# Architecture improvement plan — 2026-10-17

Status: steps 1–11 merged; review follow-up verified, step 12 is next.
Base: `dev` at `7ec9e93a`, revised after a second review of the plan at `a2eeab20`.
Inputs: two architecture audits (`reports/Audit architektúry MVVM a DRY.md`,
`reports/Umiestnenie kódu MVVM a DRY.md`, local and uncommitted, with notes in `research_notes/`)
and an independent second review of the audits and of this plan, whose corrections are adopted
below. Neither `reports/` nor `research_notes/` may be committed. Line references drift.

## 1. Goal and non-goals

Goal: fix the concrete defects the audits found, add guards against the recurring "forgotten
field" class of bugs, and then make the hottest files easier to change, without rewriting what
already works.

Non-goals:

- No big-bang refactor. The large refactors (phases 5–6) are not approved as a whole; each
  responsibility is judged and approved on its own.
- No mechanical split of a file only because it is long, and no new class that only forwards
  calls (`ARCHITECTURE.md` rule against forwarding owners). Extract only where a real owner of
  a cache, job, state or rendering policy appears.
- No merging of epochs, gates or other consistency mechanisms without evidence that one is
  redundant; their count alone is not a defect.
- No change to the Room schema version, saved enum ordinals, backup formats, the DataStore name
  `"settings"` or golden-hash pinned editor draft formats as a side effect of a move.
- No promise about how many files a future modifier will touch.

## 2. What the audits got right and what was corrected

Confirmed by both reviews (code evidence; device behaviour is verified per step):

| Finding | Evidence |
|---|---|
| Global Save dropped the shape-plate gradient (fixed, step 1) | `copyGlobalModifierPrefsFrom` copied only `GlobalShapeColorKey` |
| `XmlParser` rebuilds nested trees wrongly | `xml/XmlParser.kt:30` climbs only one parent whatever the depth drop |
| Watch mixes first and last appfilter declaration | `service/WatchChecker.kt:308` takes the name first-wins, the drawable lookup last-wins |
| Clear icons skips part of the shared save path | `apk/ApplicationProvider.kt:1404` uses `saveRenkinPack` without `markUnbuilt` / `afterProfileSaved` |
| Global Save runs in a composition scope | `ui/GlobalOptionsScreen.kt:330` (`rememberCoroutineScope`) |
| Mixed layers in single files | Compose adapters in `DataPreferences.kt`, a whole ViewModel in `GlobalOptionsActivity.kt`, `Context.dataStore` in `MainActivity.kt:53` |
| Minor | switches copy their input into local state, first letter can split an emoji, pack usage counts are not reactive, watch Apply composes a multi-step operation in UI (all in phase 7) |

Corrected or withdrawn:

- The unsaved-icon journal and marker do **not** lack fsync: `AtomicFile.finishWrite()` syncs.
- "Rotation saves half of the icons" is withdrawn: persistence runs in `NonCancellable` with
  rollback (`ApplicationProvider.kt:821`) and phones lock portrait (`allowLandscape`). Only the
  computation can be cancelled, so the user may have to repeat Save.
- Crash logs are listed on `Dispatchers.Default`; only delete and the copy read remain on Main.
- Several serializations are not a DRY violation by themselves: backup, draft and preset have
  different contracts. Shared mapping and validation are unified; formats stay separate.
- The 51 % hotspot figure (353 of 691 commits touch `MainViewModel`, `ApplicationProvider`,
  `MainScreen` or `strings.xml`) is real but includes `strings.xml` and does not prove bad
  architecture by itself. The real problems are the number of responsibilities, coupled session
  ownership and hand-synchronised field lists.
- Removing dead code is not "zero risk": references through function references, tests,
  reflection and the framework are checked first.

## 3. Process for every step

Follows `CLAUDE.md`:

1. Reproduction scenario first (failing test or exact device steps), recorded in the branch.
2. One branch per step (`fix/...` for behaviour, `refactor/...` for moves), based on current `dev`.
   Behaviour changes and file moves never share a commit.
