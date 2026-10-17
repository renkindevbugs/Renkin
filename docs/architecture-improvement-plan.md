# Architecture improvement plan — 2026-10-17

Status: agreed direction; step 1 merged, step 2 is next. Base: `dev` at `7ec9e93a`.
Inputs: two architecture audits (`reports/Audit architektúry MVVM a DRY.md`,
`reports/Umiestnenie kódu MVVM a DRY.md`, local and uncommitted, with notes in `research_notes/`)
and an independent second review whose corrections are adopted below. Neither `reports/` nor
`research_notes/` may be committed. Line references are as of `7ec9e93a` and drift.

## 1. Goal and non-goals

Goal: fix the concrete defects the audits found, add guards against the recurring "forgotten
field" class of bugs, and then make the hottest files easier to change, without rewriting what
already works.

Non-goals:

- No big-bang refactor and no mechanical split of a file only because it is long
  (`ARCHITECTURE.md` rule against forwarding owners still applies).
- No merging of epochs, gates or other consistency mechanisms without evidence that one is
  redundant; their count alone is not a defect.
- No change to the Room schema version, saved enum ordinals, backup formats, the DataStore name
  `"settings"` or golden-hash pinned editor draft formats as a side effect of a move.
- No promise about how many files a future modifier will touch; that is measured after step 16.

## 2. What the audits got right and what was corrected

Confirmed by both reviews (code evidence, device behaviour still to verify per step):

| Finding | Evidence |
|---|---|
| Global Save drops the shape-plate gradient | `data/DataPreferences.kt:311` copies only `GlobalShapeColorKey`; the editor writes the full `GlobalShapeStyleKeys` |
| `XmlParser` rebuilds nested trees wrongly | `xml/XmlParser.kt:30` climbs only one parent whatever the depth drop |
| Watch mixes first and last appfilter declaration | `service/WatchChecker.kt:308` takes the name first-wins, the drawable lookup last-wins |
| Clear icons skips part of the shared save path | `apk/ApplicationProvider.kt:1404` uses `saveRenkinPack` without `markUnbuilt` / `afterProfileSaved` |
| Global Save runs in a composition scope | `ui/GlobalOptionsScreen.kt:330` (`rememberCoroutineScope`) |
| Mixed layers in single files | Compose adapters in `DataPreferences.kt`, a whole ViewModel in `GlobalOptionsActivity.kt`, `Context.dataStore` in `MainActivity.kt:53` |
| Minor | switches copy their input into local state, first letter can split an emoji, pack usage counts are not reactive, watch Apply composes a multi-step operation in UI |

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

## 3. Rules for every step

1. One branch per step (`fix/...` for behaviour, `refactor/...` for moves), based on current `dev`.
2. Behaviour changes and file moves never share a commit.
3. Implement, then a device checkpoint (the agent drives the debug build over adb where
   possible), then tests, full suite and `assembleDebug`, then commit; merge only after the
   developer approves.
4. Every bug fix starts with a failing test or a reproducible device scenario.
5. Keep profile session tokens, rollback and stale-result protection intact.

## 4. Steps

Status values: `todo`, `in progress`, `device check`, `merged`.

### Phase 1 — concrete defects

| # | Step | Verification | Status |
|---|---|---|---|
| 1 | Global Save keeps the shape-plate gradient: copy the whole `GlobalShapeStyleKeys` set through one shared key list | test comparing editor writes with Save copies; device: gradient plate survives Save and reopen | merged |
| 2 | `XmlParser` climbs to the correct parent after a multi-level depth drop | test `vector > group > group > path` followed by a sibling path; device: reopen a saved nested vector | todo |
| 3 | One appfilter declaration rule (last wins, `ddf8f5b4`) and one own-pack identity, used by `ApplicationManager`, `DynamicClockExporter` and `WatchChecker` | fixture with a duplicated component; legacy own-pack prefix fixture | todo |

### Phase 2 — profile operation completion

| # | Step | Verification | Status |
|---|---|---|---|
| 4 | Clear icons goes through the shared save path (`markUnbuilt`, `afterProfileSaved`), after confirming the omission was not deliberate | history check, test on unbuilt flag and draft invalidation | todo |
| 5 | `GlobalOptionsViewModel` owns the Global draft and runs Save/Apply in `viewModelScope` | test that Save survives recreation; device: Save, background, return | todo |

### Phase 3 — guards against forgotten fields

| # | Step | Verification | Status |
|---|---|---|---|
| 6 | Field-completeness tests: every `DbApplication` column through `BackupCodec` and `BackupInspectionProjection`; global colour styles through every writer | reflection-based tests that fail on a new field | todo |
| 7 | Remove dead code (unused provider pass-throughs, dead persisted-undo branch, unused extensions and widgets); named constants for scale and Material You stroke ranges and render sizes; shared `isMissingIcon` predicate | full suite, no behaviour change | todo |

### Phase 4 — small moves with a clear purpose (no behaviour change)

| # | Step | Constraint | Status |
|---|---|---|---|
| 8 | `Context.dataStore` to `data/SettingsDataStore.kt` | DataStore name stays `"settings"` | todo |
| 9 | `GlobalOptionsViewModel` into its own file | — | todo |
| 10 | Preference UI adapters and labels out of `DataPreferences.kt` into `ui/` | — | todo |
| 11 | Shared `ColorizerStyle` mapping and validation | byte-identical formats, golden hashes unchanged | todo |
| 12 | Split `DbApplication.kt` into entities, DAOs, database and migrations | `RenkinPackDatabase` stays in package `data` (schema path, `RenkinPackMigrationTest`); schema JSON unchanged; update `CLAUDE.md`/`ARCHITECTURE.md` references | todo |

### Phase 5 — independent responsibilities

| # | Step | Constraint | Status |
|---|---|---|---|
| 13 | From `MainViewModel`: pack browsing, settings/installer/Shizuku, preview render pass-throughs | only groups without shared session state | todo |
| 14 | From `MainScreen`: build/install and backup dialog hosts into their feature packages | — | todo |
| 15 | Session core (profile gate, tokens, baselines) | not split mechanically; separate design note first | todo |

### Phase 6 — modifiers and adaptive geometry

| # | Step | Constraint | Status |
|---|---|---|---|
| 16 | Golden fixtures for every existing adaptive render path (preview, flattening modifier, preset preview, restore, export), then one shared geometry object | fixtures first, then the change | todo |
| 17 | Shared modifier representation on top of those fixtures | enum order and binary formats unchanged | todo |

## 5. Open questions for the developer

- `restorePrefs` keeps only 3 of the 4 `DEVICE_LOCAL_PREF_NAMES`; `LastWatchCheckAtKey` is
  cleared on restore. Intended?
- `getOrCreate` runs during composition (`3cc9a3d3`). Keep and document, or move?
