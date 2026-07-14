# Icon Watch & Notifications — design / brainstorm

> Branch: `feature/icon-watch-notifications`
> Status: **brainstorm only, no code written yet.** This doc is the spec for the
> next session. Read it together with the "Existing code to reuse" section before
> implementing.

## 1. Goal (in the user's words)

The user wants to **watch apps** and, for each, **watch one or more icon packs**.
Regularly check whether a watched icon pack has published a **new icon** for a
watched app. When it has, fire a **notification**. Tapping the notification opens
the app on the home screen and shows a **modal: original icon vs the newly found
icon**, asking "set this icon?" — Yes applies it immediately (no going through the
full Edit/Modifier flow).

Configuration lives in a **new screen** reached from a **bell icon in the home top
bar**.

Watching should be **event-driven**: only look when a watched pack actually
*updates*. No point scanning a pack that hasn't changed.

### Rule lifecycle (finalised with user)
- A **rule** = a set of apps + either **specific icon packs** or **all installed
  packs** to watch for them (`watchAllPacks` flag).
- Completion is triggered **when a new icon is found** for an app (i.e. when a
  suggestion is generated), **regardless of whether the user applies or ignores it**:
  - That app is **split off** the rule into a **new rule marked "completed"** (which
    records the matched pack(s) and keeps the suggestion so the user can still apply
    it later).
  - The original rule keeps watching its remaining apps; if it had only that one
    app, the rule itself becomes "completed".
- **Completed** rules are **not checked again** and persist until the user **deletes**
  them. Ignoring a notification does **not** delete anything — it stays as a completed
  rule.
- The **home-screen bell shows a badge** = number of completed (undeleted) rules,
  e.g. `1` means one rule is done and awaiting the user.

## 2. Key design questions & answers

