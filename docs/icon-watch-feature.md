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

### Rule lifecycle the user described
- A **rule** = a set of apps + a set of icon packs to watch for them.
- When an icon is found and **applied** for one app in a rule:
  - That app is **split off** the rule into a **new rule marked "completed"**.
  - The original rule keeps watching its remaining apps.
  - If the rule had only that one app, the rule itself becomes "completed".
- **Completed** rules are shown but **not checked again**; the user must delete
  them manually (a visible "done" marker + delete action).

## 2. Key design questions & answers

### Q: Can we check only when the pack updates (not poll)?
**Yes.** Two complementary mechanisms:

1. **Event-driven (primary):** listen for `Intent.ACTION_PACKAGE_REPLACED`
   (data scheme `package`) for the watched icon-pack package names. On Android 8+
   these package broadcasts **cannot** be declared in the manifest as implicit
   receivers — they must be **runtime-registered**. The app already does exactly
   this in [`PackageAddedService`](app/src/main/kotlin/dev/alembiconsProject/alembicons/service/PackageAddedService.kt)
   (a started Service that registers `PackageAddedReceiver` for `PACKAGE_ADDED`/
   `PACKAGE_REMOVED`). We extend that filter with `ACTION_PACKAGE_REPLACED` and
   branch in [`PackageAddedReceiver`](app/src/main/kotlin/dev/alembiconsProject/alembicons/service/PackageAddedReceiver.kt).

2. **Safety-net periodic check (secondary):** a `WorkManager` periodic job (e.g.
   once/day) that, for each watched pack, compares the pack's **current
   versionCode** against the **last seen versionCode** we stored, and only scans
   when it changed. This catches updates that happened while our runtime receiver
   wasn't alive (started services can be killed; that's why there's already a
   `BootCompletedReceiver`). The version-code gate means "no update → no scan".

`ApplicationManager.getVersionCode(PackageInfo)` already exists for the version
check; `getPackage(packageName)` returns the `PackageInfo`.

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
  completed: Boolean          // done → not checked, user must delete
  createdAt: Long

WatchRuleApp                  // apps belonging to a rule (many per rule)
  ruleId: Long (FK → WatchRule.id)
  packageName: String
  activityName: String
  (PK: ruleId + packageName + activityName)

WatchRulePack                 // packs a rule monitors (many per rule)
  ruleId: Long (FK → WatchRule.id)
  iconPackPackage: String
  (PK: ruleId + iconPackPackage)

WatchState                    // baseline fingerprint per app+pack
  packageName: String
  activityName: String
  iconPackPackage: String
  lastPackVersionCode: Long
  lastIconName: String?       // null = pack had no icon for the app last time
  lastIconHash: String?       // optional content hash
  lastCheckedAt: Long
  (PK: packageName + activityName + iconPackPackage)

PendingIconSuggestion         // what a fired notification points at
  id: Long (PK, autoGenerate)
  packageName: String
  activityName: String
  iconPackPackage: String
  drawableName: String        // the new icon's drawable in the pack
  createdAt: Long
```

> Note: a rule with N apps × M packs fans out to N×M (app,pack) watch pairs.

## 4. UI

### 4.1 Entry point
- Add a **bell `IconButton`** to the home top bar in
  [`MainScreen.kt` `TitleBar`](app/src/main/kotlin/dev/alembiconsProject/alembicons/ui/MainScreen.kt)
  (next to refresh / info / settings). Opens the Watch screen.

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
  - Multi-select **icon packs** (from `appProvider.iconPacks`).
  - Reuse existing dropdown/icon helpers where possible.
- Editing an existing active rule (add/remove apps or packs) should be possible.

### 4.3 Notification → home-screen apply modal
- Notification `contentIntent` → `MainActivity` with extras identifying the
  suggestion (e.g. `suggestionId`, or app/activity/pack/drawableName).
- `MainActivity` must handle the intent on **cold start** (`onCreate`) **and**
  warm (`onNewIntent` — add the override; consider `launchMode=singleTop`). Expose
  the pending suggestion as Compose state and show a modal in `MainColumn`.
- Modal: **original app icon** (left) vs **newly generated icon** (right),
  "Set this icon?" → confirm / cancel. (We already removed a wallpaper-preview
  modal; this is a different, simpler compare modal — see `ComparisonHeader` for a
  reference layout of original→preview.)
- The "new icon" is produced by running the resolved pack `ResourceDrawable`
  through the normal generation path:
  `appProvider.getIcon(app, options, customIcon = resolvedResourceDrawable)`
  (see [`ApplicationProvider.getIcon`](app/src/main/kotlin/dev/alembiconsProject/alembicons/apk/ApplicationProvider.kt)).
- **Confirm** = apply: `editApplication(index, app.changeExport(icon))` (same as the
  editor's `onConfirm`). Then run the **rule-split / mark-completed** logic and
  delete the consumed `PendingIconSuggestion`. Decide whether to also rebuild/
  reinstall the pack immediately or leave it for the user/auto-update (open Q).

## 5. Background flow (putting it together)

```
[watched pack updated]
  PACKAGE_REPLACED(pack)  ──or──  WorkManager periodic tick
        │
        ▼
  For each active (non-completed) rule that includes this pack:
    for each app in rule:
      resolve icon for (app, pack) via appfilter
      compare fingerprint vs WatchState
      if changed/new:
        create PendingIconSuggestion(app, pack, drawableName)
        post notification (deep-link with suggestionId)
      update WatchState (versionCode, fingerprint, checkedAt)
        │
        ▼
  [user taps notification]
        │
        ▼
  MainActivity → home modal (original vs new) → confirm?
        ├─ yes → apply icon; split app into new completed rule;
        │         remove app from original rule (or complete it if last);
        │         delete suggestion
        └─ no  → dismiss (suggestion kept or dropped — open Q)