3. Implementation, then device confirmation. The agent drives the debug build over adb only when
   the developer has authorised it for that step; otherwise the developer gets the numbered
   device checks.
4. After confirmation: tests, full suite, `assembleDebug`, commit.
5. Merge into `dev` only after a separate explicit approval from the developer.
6. Keep profile session tokens, rollback, lock order and stale-result protection intact.

## 4. Steps

Status values: `todo`, `in progress`, `device check`, `merged`.

### Phase 1 — concrete defects

| # | Step | Verification | Status |
|---|---|---|---|
| 1 | Global Save keeps the shape-plate gradient: copy the whole `GlobalShapeStyleKeys` set through one shared key list | test comparing editor writes with Save copies; device: gradient plate survives Save and reopen | merged |
| 2 | `XmlParser` climbs to the correct parent after a multi-level depth drop | test `vector > group > group > path` followed by a sibling path; device: reopen a saved nested vector | merged |
| 3 | One appfilter declaration rule (last wins, `ddf8f5b4`) for `ApplicationManager`, `DynamicClockExporter` and `WatchChecker` | fixture with a duplicated component; watch name and hash describe the same drawable; previews and dynamic clocks unchanged otherwise | merged |
| 4 | One own-pack identity (current and legacy prefixes) used by every source filter | fixture with a legacy own pack: it is never offered as an icon source | merged |

### Phase 2 — profile operation completion

| # | Step | Verification | Status |
|---|---|---|---|
| 5 | Clear icons goes through the shared save path (`markUnbuilt`, `afterProfileSaved`), after confirming the omission was not deliberate | history check; tests on the unbuilt flag and draft invalidation | merged |
| 6 | `GlobalOptionsViewModel` owns the Global draft, its baseline, the Save progress and the Save result, and runs Save/Apply in `viewModelScope` | tests: Activity recreation shows the finished Save; background and return; a result after a profile switch is rejected | merged |

### Phase 3 — guards against forgotten fields

| # | Step | Verification | Status |
|---|---|---|---|
| 7 | Field contract for `DbApplication` in backups: every column is declared as preserved, derived (e.g. `profileId` supplied outside `BackupCodec`) or normalized, and a test fails on a column without a declaration | reflection over the entity against the declared contract; round trip checks preserved fields | merged |
| 8 | `BackupInspectionProjection` keeps every field the decoder needs after filtered loading | test decoding from the filtered projection equals decoding from the full row | merged |
| 9 | Global colour styles: every `ColorStyleKeys` set is covered by every writer and reader | extend the step 1 test to readers and to `ProfileStyle` capture | merged |
| 10 | Remove verified dead code only (unused provider pass-throughs, dead persisted-undo branch, unused extensions and widgets) | references checked in main, tests, function references and the framework; full suite | merged |
| 11 | Named constants for scale and Material You stroke ranges and render sizes; shared `isMissingIcon` predicate | each constant's meaning and boundary behaviour checked at every call site; tests on boundaries | merged |

### Phase 4 — small moves with a clear purpose (no behaviour change)

| # | Step | Constraint | Status |
|---|---|---|---|
| 12 | `Context.dataStore` to `data/SettingsDataStore.kt` | DataStore name stays `"settings"` | todo |
| 13 | `GlobalOptionsViewModel` into its own file | — | todo |
| 14 | Preference UI adapters and labels out of `DataPreferences.kt` into `ui/` | keep reads through `ProfileStyleAccess`; never replace them with direct DataStore reads | todo |
| 15 | Shared `ColorizerStyle` mapping and validation | byte-identical formats, golden hashes unchanged | todo |
| 16 | Split `DbApplication.kt` into entities, DAOs, database and migrations | `RenkinPackDatabase` stays in package `data` (schema path, `RenkinPackMigrationTest`); schema JSON unchanged; update `CLAUDE.md`/`ARCHITECTURE.md` references | todo |

### Phase 5 — independent responsibilities (each approved separately)