### Q: Can we check only when the pack updates (not poll)?
**Yes.** Decision (user picked the more robust / maintainable option, see §8 #5):
**WorkManager is the single engine.**

- A **`WorkManager` periodic job** (e.g. once/day) iterates the packs referenced by
  active rules, compares each pack's **current versionCode** against the **last seen
  versionCode** in `WatchState`, and **only scans when it changed**. Version-gate =
  "no update → no scan", so it never wastes work, and WorkManager survives process
  death / reboot without our own `BootCompletedReceiver` plumbing. This is simpler to
  evolve than the started-service + runtime-receiver approach.
- **Optional later fast-path:** extend the existing runtime receiver
  ([`PackageAddedService`](app/src/main/kotlin/dev/alembiconsProject/alembicons/service/PackageAddedService.kt) /
  [`PackageAddedReceiver`](app/src/main/kotlin/dev/alembiconsProject/alembicons/service/PackageAddedReceiver.kt))
  with `ACTION_PACKAGE_REPLACED` to enqueue an expedited one-off check immediately
  when a watched pack updates. Not required for v1 — the periodic job already covers
  correctness. (Implicit package broadcasts can't be manifest-declared since API 26,
  hence the runtime registration.)

`ApplicationManager.getVersionCode(PackageInfo)` already exists for the version
check; `getPackage(packageName)` returns the `PackageInfo`.

### Q: which packs does a rule watch?
A rule watches **either** an explicit set of packs **or** all installed packs
(`WatchRule.watchAllPacks = true`). With "all packs", any installed pack that starts
offering an icon for a watched app triggers a suggestion.

### Q: How do we know a pack has a "new icon" for an app?
For a given (app, pack) we can already resolve whether the pack ships an icon for
the app's component and **which drawable** via:
- `ApplicationManager.getAppFilterRawElements(packPkg, listOf(app))`
- `ApplicationManager.getDrawableFromAppFilterElements(packPkg, listOf(app), elements)`
  → `Map<InstalledApplication, ResourceDrawable>`.

"New" needs a stored baseline. Recommended: store per (ruleId/app, pack) a
**fingerprint** = pack versionCode + resolved drawable identity. Drawable identity
options (pick one):
- **drawable resource name** (cheap, but a pack could change the art under the same
  name — misses some updates), or
- **content hash** of the resolved drawable bytes (robust; catches art changes).
  Recommended: hash the `ResourceDrawable` bitmap (reuse `toSafeBitmapOrNull`).

A watched (app, pack) yields a suggestion when: the pack version changed **and**
(an icon now exists where the fingerprint was empty **or** the fingerprint
differs). Store the new fingerprint only **after** the user resolves the suggestion
(apply or dismiss) so we don't keep re-notifying, but also don't silently swallow a
change the user ignored — see open questions.

## 3. Data model (Room) — new entities

Current DB: [`AlchemiconPackDatabase` / `AlchemiconPackDao` / `DbApplication`](app/src/main/kotlin/dev/alembiconsProject/alembicons/data/DbApplication.kt)
(version 1, single table). We need to **bump the version** and add a migration (or,
since the pack table is rebuilt on every install, a destructive migration for the
new tables is acceptable — confirm with user).

Proposed tables:

```
WatchRule
  id: Long (PK, autoGenerate)
  watchAllPacks: Boolean      // true → ignore WatchRulePack, check every installed pack
  completed: Boolean          // done → not checked; user must delete; counts toward bell badge
  createdAt: Long
  completedAt: Long?

WatchRuleApp                  // apps belonging to a rule (many per rule)
  ruleId: Long (FK → WatchRule.id)
  packageName: String
  activityName: String
  (PK: ruleId + packageName + activityName)

WatchRulePack                 // explicit packs a rule monitors (only when !watchAllPacks)
  ruleId: Long (FK → WatchRule.id)
  iconPackPackage: String
  (PK: ruleId + iconPackPackage)

WatchState                    // baseline content fingerprint per rule+app+pack
  ruleId: Long               // overlapping rules/profiles advance independently
  packageName: String
  activityName: String
  iconPackPackage: String
  lastPackVersionCode: Long
  lastIconName: String?       // null = pack had no icon for the app last time
  lastIconHash: String?       // CONTENT hash of the resolved drawable bitmap (decided: hash)
  lastCheckedAt: Long
  (PK: ruleId + packageName + activityName + iconPackPackage)

IconSuggestion                // one per (rule, app) — grouped across packs
  id: Long (PK, autoGenerate)
  ruleId: Long                // the COMPLETED rule this belongs to
  packageName: String
  activityName: String
  createdAt: Long

IconSuggestionCandidate       // the new icon options, one per matching pack
  suggestionId: Long (FK → IconSuggestion.id)
  iconPackPackage: String
  drawableName: String
  iconHash: String
  (PK: suggestionId + iconPackPackage)
```

> Note: a rule with N apps × M packs fans out to N×M (app,pack) watch pairs.
> When several packs offer an icon for the same app, they become **multiple
> candidates of one suggestion** (grouped — user picks the pack in the modal).

## 4. UI

### 4.1 Entry point
- Add a **bell `IconButton`** to the home top bar in
  [`MainScreen.kt` `TitleBar`](app/src/main/kotlin/dev/alembiconsProject/alembicons/ui/MainScreen.kt)
  (next to refresh / info / settings). Opens the Watch screen.
- The bell carries a **badge** = number of completed (undeleted) rules (M3
  `BadgedBox`). `0` → no badge.

### 4.2 Watch screen (new composable / full-screen dialog)
Mirror the look of the existing full-screen editor
[`OptionsDialog`](app/src/main/kotlin/dev/alembiconsProject/alembicons/ui/IndividualOptions.kt)
(slide-in `Dialog`, `Surface`, M3 Expressive). Contents:
- List of **rules**, grouped Active vs Completed (or a "done" chip on completed
  ones). Each rule card shows its apps + watched packs.
- Completed rule → visible **done badge** + **delete** button (deleting is the only
  way to clear it; it is never re-checked).
- **FAB / add button** → "New rule" editor:
  - Multi-select **apps** (from `appProvider.applicationList`).
  - **"Watch all icon packs"** toggle; when off, multi-select **icon packs**
    (from `appProvider.iconPacks`).
  - Reuse existing dropdown/icon helpers where possible.
- Editing an existing active rule (add/remove apps or packs) should be possible.
- A **completed** rule that still holds a suggestion can be **applied from here too**
  (opens the same compare modal), not only via the notification.

### 4.3 Notification → home-screen apply modal
- Notification `contentIntent` → `MainActivity` with extras identifying the
  suggestion (e.g. `suggestionId`, or app/activity/pack/drawableName).
- `MainActivity` must handle the intent on **cold start** (`onCreate`) **and**
  warm (`onNewIntent` — add the override; consider `launchMode=singleTop`). Expose
  the pending suggestion as Compose state and show a modal in `MainColumn`.
- Modal: **original app icon** (left) vs **newly generated icon** (right),
  "Set this icon?" → confirm / cancel. If the suggestion has **multiple candidate
  packs**, the modal lets the user **pick which pack** (e.g. a row of pack chips /
  segmented buttons); the right-hand preview updates per selection. (We already
  removed a wallpaper-preview modal; this is a different, simpler compare modal — see
  `ComparisonHeader` for a reference layout of original→preview.)
- The "new icon" is produced by running the resolved pack `ResourceDrawable`
  through the normal generation path:
  `appProvider.getIcon(app, options, customIcon = resolvedResourceDrawable)`
  (see [`ApplicationProvider.getIcon`](app/src/main/kotlin/dev/alembiconsProject/alembicons/apk/ApplicationProvider.kt)).
- **Confirm = store and consume (decided):** `editApplication(index, app.changeExport(icon))`
  (same as the editor's `onConfirm`) and **show a toast** telling the user to press
  **Build** to regenerate the pack with the new icon. **Do NOT auto-rebuild/reinstall.**
  Then delete the consumed completed rule together with its suggestion/candidates. It no
  longer belongs in Completed after the user applied it.
- **Cancel/ignore:** nothing is applied; the completed rule + suggestion stay so the
  user can still apply later from the watch screen. The bell badge keeps counting it.

## 5. Background flow (putting it together)

```
WorkManager periodic tick   (optional fast-path: PACKAGE_REPLACED → expedited one-off)
        │
        ▼
  For each active (non-completed) rule:
    packs = if rule.watchAllPacks then all installed packs else rule packs
    for each pack in packs:
      if pack.versionCode == WatchState.lastPackVersionCode: skip   // no update → no scan
      for each app in rule:
        resolve icon for (app, pack) via appfilter; hash the bitmap
        new = (icon now exists where it didn't) OR (hash != lastIconHash)
        update WatchState(versionCode, name, hash, checkedAt)
        if new: collect candidate (pack, drawableName, hash) for this app

    for each app that collected >=1 candidate:
      create IconSuggestion(app) + IconSuggestionCandidate(per pack)
      SPLIT app out of the rule into a NEW rule marked completed (records matched packs);
        remove app from the original rule (complete the rule if it becomes empty)
      post ONE grouped notification (deep-link with suggestionId)
      → bell badge increments (counts completed rules)
        │
        ▼
  [user taps notification]  ── or ──  [opens watch screen → taps the completed rule]
        │
        ▼
  MainActivity → home modal (original vs new; pick pack if multiple candidates)
        ├─ confirm → store icon (changeExport) + TOAST "press Build to apply";
        │            delete the completed rule + suggestion. (NO auto-rebuild.)
        └─ cancel  → keep the completed rule + suggestion (still applyable later)

  Completed rules are never re-checked; user deletes them manually → badge decrements.
```

## 6. Manifest / permissions / deps
- `POST_NOTIFICATIONS` — already declared and requested.
- Add **`androidx.work:work-runtime-ktx`** — confirmed **NOT yet** in
  `gradle/libs.versions.toml` / `app/build.gradle.kts`; must be added. WorkManager is
  the **primary (and for v1, only) engine** (decided in §8 #5 for durability +
  maintainability).
- New **notification channel** for "icon available" (separate from the existing
  `alembicons_package_added` / `alembicons_update_pack` channels in
  [`NotificationManager`](app/src/main/kotlin/dev/alembiconsProject/alembicons/service/NotificationManager.kt)).
- *(Optional, later)* fast-path: extend the runtime `IntentFilter` in
  `PackageAddedService` with `Intent.ACTION_PACKAGE_REPLACED` to enqueue an expedited
  one-off check. Not needed for v1.

## 7. Existing code to reuse (pointers for next session)
- Resolve a pack's icon for an app: `ApplicationManager.getAppFilterRawElements`,
  `getDrawableFromAppFilterElements`, `getIconPackDrawableNames/Ids/Drawables`,
  `getResources`, `getVersionCode`, `getPackage`.
- Generate the final icon from a pack drawable: `ApplicationProvider.getIcon(app,
  options, customIcon)`; build options via `GenerationOptions.fromPreferences`.
- Apply / persist: `ApplicationProvider.editApplication`, `changeExport`,
  `saveAlchemiconPack` (icons persist to Room on pack install/build).
- Notifications & channels & deep-link intents: `NotificationManager`.
- Runtime package broadcasts: `PackageAddedService` + `PackageAddedReceiver`.
- Full-screen M3 dialog & original→preview header: `IndividualOptions.kt`
  (`OptionsDialog`, `ComparisonHeader`).
- DataStore prefs pattern: `data/DataPreferences.kt`.
- Room pattern: `data/DbApplication.kt`.

## 8. Decisions (resolved with user 2026-06-13)
1. **"New icon" = content hash** of the resolved drawable bitmap (not just name).
2. **After applying: store only**, no auto-rebuild. Show a **toast** prompting the
   user to press **Build** to regenerate the pack with the new icon.
3. **Ignoring a suggestion** does not re-notify: the app is already split into a
   **completed** rule at suggestion time, so it's never re-checked. The completed
   rule lingers (applyable later) and is counted by the **bell badge** until the user
   applies or deletes it. Applying consumes the completed rule; closing the modal does not.
4. **Multiple packs → one grouped notification**; the user **picks the pack in the
   modal** (candidates list).
5. **Engine: WorkManager only** for v1 (most robust + easiest to extend). Event
   receiver is an optional later optimisation.
6. A rule watches **specific packs OR all installed packs** (`watchAllPacks`).

### Remaining minor questions
- **DB migration:** proper Room migration vs destructive. Watch tables are new and
  must persist across rebuilds; the existing `DbApplication` table is rebuilt on each
  pack install so it can tolerate a destructive path — but a real migration is safer.
- **WatchState pruning:** when a rule/app/pack is removed or a rule completes, prune
  the now-irrelevant `WatchState` rows.
- **Badge source of truth:** simplest = `COUNT(*) WHERE completed = true`.

## 9. Suggested implementation phases
1. **Data layer:** Room v2 + entities + DAO + a `WatchRepository`.
2. **Watch screen UI** (bell entry + rule list + add/edit rule) writing to the repo.
   Get the user's sign-off on the UX here (interactive prototype per their
   preference).
3. **Detection engine:** a `WatchChecker` that scans active rules (version-gated per
   pack), produces grouped `IconSuggestion` + `IconSuggestionCandidate` rows, performs
   the **rule split/complete**, and updates `WatchState`. Unit-testable in isolation.
4. **Trigger:** a `WorkManager` periodic worker calling `WatchChecker`. (Optional
   later: `PACKAGE_REPLACED` fast-path enqueuing an expedited check.)
5. **Notification + deep link:** new channel + grouped suggestion deep-link intent
   (carries `suggestionId`).
6. **Apply modal** on home screen (`MainActivity` intent handling + `MainColumn`
   modal, pack picker for multiple candidates) → store icon + toast "press Build".
7. Polish: completed-rule management + bell badge, edge cases from §8.