```

## 6. Manifest / permissions / deps
- `POST_NOTIFICATIONS` — already declared and requested.
- Extend the runtime `IntentFilter` in `PackageAddedService` with
  `Intent.ACTION_PACKAGE_REPLACED` (keep data scheme `package`). No new manifest
  receiver needed (implicit package broadcasts are blocked since API 26).
- Add **`androidx.work:work-runtime-ktx`** for the periodic safety-net job (verify
  it's not already in `gradle/libs.versions.toml` / `app/build.gradle.kts`).
- New **notification channel** for "icon available" (separate from the existing
  `alembicons_package_added` / `alembicons_update_pack` channels in
  [`NotificationManager`](app/src/main/kotlin/dev/alembiconsProject/alembicons/service/NotificationManager.kt)).
- Reliability: the existing watcher is a started Service revived by
  `BootCompletedReceiver`. For this feature, prefer **WorkManager** as the durable
  scheduler and keep the runtime receiver as a fast-path. (Open Q: migrate fully to
  WorkManager?)

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

## 8. Open questions (decide with user before/while implementing)
1. **"New icon" definition:** drawable-name change vs content-hash. Hash is more
   correct but costs a bitmap decode per (app,pack) per check. Default: hash.
2. **After applying** an icon from a suggestion: rebuild & reinstall the Renkin Pack
   immediately, or just store and let the user build later (respect the existing
   `AutomaticallyUpdate` setting)?
3. **Dismiss/"not now":** if the user ignores or declines a suggestion, do we
   update the fingerprint (so we don't re-notify for the same version) or keep
   re-notifying on the next pack update only? Default: update fingerprint on the
   pack version so we notify again only on the *next* pack update.
4. **Multiple packs** offering an icon for the same app at once: one notification
   per (app,pack), or one grouped notification letting the user pick the pack in the
   modal? Affects modal UX.
5. **DB migration:** proper Room migration vs destructive (the pack table is
   rebuilt anyway, but watch tables are new and must persist).
6. **WatchState lifecycle:** when a rule/app/pack is removed, prune its WatchState
   rows.
7. **Reliability target:** is WorkManager-only acceptable (simpler, durable) if the
   event receiver proves flaky?

## 9. Suggested implementation phases
1. **Data layer:** Room v2 + entities + DAO + a `WatchRepository`.
2. **Watch screen UI** (bell entry + rule list + add/edit rule) writing to the repo.
   Get the user's sign-off on the UX here (interactive prototype per their
   preference).
3. **Detection engine:** a `WatchChecker` that, given a pack, scans rules and
   produces `PendingIconSuggestion`s + updates `WatchState`. Unit-testable in
   isolation.
4. **Triggers:** extend `PackageAddedReceiver` for `PACKAGE_REPLACED` + a
   `WorkManager` periodic worker, both calling `WatchChecker`.
5. **Notification + deep link:** new channel + suggestion deep-link intent.
6. **Apply modal** on home screen (`MainActivity` intent handling + `MainColumn`
   modal) + apply + **rule split/complete** logic.
7. Polish: completed-rule management, edge cases from §8.