| # | Step | Constraint | Status |
|---|---|---|---|
| 17 | From `MainViewModel`: pack browsing and settings/installer/Shizuku | only groups that gain a real owner of state, jobs or caches; no new forwarding class | todo |
| 18 | Preview rendering out of `MainViewModel` only if a real owner of cache, jobs or render policy appears | otherwise leave the pass-throughs | todo |
| 19 | From `MainScreen`: build/install and backup dialog hosts into their feature packages | — | todo |
| 20 | Session core (profile gate, tokens, baselines) | not split mechanically; separate design note first | todo |

### Phase 6 — adaptive geometry and modifier representation (two separate areas)

| # | Step | Constraint | Status |
|---|---|---|---|
| 21 | Adaptive geometry: golden fixtures for every existing render path (preview, flattening modifier, preset preview, restore, export), then one shared geometry object | fixtures first, then the change | todo |
| 22 | Modifier representation: data-contract tests first (disabled settings, stage order, mapping to presets and drafts, backward compatibility, existing golden hashes), then a shared representation | enum order and binary formats unchanged | todo |

### Phase 7 — confirmed smaller problems (backlog, by priority)

| # | Step | Verification | Status |
|---|---|---|---|
| 23 | Watch Apply: the "apply icon, then delete the rule" operation moves from the composable into one ViewModel/service operation | test the order and failure of each part | todo |
| 24 | Stateless shared switches and sliders (no local copy of the input value) | Compose test: external value change is shown | todo |
| 25 | Pack usage counts update reactively | test after an edit and a profile switch | todo |
| 26 | First letter of generated text icons respects Unicode grapheme clusters (emoji) | test with emoji and combining characters | todo |

## 5. Review follow-up — verified

Branch: `fix/global-save-appfilter-review`, based on `dev` at `c32cba30`.
Device checks and automated verification passed on 2026-10-17. The developer authorized
on-device verification and, conditional on success, commit and merge.

- Global Save now handles errors from preference reads as well as rendering and persistence.
  Cancellation still propagates, and progress covers the entire operation.
- Save uses one captured draft for rendering, preferences, target flags and its saved baseline.
  Later control edits remain dirty. Baseline and the applied flag publish only for the captured
  profile session.
- Appfilter bulk loading selects the last declaration before loading resources, matching the
  name and targeted lookup paths. A missing or malformed winning drawable does not fall back to
  an earlier declaration of the same component.

Device results (CPH2663, debug package only):

1. Saved a circle plate with a radial gradient. Room retained `GRADIENT` / `RADIAL`; after a
   cold restart, the editor retained the style and its Save button was disabled.
2. Applied the captured style to 217 empty slots, switched to the app's MainActivity while it
   ran, and reopened Global style after completion. The profile increased from 3 to 220 stored
   icons; all 217 new icons were saved and Save was disabled on reopen. No debug-process crash.
3. An existing stale watch suggestion correctly refused Apply. A manual watch check refreshed
   it; applying the Lawnicons AntennaPod candidate matched its preview and removed that rule.
   Watch suggestions decreased from 10 to 9.
4. Restored all 47 persistent files, including `no_backup`, byte-for-byte against their original
   SHA-256 manifest. All six profiles returned to their original icon counts (939 stored rows
   total), and relaunch returned to the original active profile with its original three icons.

The phone locks portrait, so physical rotation during Save was not tested. A Robolectric host
Activity recreation test retains the running GlobalOptionsViewModel and verifies its completed
baseline. Deterministic tests also cover a blocked preference read with a later draft edit,
Reset and duplicate-Save rejection, preference-read failure, and missing/malformed last
appfilter drawables without reviving an older entry or losing unrelated healthy components.

Both targeted classes passed (34 tests). The full `testDebugUnitTest` suite passed all 1,462
tests across 197 suites with no failures, errors or skips. `assembleDebug` and `git diff --check`
passed. Tests use the existing local `build/verify-jvm.gradle` JVM workaround.

## 6. Open questions for the developer

- `restorePrefs` keeps only 3 of the 4 `DEVICE_LOCAL_PREF_NAMES`; `LastWatchCheckAtKey` is
  cleared on restore. Intended?
- `getOrCreate` runs during composition (`3cc9a3d3`). Keep and document, or move?
