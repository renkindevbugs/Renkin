# Architecture

A guide for developers (and AI assistants) working on this codebase. The app builds
installable Android icon packs on-device.

## Naming — read this first

Several names float around; this trips up newcomers:

| Thing | Value |
| --- | --- |
| App display name | **Renkin** |
| Application id / namespace | `dev.renkinProject.renkin` (renamed 2026-07; a brand-new app identity) |
| Generated icon pack package | `dev.renkinProject.renkinpack` (`IconPackBuilder.PACKAGE_NAME`); non-default profiles append `.p<profileId>` so several packs install side by side |
| External libraries | `dev.alembiconsProject.imagetracer`, `dev.alembiconsProject.tgCannyEdgeCompose` and the `com.github.Alembicons` gradle coordinates are **upstream libraries — never rename** |

It is a fork of **Alembicons** (formerly *Iconeration*). The upstream attribution
(InfoDialog F-Droid link, `aboutFork` strings) stays.

## Build & run

- JDK: the project targets **Java/Kotlin 17**.
- CLI build: `JAVA_HOME="D:/Android/jbr" ./gradlew compileDebugKotlin`
- Unit tests: `./gradlew testDebugUnitTest`
- Dependencies live in `gradle/libs.versions.toml` (version catalog) + `app/build.gradle.kts`.
  KSP is used for annotation processing (Room + Hilt) — prefer KSP over kapt.
- `.gitattributes` normalizes the repo to LF.

## Layers

The ownership refactor has completed coherent domain state (7B), durable editor drafts (7C)
and seven incremental feature-package moves (7D1–7D7). The closing 7D8 assessment below records
the remaining boundaries and follow-ups. Further service/module extraction requires an
independent responsibility or measured need, rather than a line-count target.

```
Composables (ui/, feature/*/)             ← stateless-ish views, read VM state, call VM funcs
        │  hiltViewModel()
        ▼
MainViewModel / feature.watch.WatchViewModel ← @HiltViewModel; own session/UI state, orchestrate
EditorSessionViewModel                   ← owns the individual editor's live draft and controls
EditorDraftRecoveryViewModel             ← serializes recovery offers, autosave and checkpoint cleanup
        │                                    operations on viewModelScope
        ▼
ApplicationProvider (apk/)                ← orchestrator: owns the app list, profiles, refresh,
        │                                    build snapshots and persisted results
        ├── InstalledAppCatalog            ← launcher activities, labels and application icons
        ├── IconPackCatalog                ← installed icon-pack discovery and package metadata
        ├── IconPackRepository             ← installed packs, app-filter elements, per-app
        │     │                              drawables, calendar icons (pack StateFlow)
        │     └── ApplicationManager        ← icon-pack resources and appfilter XML only
        ├── IconGenerationService          ← runs IconGenerator to produce icons
        ├── IconPackBuildService           ← assembles/signs APKs and runs install/replace
        └── RenkinPackStore                ← persistence: DbApplication ↔ drawable serialization
                └── RenkinPackRepository    ← raw Room I/O on RenkinPackDatabase

WatchRepository (data/watch/)             ← icon-watch rules/suggestions on WatchDatabase
```

`MainViewModel` owns a `BuildInstallCoordinator` for its lifetime. The coordinator owns the
build/install job, APK handoffs and one sealed `BuildInstallState`: Idle, Building,
ChoosingInstaller, Installing, Conflict, Done or Failed. Progress and pending artifacts belong
to their applicable state; the existing MainViewModel UI properties are read-only projections,
not independently writable flags. Installer catalog/preferences and Shizuku settings remain
with MainViewModel for this extraction. MainViewModel validates the active session before
starting and owns the app-list/diff/undo baseline callbacks; provider/build services retain the
snapshot, profile/storage gates, epoch checks, installer backends and artifact reservations.
Installer confirmation claims Installing synchronously. Operation ownership rejects late progress
and terminal publication, while pending choice/conflict states retain the APK. Cancellation and
ViewModel clearing release unclaimed artifacts outside cancellation; dismissing the picker finishes
the captured build without installing and synchronizes the owning profile's build baseline.
Installer outcomes are published before best-effort UI synchronization. A changed session or
failed baseline/missing-pack refresh is logged without reclassifying a successful installation,
losing a conflict prompt or replacing the installer's failure report. Cancellation still propagates.
Toast-only failures return to Idle; Failed always carries a dismissible report. A replacement that
still conflicts explicitly releases its APK as completed, so the next build can prune it. Build
progress uses one Main collector over conflated complete snapshots rather than a job per tick.
The picker and conflict-dismiss paths update the diff to reflect the already committed build.
This synchronization follows the captured pack and the baseline callback's profile/session checks,
so starting another build does not skip it. Dismissing a pending prompt or clearing the ViewModel
claims artifact cleanup before changing state; the previous job cannot release that APK as
unfinished while its completion cleanup is still running.

`MainViewModel` also owns a `BackupUiCoordinator`. It owns backup/export/import jobs, the
pending prepared import, confirmation metadata, cancellation presentation, service progress
projections and automatic-backup settings flows. UI receives only the import preview; the staged
file handle stays private to the coordinator and is released on dismissal, completion or clearing.
MainViewModel retains live profile saves, baseline/missing-pack bookkeeping and startup recovery
actions. Both use the same profile-session mutex: exports save and prepare their immutable archive
under it, then copy to SAF outside it. Import preparation and the pre-restore snapshot still happen
before the provider's session replacement callback; only durable commit replaces the session.
BackupManager, AutoBackupManager and recovery remain shared domain services. Their storage gates,
commit cancellation boundary, archive format and recovery snapshots are unchanged. A shared
profile import remains additive and does not reload the active profile. Cancellation propagates
through automatic-backup updates and post-import verdict verification.

`feature.presets.PresetLibraryViewModel` owns the device-wide colour and modifier libraries for each activity.
MainActivity and GlobalOptionsActivity obtain it through Hilt and use the same library UI callbacks.
It receives the singleton RenkinPackRepository through constructor injection and reads and writes
the library directly. MainViewModel and GlobalOptionsViewModel retain profile and editor operations.
Repository storage gates, recovery-aware Room flows, preset ordering and persisted formats remain
the same. Success toasts come from the library owner after the write. Write failures are logged
and reported with a failure toast; cancellation still propagates. Profile changes do not own or
reset this device-wide library. AppModule owns the repository binding and the factories that
compose domain collaborators.

Phase 7B1 moves installed-pack discovery/readiness out of Compose state. `IconPackRepository`
owns a read-only `StateFlow<IconPackState>` containing the pack list and loaded flag. Loading
publishes not-ready, then discovered packs, and ready only after appfilter/calendar indexes are
constructed. Repository/provider synchronous getters are documented as action-time snapshots;
they do not trigger recomposition. MainViewModel exposes only the flow, and action handlers read
`iconPackState.value` explicitly. There is no separately writable UI copy. `ApplicationProvider`,
MainViewModel and the Global options ViewModel expose the repository flow directly. MainActivity,
Global options, the watch
screen and watch Apply collect it with `collectAsStateWithLifecycle`; resumed screens receive
the latest state even if loading finished while they were stopped. Pack lists passed to stateless
editor/source widgets come from those collected snapshots. Tap-time readiness and foreground
installed-pack checks still read the current domain value.

The lifecycle runtime Compose dependency uses the existing lifecycle version. Pack exclusion,
isolated parallel appfilter parsing, cancellation, cache clearing and refresh/profile ownership
are unchanged. The live application list and provider/profile scalar state keep their existing
behavior until subsequent 7B checkpoints. No persistence format changes.
After device confirmation, eight new regression cases cover the public catalog load, empty/own
packs, discovery before readiness with completed indexes, late collection/reload, discovery
failure/retry, isolated malformed appfilters, parser cancellation/retry and lifecycle stop/resume.
`IconPackLifecycleComposeTest` verifies a stopped screen retains its displayed snapshot and
receives the latest completed repository state on resume. The public load method and controlled
discovery/parser tests use the same internal loading pipeline.

Phase 7B2 moves the remaining UI Flow/StateFlow reads to `collectAsStateWithLifecycle`,
including the remembered preference helpers, profile lists, preset libraries, watch rules and
scheduling, backup progress/recovery availability and startup recovery state. Collection uses
the host's lifecycle and the default STARTED threshold: it stops below STARTED and restarts
when the screen becomes active. Existing initial values and remembered flow identities stay
the same. A stopped composition retains its displayed value; after restarting, StateFlow
delivers its current value and cold flows resume their source subscriptions. Lifecycle collection
does not make an asynchronous source read synchronous or remove its existing loading defaults.

ViewModel/repository sharing policies remain with their owners. In particular, the eager options
snapshot still retains preferences across sheet/dialog reopening, and watch flows retain their
existing subscription timeout. Build/install, backup, preview and worker jobs keep their existing
scopes, cancellation and session checks; stopping UI collection does not cancel those operations.
One-shot toast/event collectors and operation-time preference snapshots retain their contracts.
Code holding the storage gate continues reading the owner directly rather than awaiting UI flows.
Room remains v21, portable backup format 4 and recovery preferences format 3.
`PreferencesLifecycleComposeTest` exercises both production preference helpers: initial defaults
before STARTED, zero source subscriptions while stopped, retained displayed values, latest values
and absent-key defaults after restarting. It also restarts UI during failed recovery, verifying
that recovery status updates while preference data stays paused until recovery completes.

Phase 7B3 moves the active profile's published lock-key set out of Compose state.
`IconLockManager` owns `lockedIconKeysState`, a read-only StateFlow of snapshot sets. `lock`
and `release` still stage held-row changes; existing batch boundaries call `publish`. Discard,
replacement and clear still publish immediately. The mutable held/orphan maps, provenance and
verdict policy remain private and keep their existing storage/profile-gate serialization.
Operation-time policy reads continue using the held rows, without waiting for UI emissions.

Provider and ViewModels expose the same flow without a second writable copy. Main filters,
hero statistics and Global options categories collect it with lifecycle awareness. The app list
collects once and passes each row its lock flag; rows do not subscribe individually. Derived
lists/categories include the collected key set in their remembered inputs, so a lock-only update
recomputes them. Manager/provider synchronous getters are action-only snapshots. Preserved
rows, missing-pack verification/unlocking, refresh exclusions, exact Apply and profile/session
ownership retain their behavior. No persistence formats change. Device confirmation precedes
tests, compilation and commit under CLAUDE.md.

`IconLockManagerTest` covers staged publication, retained snapshot stability, selective replacement,
orphan preservation, profile clear and late collection after background publication.
`IconLockLifecycleComposeTest` exercises lock-only changes against the production filter, hero
statistics and Global options categorization, including reset and lifecycle stop/resume. These
checks verify settled values, not same-frame atomicity with the still separate application list.

During this checkpoint the application list remains Compose state while lock keys reach UI
through a lifecycle collector. Their displayed snapshots can temporarily disagree during reset,
unlocking or profile loading; collection does not guarantee delivery in the same frame. The old
mutation paths did not explicitly group these writes in a single mutable Compose snapshot either.
The later application-list migration must publish applications and lock keys in one coherent UI
snapshot; two independent StateFlows alone would still allow mixed revisions. Device checks should
watch reset/filter transitions for flicker and verify that rows, lock counts and filters settle
consistently. Held-row policy and persistence continue reading under the existing gates.

Phase 7B4 replaces the live Compose application list with `ApplicationListState`. The provider
publishes applications, lock keys, app-list readiness and the owning session token together at
the end of each acquired profile operation, while the profile and storage gates are still held.
The completion callback also runs after cancellation/failure so already applied edits and partial
held-row batches remain visible. A failed gate acquisition does not publish. This supersedes the
temporary separate-list/lock delivery described for 7B3; independently combining their flows in
UI would not provide the same guarantee.
`replaceApplications` and both `editApplication` overloads verify an active coroutine-context
owner for this exact profile gate. Dispatcher changes preserve that owner; a marker retained
after an operation returns is invalid. Checking only `Mutex.isLocked` would not distinguish a
mutation from another operation's caller. Completion publication still runs under the mutex;
it also runs if bounded acquisition took the lock but cancellation prevented operation entry.

The private staged list is a persistent list: replacing one application shares unchanged storage
with retained snapshots instead of copying the entire list for each icon. Refresh keeps its
existing commits of at most 20 icons, with one aggregate publication per commit. The dependency
`kotlinx.collections.immutable` is pinned to 0.4.0, whose
[release uses Kotlin 2.1.20](https://github.com/Kotlin/kotlinx.collections.immutable/releases/tag/v0.4.0),
matching this project's compiler. Gradle sync is required after taking this checkpoint.
Synchronous provider/ViewModel list getters are operation-time views of the staged list; UI
collects the published flow with lifecycle awareness. A monotonically increasing revision is
compared first to keep StateFlow equality from scanning every application on each publication.
Unchanged list identity, lock values, readiness and session skip publication.

MainColumn collects once and passes the same snapshot through the application list to its hero
card and row lock flags. Global options categories, watch screens and the wallpaper build preview
also collect the aggregate. Filters and counts invalidate on its revision. Pack changes have one
shared ViewModel computation over application snapshots and the existing baseline inputs; it
rejects baselines from another session. Upstream collection and diff computation run on
`Dispatchers.Default` before `stateIn`; sharing uses `WhileSubscribed(5_000)` so computation stops
five seconds after the last UI subscriber leaves and resumes from current inputs on return.
Its result also carries the application revision and token,
so a delayed diff cannot display against a newer list. Editor and quick-action ownership comes
from the displayed snapshot rather than recapturing the provider's potentially newer session.
Existing exact Apply, render tickets, undo, refresh exclusion, storage epoch and build ownership
checks remain in place. Operations never wait on these UI flows while holding a gate.
App-list readiness is a volatile operation-time Boolean rather than a second Compose state;
UI reads it only from the aggregate snapshot.

The application list intentionally displays its loading indicator while switching/reloading a
profile or committing a full import, and until the collected token matches the live session.
Previously the old list stayed visible. The list's scroll state lives above this branch and the
backup restore progress dialog remains in MainColumn, so it stays visible during import.
Device checks must assess brief loader flashes during rapid profile switches, scroll retention,
progress visibility during import and correct rows/counts once the transition completes.

Profile/startup scalar flags and ViewModel baseline bookkeeping remain Compose state for a later
7B checkpoint. This is not a migration of every provider field. Room remains 21, portable backups
4 and recovery preferences 3. The 7B4 manual checkpoint covers cold loading, sorting/filtering,
Apply/reset/undo, refresh/cancellation, Save/Discard profile switches, Global options and lifecycle
recreation, watch and build preview, plus locked-row reset/unlock when such a fixture is available.
After device confirmation, regression tests cover aggregate publication boundaries, partial
failure, retained immutable snapshots and stale-session rejection in `EditorApplyIntegrityTest`.
`ProfileOperationGateTest` covers completion on success/failure/cancellation and rejects missing,
foreign and expired coroutine owners, including dispatcher changes. `PackChangesFlowTest` uses
the production `observePackChanges` flow to check background collection, the five-second
subscription shutdown/restart and rejection of foreign baselines/delayed diffs.
`ApplicationListLifecycleComposeTest` exercises the production filter, counts and categories
against one aggregate snapshot, including retained UI values while stopped and latest values on
resume. Existing session, exact Apply and filtering suites remain part of checkpoint validation.

The UI layer never constructs repositories by hand; view models receive them through
Hilt. `getCurrentMainActivity()` is still used in a couple of places, but only for genuine
Activity operations (`finish()`, starting services, permission requests).

`ApplicationProvider` is the orchestration boundary, not the composition root. Hilt supplies
its repositories, catalogs, stores, profile/lock managers, generation and pack-build services as
application singletons, so those collaborators can be tested or reused without constructing the
whole provider and all persistence state is shared deliberately.

## Activities

- **MainActivity** — the whole app UI; provides `LocalMainActivity` and `LocalToaster`.
- **GlobalOptionsActivity** — wallpaper-backed Global options grid. It uses a lightweight
  view model over the shared provider and performs guarded provider initialization when Android
  recreates it in a cold process without MainActivity/MainViewModel. Its editor remains tied to
  the original session: activity recreation in the same process preserves ownership, but a cold
  recreation closes the expired editor and reports that unsaved changes must be repeated.
- **WallpaperPreviewActivity** — the pack preview before a build. Its theme sets
  `windowShowWallpaper` + a transparent background so the system draws the real wallpaper
  behind it (the launcher trick — no permission; `WallpaperManager.getDrawable` is locked
  behind MANAGE_EXTERNAL_STORAGE since Android 13). It must be an activity: dialog windows
  never become the wallpaper target. It reads shared state from the `ApplicationProvider`
  singleton directly — deliberately **not** through a new `MainViewModel`, whose init would
  re-run provider initialization and drop unsaved icons. RESULT_OK = "user pressed Build";
  the launching side (BuildPackFab) runs the build. If Android recreates it alone over a killed
  process (`savedInstanceState != null` while `startupComplete == false`), the reviewed session
  no longer exists — `onCreate` closes it with `RESULT_CANCELED` + `EXTRA_SESSION_LOST` *before*
  `setContent`, rather than initializing the provider and silently reviewing the last *saved*
  state instead.
  The launch intent and successful result carry the original `ProfileSessionToken`; the preview
  and build both reject a changed session, including reload/clear and returning to the same
  profile. This guards session ownership, not an immutable revision of every icon edit.

## Profiles

Multiple named icon sets. Each profile owns its icons (`DbApplication.profileId`), its
configured style (`Profile.profileStyle`, with a legacy Room snapshot and an in-memory preference
projection of `ProfilePrefKeys` from `DataPreferences.kt`), its watch rules, and
builds its own pack APK under its own package (see Naming). The default profile (id = 1) is
undeletable; deleting a profile also deletes its watch rules, and a watch notification whose
profile has since been deleted shows an explanation instead of applying to the wrong profile.
Switcher = the top-bar title dropdown.

Phase 7A1 isolates the existing JSON snapshot encoder, replacement rules and inactive-profile
built-source patch in `data/LegacyProfilePreferences.kt`. The registered keys and synchronized
DataStore operations remain in `DataPreferences.kt`; Room stays at v20 and the active profile
still uses DataStore. The [profile style migration design](profile-style-migration.md) describes
the proposed typed model, active-profile bootstrap and portable/recovery compatibility rollout.

Phase 7A2 introduces an immutable `ProfileStyle` with stored source, generation/global modifier,
export, policy and built-baseline groups. Nullable fields preserve absence and inactive values;
colours and font paths remain configured strings, and unknown legacy enum ordinals remain
explicit values. `ProfileStylePreferences` captures those values without renderer normalization.
Snapshot creation projects this model back into the existing legacy JSON. `ProfileStyleSchema`
classifies all registered profile keys and drives legacy projection plus the versioned codec.
Complete/unique classification and per-key value round-trips are required test checks before
shipping; schema initialization does not assert against the registry in production.
The strict version-1 `ProfileStyleCodec` is bounded to 256 KiB in characters and UTF-8 bytes;
it is prepared for the later storage migration and does not write new DB or backup documents.
At that checkpoint Room remains at v20, portable backup format at 3 and DataStore owns the
active settings.

Phase 7A3 appends `Profile.profileStyle` as TEXT NOT NULL with an empty-string default in
Room v21. Migration 20 → 21 only reserves the column; existing rows, new profiles and portable
imports leave it empty. Active settings remain in DataStore and inactive settings in
`prefsSnapshot`, with no bootstrap or independent typed writes before the authority cutover.
Recovery adapters compose audited additions by table: v18/v19/v20 snapshots restored into v21
default the new Profile column to empty, while v21 snapshots preserve it exactly. The exported
v21 Room schema is checked in with migration/recovery coverage for preserved values, defaults,
rejected schema changes, Previous state/redo and pending startup recovery. Portable backups
remain at format 3.

Phase 7A4 makes `ProfileStyleStore` the application-scoped style owner. Hilt supplies the
shared repository and settings DataStore. `ProfileStyleAccess` is installed in
`RenkinApplication.attachBaseContext`, before WorkManager providers; it routes the existing
preference APIs to that owner only for the real settings store, leaving staged/standalone
stores independent. The recovery callback finishes pending rollback and then bootstrap before
READY. Workers, provider snapshots and UI observers use the same storage boundary.

Bootstrap validates every profile before a single Room transaction writes converted documents
and their derived `prefsSnapshot`. A legacy active row uses one live DataStore snapshot; other
legacy rows use their stored snapshots. Non-empty documents are authoritative and validated,
never automatically replaced from the preference cache. Missing/default/disabled configuration remains
unresolved. The active DataStore projection is synchronized afterwards. Conversion, encoding
and repository access run off Main; accepted Room/projection commits finish outside cancellation.

Every production profile preference setter delegates to the owner. Style and legacy projection
commit together; switching selects the target without saving a leaving preference cache.
New profiles start converted. Built-primary updates target the captured profile, including an
inactive one. Metadata updates preserve the current typed/legacy pair even if their incoming
row was captured before a style edit. Application settings remain in DataStore. UI reads its
derived active projection through recovery-aware flows; operation snapshots retain the existing
profile token, storage epoch and immutable preference checks. Exact icon Apply is unchanged.

Full/shared imports, Previous state, rollback and recovery abandonment run bootstrap after
restoring rows/preferences and before releasing storage. Storage replacement invalidates the
bootstrap epoch and pauses observers until the outer operation finishes; failed bootstrap cannot
publish usable data. Observer availability is separate from recovery-screen state: normal imports
keep MainScreen and its restore progress composed. The outer operation resumes observers after
successful commit or rollback; startup/recovery failures keep them paused. A projection failure
after a normal style commit blocks access for recovery to re-project the authoritative row. A
failed application-only setting or profile selection that did not write Room propagates without
requiring recovery. During restore, initialization errors remain within the existing
rollback boundary. Room stays at v21 and portable exports at format 3, using the current legacy
projection for every profile. Format 4 and retirement of compatibility projections are separate
checkpoints. `ProfileStyleStoreTest` exercises the installed bridge with real Room transactions,
all 64 setters, startup/restart, cancellation, projection failure/retry and explicit repair.
`StorageOperationGateTest` covers observer-only pauses and successful/failed rollback boundaries.
`BackupRecoveryTest` covers worker-first recovery, v20 Previous state/bootstrap/restart/redo,
full/shared imports, typed-style rollback and Profile-only retained diagnostics.

The confirmed keep-current recovery action can explicitly repair invalid non-empty style documents
from their atomically maintained legacy snapshots. All replacement copies must pass strict bounded
JSON/type/encoding validation before any repair writes. Original Profile rows are durably retained
in abandoned recovery storage before the single repair transaction. Valid documents stay unchanged;
invalid legacy copies prevent repair rather than clearing the profile. Startup, retry and imported
documents retain strict validation without automatic fallback. Existing recovery confirmation explains
this repair and preservation, including the loss of the Previous state action when its recovery
directory is moved. The diagnostic `profiles.json` contains only the Profile table, preserving raw
columns and values without copying icon payloads; it is not a complete restorable snapshot.
Explicit abandoned-copy deletion remains the cleanup path.

Phase 7A5 enables portable backup format 4 for full and shared-profile exports. Every profile
carries the exact versioned style document as a required JSON string in `profileStyle`; the
legacy `prefsSnapshot` remains a separate derived field. `ProfileStyleBackup` validates the
bounded typed document and its strictly parsed legacy copy, rejecting semantic mismatches.
Full backups also validate the active preference projection against the active typed style.
The shared strict legacy reader lives in `ProfileStylePreferences`, reused by explicit recovery
repair. Its size budget includes flat key-name overhead; the typed document still has its own
256 KiB character/UTF-8 limits. Absent/disabled values, theme colours and unknown ordinals
remain configured values, without renderer normalization.

The manifest version is passed through stream decoding and validation before preparing an import.
Codec, stream and validator APIs require an explicit format; none supplies a default.
Formats 1–3 keep legacy decoding and shared bootstrap; they cannot smuggle a `profileStyle` field
past the format check. Format 4 requires a valid document and matching copy; a missing, invalid or
unsupported document never falls back. Known profile preference tags/primitive types and the
active identity are checked before JSONObject coercion. Full import retains exact style documents;
shared import assigns a fresh id without changing the document or the active style. Older builds
supporting only formats 1–3 reject format 4 entirely. Manual and automatic exports use the same
format-4 path. Room remains v21; the typed version remains 1 and no schema migration is needed.

Local recovery database snapshots and `prefs.json` are independent of the portable manifest:
the preference-only JSON explicitly uses `RECOVERY_PREFS_FORMAT` (legacy format 3), while Room
snapshots retain their existing audited schema handling. Previous state, pending rollback and repair
are unchanged.
Legacy/UI projections remain until their separate retirement checkpoint. Legacy test fixtures pass
their format explicitly; current export fixtures bootstrap their typed documents before exporting.
`ProfileStyleBackupTest` covers format 4, exact text and configured-value preservation, strict raw
types, invalid/missing documents, projection mismatches, explicit legacy formats and near-limit
multibyte compatibility copies. `ProfileStyleArchiveTest` covers full/shared archives, fresh ids,
restart, legacy bootstrap, rejection before storage changes, profile-id diagnostics, background
export through the worker's shared backend and the unchanged local recovery format. Existing
snapshot, rollback, previous-state and modifier-input suites retain their coverage. Worker scheduling
and SAF document management are unchanged; the background test exercises the export backend.
Invalid typed styles stop export and log the owning profile id for diagnosis.

Phase 7A6 routes settings snapshots and UI/background preference flows through Room-backed reads.
`ProfileStyleStore.snapshot` resolves the active id and current Profile row under the storage gate,
removes every profile key from the DataStore snapshot and overlays the typed document's present
values. Missing values remain missing. Application settings and active selection still come from
DataStore. Action snapshots, render-staleness checks, builds and backups therefore read the Room
owner even when the persisted compatibility projection is obsolete. The bounded single-document
cache stores only a pure decoded preference projection; every read still queries the owning row.

`observePreferences` selects the shared owner when collection starts. The application singleton
owns one lazy sharing scope and one `shareIn` upstream with `WhileSubscribed`, immediate stop and
replay expiration, and one replayed snapshot. Room/DataStore invalidations and slow-consumer output
are conflated. Subscribers share the gated Room read instead of each queuing their own snapshot.
Unchanged results are suppressed. Replayed snapshots carry their storage epoch; each subscriber
rejects an old epoch even if resubscription races the replay reset after an import or rollback.
A Room style update can wake readers without a DataStore write; intermediate Room/projection commits
cannot be read across the existing storage boundary. Startup/import/recovery pause both the shared
upstream and subscribers. The test reset closes the singleton's sharing scope. Standalone/staged
DataStores retain their direct flow behavior. Room remains 21, portable backups 4 and local recovery
preferences 3.

Observation failures never emit empty/default settings. Snapshot failures invalidate initialization
and report recovery while still holding the gate, preserving their diagnostic cause. Source failures
also enter recovery; cancellation propagates. The gated upstream stays restartable after retry or
confirmed repair instead of letting an exception escape into UI collectors or killing sharing.
Operations holding the storage gate must use `getPreferencesAfterPendingWrites`, which reads in
the caller's owner context; they must not wait for the separately scoped shared flow. The current
first/single-call audit found only raw DataStore reads inside the owner and standalone adapter.
`MainViewModel.loadWhatsNewState` waits for READY before acquiring the gate. OptionsViewModel's
`preferenceSnapshot` is consumed by UI collectors, with no gate-owned first-emission waits.

This checkpoint retains the dual-written `prefsSnapshot` and persisted DataStore compatibility
projection. Removing their writes and cleaning up their storage is a later checkpoint, after these
Room-backed readers are confirmed. The confirmed 7A6 regression tests cover obsolete DataStore
values and absent Room values, snapshots inside an existing gate owner, Room-only invalidation,
profile switching, application settings, 17 subscribers sharing one upstream and snapshot read,
slow consumers during writes, subscriber stop/restart, snapshot/source failure and retry in the
same collector, and fresh snapshots without intermediate values after storage replacement.

Phase 7A7 retires the persisted active-profile DataStore projection. After validating and
converting all Room styles, bootstrap removes every `ProfilePrefKeys` entry from the settings
DataStore and retains application settings, unknown application keys and `ActiveProfileIdKey`.
An empty legacy active row still captures its incoming DataStore values before this cleanup;
an existing typed document always wins. A failed validation leaves the incoming preferences
available for recovery. The epoch/bootstrap barrier applies equally to startup and restored data.

Normal edits continue capturing an immutable combined preference view, but split persistence:
the typed document and derived `prefsSnapshot` commit together in Room, and only application
values plus active selection may persist to DataStore. An unchanged application snapshot skips
the DataStore update entirely. Style-only and built-primary commits therefore notify readers
through the shared Room flow. Both profile-selection paths preserve the selected document's exact
text and avoid rewriting a consistent Room pair. A combined style/application operation that
commits Room and then fails writing DataStore still requires recovery; an application-only or
selection failure without a Room write propagates without blocking storage. Accepted commits
remain non-cancellable under the gate.
The application-preference read, equality check and `updateData` share the storage gate with all
other writers, including restore. The update transform deliberately uses the captured target;
it must not be used outside that ownership boundary.

Portable format-4 exports and local format-3 recovery snapshots deliberately retain their derived
active-profile preference values: both read `getPreferencesAfterPendingWrites`, not raw DataStore.
Restore may temporarily write those legacy-compatible values under the paused storage gate, then
forced bootstrap validates/converts the restored rows and removes the profile keys again before
observers resume. Standalone/staged DataStores retain their existing full preference behavior.
Room stays at 21, portable backups at 4 and recovery preferences at 3. The Room legacy column and
its dual writes remain until a separate schema-retirement checkpoint. The confirmed 7A7 tests
verify cleanup of all 64 profile keys, restart idempotence and preserved application/unknown keys,
every owner setter and staged Global Save, style-only and built-primary updates without DataStore
writes, exact document preservation on both selection paths, concurrent application/style edits,
combined-write failure/retry and cancellation after Room commit. Archive/recovery tests additionally
assert raw DataStore stays application-only after full/shared/legacy imports and v20 Previous
state bootstrap, while exported format-4 data and format-3 recovery retain derived profile values.

Phase 7B5 moves `IconUndoTracker` off Compose state. Its StateFlow is private; capture
copies the supplied row list, and discard/clear replace the stored step. The provider reads
the operation-time step under its profile gate and includes the same object in
`ApplicationListState.undoStep`. Publication also checks step identity, so clearing only Undo
still publishes even when applications, locks and the session are unchanged. No new artwork
copy or independently writable ViewModel Undo state is introduced.
An Undo-only change also advances the aggregate revision, so revision-keyed filters, counts and
the subscribed pack diff recompute even if the application list is unchanged. This is an accepted
cost for infrequent operations such as clearing Undo after a build. Separate content revisions
would also require revisiting diff freshness checks; introduce them only if measurement warrants it.

MainViewModel exposes a distinct Undo projection from the completed application aggregate.
The snackbar combines this with the existing conflated prompt channel, replacing the previous
`snapshotFlow` bridge. Provider UI never collects the tracker's intermediate step directly:
an edit may discard a row before the same operation captures its replacement offer. The
operation id, session validation, partial-row invalidation, persisted restoration and build's
step identity checks retain their existing meaning. The prompt/event collector retains its
existing composition lifetime; this checkpoint does not change event delivery on backgrounding.

The 7B5 device feedback also exposed silent waits in quick actions and save-before-switch.
MainViewModel owns `ForegroundProfileActions`, which manages `profileActionProgress` and the
optional session-mutex reservation. Progress is claimed synchronously before launching Generate,
Reset, Undo, manual profile switch or create-and-switch. It covers mutex/gate acquisition and
the complete action, including saving, profile loading and baseline synchronization. Completion
clears it on success, failure, cancellation and cancellation before coroutine entry. MainColumn
collects it with lifecycle awareness and shows a blocking standard progress dialog with the
action, optional application name and shared `WavyLoadingBar`. Repeated foreground actions
cannot queue through it and report a busy message. Single-icon Generate deliberately allows
one foreground request at a time through this UI; its rendering still uses `serialize = false`
and the provider's render tickets. This does not serialize the rendering service globally.
This is presentation state; it does not replace provider session ownership or gate checks.

For serialized foreground actions, the ViewModel session mutex is acquired with `tryLock` before
launch; contention returns busy immediately instead of hiding an unbounded queue behind a modal.
Reset and Undo additionally use the existing bounded storage/profile gate path (300 ms per gate
acquisition). The timeout covers acquisition only, not the operation body or a committed write.
Their progress clears after contention/failure and leaves the pending Undo and icon state intact
when no operation began. Single-icon Generate and manual profile switching/creation still use
their normal provider paths; profile switching also waits for its ViewModel session mutex.
An actual Save/load/baseline refresh can therefore remain modal for its full duration. No Cancel
button is offered during that work; cancellation semantics around profile persistence are not
changed by this checkpoint. Device checks must cover contention as well as large-profile Save.

Refresh Undo now captures every committed icon-state change, including filling a previously
empty slot, for both single and bulk generation. This deliberately supersedes the replacement-only
Undo decision in `832934d`, following the request to undo the first Refresh too. It is a product
behavior change, not a necessary consequence of moving state to StateFlow. Undo restores the
captured empty slot; mixed batches restore both newly generated and overwritten targets.
Skipped/withheld/unchanged results do not create
an offer. Bulk capture still checks applied-row identity at completion so later edits are never
rolled back. The extra empty-source rows retained for Undo contain no old generated artwork.
Reset changes only live state, so its Undo is marked `persisted = false`: restoring it must not
serialize/save the entire profile or accidentally persist unrelated unsaved changes. Operations
that actually reached storage still use the persisted restoration path. Reset emits its Undo
prompt before a separate missing-pack-summary refresh rather than waiting for that follow-up.
Single-icon Generate/Reset offers validate against the completed aggregate's session and Undo
operation id without acquiring another gate just to send a UI event. Later aggregate changes
invalidate the displayed offer, and executing Undo still validates its session/operation under
the provider gate.
Profile baseline loading derives saved keys from the saved-hash map, avoiding an additional
full-row Room read; the session checks around baseline publication remain unchanged.

The 7B5 device checkpoint is confirmed. Regression tests cover captured-row independence,
Undo-only publication, session invalidation, first-generation and mixed-batch Undo, preserved
dirty state without accidental persistence, and bounded Reset/Undo contention with retry.
Foreground-action tests cover duplicate requests, externally held mutexes, cancellation before
entry, failed jobs, failed launch and independent renderer execution. Existing profile-gate and
application-list lifecycle tests run alongside these cases. Room stays 21, portable backups 4
and recovery preferences 3.

Phase 7B6 moves the provider's dirty-key set and long-operation registry out of Compose state.
They remain operation-time values guarded by the profile gate; replacements are volatile for
action-time readers. `ApplicationListState` now includes the replacement-only `unsavedIconKeys` set and
`longOperationRunning`, evaluated for the snapshot's exact session token. Gate completion
publishes these together with applications, locks and Undo, including dirty-only or busy-only
changes. The provider never mutates an assigned set, so publication shares it without another
copy and retained snapshots cannot acquire later dirty keys. No independent UI flow exposes
an intermediate registry state. These two fields establish the coherent domain contract for
future dirty indicators and operation availability in UI; this checkpoint has no UI consumer
for them yet and deliberately retains the existing action-time guards and feedback.

Long-operation registration and non-cancellable cleanup keep their existing gate boundaries.
Cleanup removes only its own operation id, so a render finishing after a profile switch cannot
clear the new session's busy flag. Busy status means a provider render/build reservation for
the owning session; it is separate from the foreground progress dialog introduced in 7B5.
Dirty keys still describe edits to the live profile, while MainViewModel's existing saved/built
baselines also participate in deciding whether to prompt Save/Discard. Those action-time
checks keep their current provider reads; they do not await a UI flow while holding a gate.
Build and Global options guards likewise read current operation status when tapped.

Dirty/busy-only publications advance the aggregate revision and can recompute revision-keyed
filters and the subscribed pack diff, as with Undo-only changes. This checkpoint does not add
a separate content revision or change refresh batches, render tickets, persistence, Undo or
Save/Discard semantics. Startup readiness, profile-switch transition state and profile identity
remain for the next independently confirmed 7B checkpoint; in particular the immediate loader
during a profile transition still uses its existing signal.

The 7B6 device checkpoint is confirmed. Regression checks cover dirty-only publication on Save,
retained dirty sets, busy-only publication before rendering, cancellation cleanup and session
ownership when switching during a render. The targeted suites and all 924 unit tests across
148 suites pass, with no failures or skips; `assembleDebug` also passes. Room stays 21,
portable backups 4 and recovery preferences 3.

The independent `fix/profile-switch-latency` checkpoint addresses device feedback about profile
switching and ordinary startup; it is separate from the confirmed 7B6 commit.
Profile Save encodes independent rows on a dispatcher limited to two concurrent tasks, preserving
row order and the existing single Room replacement transaction. An identical source/export
instance is serialized once. Input files are still prepared and synced before their referencing
rows commit, and accepted only after that commit. No cross-session artwork cache is introduced.
When a stored source equals its rendered payload and flags, bitmap/vector decoding can use a
detached copy instead of a second PNG/XML decode; mutable pixels/geometry remain independent.
Other drawable types and failed copies retain the original decoding fallback. Applying/resetting
loaded rows uses their known indices instead of repeatedly searching the whole application list.

Baseline loading captures one projection containing component identity, rendered artwork and
calendar fields, excluding base artwork and modifier documents, together with built fingerprints
under storage/profile ownership. The immutable artwork strings are hashed on Default after
releasing those gates. Publication rechecks session, saved-row revision, both storage epochs and
the current built map under the gates. One load makes at most three capture/hash/publication
attempts and stops earlier on cancellation or supersession. If all attempts lose a write race,
the diff stays unready until the next session or explicit Save/build baseline synchronization;
no timer immediately starts another read loop. Debug diagnostics record this deferral. Live Apply
does not persist artwork or change the saved revision, so repeated Apply alone does not cause
these retries. Legacy built adoption happens only after validation, reuses the computed saved
fingerprints and retains the hasUnbuiltChanges condition. SHA-1 bytes and lowercase hexadecimal
output are unchanged; hexadecimal encoding avoids a formatter allocation per digest byte.
There is no Room schema or portable/recovery format change.

Switch progress covers Save, loading target icons and the in-memory missing-pack summary.
Artwork fingerprint reads run afterward in a session-owned baseline job, shared with the existing
session observer. This background job takes the ViewModel mutex only to claim the load,
publish its result and refresh the small missing-pack summary, allowing Apply, Reset and Undo
during hashing. Another explicit switch cancels this optional job. Publication still validates
the session token, and the diff remains unavailable
until its matching baselines arrive. The completed icon load supplies its saved keys without
another database query, so Save/Discard and preview classification do not borrow the old profile's
keys while hashes load. `updatedKeys` already filters by its owning session. The switch also
atomically clears expired backing markers; it preserves markers already owned by the target
session. Deferred publication uses `clearUpdated = false`, preserving new edits. On app reload,
refreshApps explicitly rebinds the retained edits to the new session. It carries baselines forward
only if they were ready for the pre-reload session; a reload during hashing awaits a fresh read
instead of declaring old hashes ready. Startup has no retained
edit markers and full import/Previous state reset their baselines explicitly. A load owner also
rejects late publication from an older read in the same session after a Save/build synchronization.

MainActivity shows the same inert `MainStartupScreen` during CHECKING and ordinary catalog/icon
loading: home title/actions/search shell with Material's morphing-shape `LoadingIndicator`.
Startup and the interactive screen use the same stateless title-bar content and shared search
field; the startup branch does
not instantiate toolbar ViewModels. No profile/edit/build actions are active in that shell.
RECOVERING, FAILED and CHECKING during an explicit recovery Retry show the dedicated recovery UI.
Retry therefore retains its recovery progress instead of flashing the home skeleton. The storage
barrier, paused data observations, pending rollback and strict style bootstrap remain intact;
changing startup presentation does not bypass them. Crash-report dialogs wait until the shell
finishes. Only the optional diff follows foreground switching; durable Save and target icon
loading still finish before dismissing progress.

The startup animation retains the established morphing shapes, as requested after the 7B7
device checkpoint. This visual restoration leaves startup/recovery routing and readiness
ownership unchanged. Its on-device appearance is confirmed; the startup-shell test, all 936
unit tests across 149 suites and `assembleDebug` pass. The full suite uses the local test-JVM
C2 workaround described below; project runtime settings are unchanged.

Debug `ProfilePerformance` logs report ViewModel/gate waits, input preparation, encoding, Room
write, target-profile load, baseline loading, missing-pack summary and startup recovery/bootstrap.
These diagnostics contain only phase names, profile ids, counts and durations. Device timings
can establish any remaining bottleneck; no numerical speedup is claimed without measurements.
Extended manual checks cover cold startup, Save/Discard switching between large profiles,
rapid consecutive switches, edits before diff readiness, retained per-icon modifier sources,
reload with unsaved edits, Save/build during baseline hashing, unchanged build diffs,
missing-pack locks, toolbar actions and recovery Retry. The device checkpoint is confirmed.
Regression tests cover delayed baseline publication across live edits, Save, Build, profile
switching and storage replacement, deferred legacy built adoption, independent bitmap copies
and the inert startup shell. All 933 unit tests across 149 suites pass without failures or skips,
and `assembleDebug` passes. The first full test run hit a native JBR 21 C2 compiler crash while
compiling Robolectric SQLite code; the successful full run disabled C2 only in its test JVM
through a local init script. Project and application runtime settings are unchanged.

The next checkpoint, **7B7**, moves provider startup readiness and profile-transition status
from Compose state into the read-only `runtimeState: StateFlow<ApplicationRuntimeState>`.
The existing assignments still occur at the same points, including transition cleanup in
`finally`. Atomic updates retain the other flag. `startupComplete` and `isProfileSwitching`
remain action-time projections only. MainActivity, the home list/card, Global options and
wallpaper preview collect the flow with lifecycle awareness. Summary and screen-ownership
checks receive the collected runtime value rather than relying on a Compose read of a getter.

Runtime readiness is separate from `ApplicationListState`: it must announce a transition
before icon loading finishes, while icon rows, locks, dirty keys and Undo still publish together
at profile-gate completion. The list continues checking its session against the live owner.
The baseline/session observers combine runtime readiness with the remaining Compose identity
and baseline state, so a startup completion or transition end still triggers their work.
Opening a watch notification awaits `runtimeState.first { it.startupComplete }` instead of
polling every 50 ms; the wait remains cancellable and has no timeout or fallback profile.
Deferred baseline hashing, retry bounds, gate ownership and Save/Discard behavior are unchanged.
ProfileManager's active id and the provider session revision remain Compose state for a separate
**7B8** checkpoint; ViewModel UI/diff bookkeeping remains owned by its ViewModel. There is no
database, backup, dependency or string change. The 7B7 on-device checkpoint is confirmed,
including watch notifications after cold and warm startup. Regression tests cover startup
readiness, transition publication before the completed icon snapshot, and readiness cleanup
after cancellation and reload failure.
The targeted checks and all 936 unit tests across 149 suites pass without failures or skips;
`assembleDebug` passes. The full suite uses the same local test-JVM C2 workaround described
above, without changing project or application runtime settings.

The next checkpoint, **7B8**, adds the non-null `ProfileSessionToken` to
`ApplicationRuntimeState`. Active profile id is a projection of that token; readiness,
transition status, revision and provider-instance identity now share one read-only flow.
Provider action getters read it synchronously, and session validation captures one runtime
value for its readiness/identity checks. Invalidation increments the token revision under the
profile gate and still clears render tickets and Undo. Leaving and returning to a profile
cannot revive a stale token; recreating a provider starts a new instance identity.

`ProfileManager.activeProfileId` is now a volatile operation-time selection for persistence,
not UI state. Only provider initialization, profile selection and reload call its id-changing
methods. Those calls publish the selected id back into the runtime token in `finally`, before
loading the selected icons; failed or cancelled persistence therefore cannot silently leave
the provider on a different id from its manager. These operations remain under the existing
storage/profile gates. Reload announces its transition before invalidating identity. Icon
rows, locks, dirty keys and Undo still publish as one completed `ApplicationListState` at the
end of the gate, and the home list hides a completed snapshot whose token differs from runtime.

The session observer now maps the runtime flow directly, filtering unready states. The diff
continues observing ViewModel-owned baseline fields with `snapshotFlow`, then validates both
their owner and the edited-key owner against the same runtime token. Hero, profile title,
editor, quick actions, change sheet, wallpaper preview and notification source capture use
lifecycle-aware runtime reads. Outline drafts key themselves by the collected session while
respecting Global options' fixed saved-state owner. Watch rules/badges map the active id with
`distinctUntilChanged`, retaining their Room subscription across same-profile revision and
readiness changes. No I/O is added to composition, and the startup morphing-shape animation,
deferred baseline reads, format versions and Save/Discard rules are unchanged.

7B8 has passed its on-device checkpoint. Checks cover cold-start profile restoration, Save/Discard and rapid switching,
stale editor/preview owners, outline drafts, watch rules/notification routing, reload and backup
session replacement. Additional checks cover deleting the active profile (default title/icons/
watch rules and completed loading), returning to Watch after a notification switches profiles,
and preserving its rules/badge without resubscription when only the revision changes. To check
Global options expiry on app-list reload, leave it open, use a same-profile watch notification
to open another MainActivity, dismiss the suggestion, reload the app list from Settings and
return to Global options. It must close with an expired-session notice and preserve storage.
The runtime fixture supplies an explicit initial session token, with regression coverage for
monotonic revisions, process ownership, unready intermediate switch states and deletion of the
active profile. The deletion fixture resets/closes its Watch singleton because Robolectric
resets SQLite connections independently of production static instances between tests.
Targeted checks and all 938 unit tests across 149 suites pass without failures or skips;
`assembleDebug` passes. The full suite uses the same local test-JVM C2 workaround described
above; project/application JVM settings remain unchanged. At that checkpoint the remaining
primary work was **7C** (durable drafts) and **7D** (feature packages). Their subsequent
checkpoints and the separate legacy `prefsSnapshot` retirement are documented below.

Rendering regression tests now use test-only Roborazzi 1.41.0 alongside the existing Robolectric
4.14.1. The first reference covers a gradient and shadow in the actual bitmap modifier pipeline.
Ordinary unit tests verify the checked-in PNG; explicit record tasks update it for visual review.
See [rendering-tests.md](rendering-tests.md) for commands, scope and native-host limitations.

## Threading & state

The domain StateFlow migration in **7B**, durable editor drafts in **7C** and feature-package
moves **7D1–7D7** have passed their checkpoints. **7D8** is the closing documentation review;
its assessment and separate follow-ups appear after the checkpoint history below. The following
7C entries describe the rollout at each historical checkpoint, rather than missing live wiring.

The developer chose a Restore/Discard offer for the last unfinished individual editor in 7C.
The first checkpoint, **7C1**, adds only `data/editor/EditorDraftRecord`, `EditorDraftCodec` and
`EditorDraftStore`; no production caller captures or restores drafts yet. The bounded version-1
envelope carries persistent comparison data and artifact checksums, never an old live session
token. The singleton IO store owns `noBackupFilesDir/editor-draft`, atomically publishes a
complete last checkpoint, rejects stale saves/discards and can rotate its persistent storage
generation before replacement. See [editor-draft-recovery.md](editor-draft-recovery.md) for the
limits, lock direction, commit/error semantics and remaining capture/validation/UI contracts.
7C1 passed its device regression checkpoint and 25 targeted codec/store checks. All 963 unit
tests across 151 suites pass without failures or skips, and `assembleDebug` passes. Full tests
use the existing local test-JVM C2 workaround without changing project/application settings.
The store tests exercise first-use IO, stale/concurrent writes, corruption, invalid paths,
orphan cleanup, cancellation during publication and I/O failures before/after rename. Directory
fsync uses the existing Windows-compatible Os shadow with an optional test failure callback;
payload file sync runs normally. Room/portable-backup/recovery-prefs versions remain 21/4/3.

Checkpoint **7C2a** adds `EditorControlsSnapshot`, a bounded version-1 binary
`EditorControlsCodec`, and the Main-thread `ui/EditorControlCapture` adapter. The value snapshot
preserves disabled controls, nested color/target lists, exact finite floats, packed UI colors,
vector/upload control values and ordered brush geometry without retaining live Compose state.
The fixed binary order, named enums, strict UTF-8/count checks and 2 MiB output ceiling avoid
recipe normalization and unbounded parsing. Capture bounds/copies collections before encoding
off Main. No autosave/recovery caller is installed yet. Complete artwork/origin/vector-seed/
replay capture and canonical baseline fingerprints remain **7C2b**, followed by the recovery
offer and storage/lifecycle integration in **7C3**. Controls alone cannot restore a draft.
Those callers must handle capture/encode rejection without replacing the last valid checkpoint,
validate control domains before binding without silent clamping, and resolve font identifiers
only through verified owned artifacts or the allowed catalog.
Device regressions are confirmed; eleven new capture/codec tests, all 974 tests across 152
suites and `assembleDebug` pass. See the draft-recovery document for the format, limits,
semantic validation requirements and integration coverage still required before recovery.

Checkpoint **7C2b1** adds `EditorDraftFingerprints` for canonical typed-style/full
persisted-icon SHA-256 baselines and `EditorDraftResources` for bounded independent copies of
gallery PNGs and catalog/bundled fonts. Hashing detached rows runs on Default; file reads run on
IO, validate canonical ownership/regular-file identity and never acquire the gates. References
are selection attribution, not restore-time file authority. The existing gallery API also
exposes its path without creating a directory. No capture/autosave/recovery caller is installed.
Device regressions are confirmed; 17 new tests, all 991 tests across 154 suites and
`assembleDebug` pass. Android system-font and symlink/no-follow checks remain platform coverage;
the JVM resource tests simulate descriptors/stat changes while reading real files. **7C2b2** adds
detached bitmap/vector/adaptive/inset artwork and exact vector-path/current/seed codecs, with
Main-thread value capture and background codec/native binding boundaries. It preserves vector
root properties, trim/miter/clip, packed colors/reference brushes, raw sRGB ARGB pixels and
adaptive original layers. Unsupported brushes/configs and over-budget trees fail explicitly.
No production caller is installed. Device regressions are confirmed; 19 new native-graphics
codec/capture/binding tests, all 1,010 tests across 155 suites and `assembleDebug` pass with
the existing local test-JVM C2 workaround. Integration timing/heap and process-death recovery
remain coverage for the subsequent checkpoints.
**7C2b3** implements an explicit complete-capture preparation API on the existing editor owner;
its device checkpoint is confirmed and verified. It combines controls/resources, original live input, completed
raw/rendered artwork, exact generation options/masks, replay bookkeeping and current/seed vector
state. Ordered native masks share one bounded artifact. Render/source witnesses reject mixed
or obsolete frames; idle witness acknowledgment does not restart rendering for tab changes.
Preparation rechecks gated
persisted baselines, both storage epochs, the draft checkpoint and live frame identity after
off-Main hashing/encoding/resource work. It does not write a checkpoint or install a production
caller. Twenty-three complete-capture/codec regressions and four provider-baseline regressions
cover inactive-source clearing, idle witness acknowledgment, pending/cancelled/stale renders,
exact options/original artwork, grouped ordered masks, malformed payloads and replaced owners.
All 1,037 tests across 156 suites and `assembleDebug` pass with the existing local test-JVM
C2 workaround. **7C3** will enable recovery and lifecycle persistence after its own checkpoints.
Existing DB vector XML drops some editable properties and cannot substitute for that codec.
Future capture/offer owners must handle oversized uploads, unavailable fonts and unconverted/
invalid styles without crashing or replacing the last valid checkpoint with partial/default
state. Recovery resolves verified owned font bytes even if the old system path has disappeared.
**7C3** remains the explicit recovery offer and lifecycle/storage integration. Room/portable
backup/recovery-prefs versions remain 21/4/3. Contracts and pending coverage are documented in
`editor-draft-recovery.md`.

**7C3a** implements dormant recovery preparation on EditorSessionViewModel, semantic validation
before native binding, owned-byte Typeface/upload binding, exact artwork/mask/current/seed values
and original modifier-stage verification. It compares both durable baselines and repeats current
provider/storage/checkpoint/editor-generation checks around decoding and native work. A different
profile requires an explicit normal switch and fresh preparation. Missing components or changed
durable fingerprints return Obsolete without error logging; a profile switch after inspection
expires the exact captured session token and returns Superseded. Ready is an unattached native
lease; the future offer owner must revalidate before acceptance and detach every preview consumer
before recycling expired preparations.
No live controls/session attachment, recovery offer, autosave or replacement hooks are installed.
The device checkpoint is confirmed. Twenty-nine new tests cover semantic/native/resource binding,
preparation ownership/results, failure/cancellation preservation and inspected-token expiry. All
1,066 tests across 158 suites, compileDebugKotlin and assembleDebug pass with the local test-JVM
C2 workaround. **7C3b** will integrate acceptance, the offer/error UI and lifecycle/storage behavior.

**7C3b1** reconstructs an unpublished session through PreparedEditorRecovery.sessionForAcceptance.
It restores exact controls, original/base/result metadata, active completed frame/Create request,
verified replay bookkeeping and independent current/seed vector inputs without a builder call.
The original editor application is distinct from baselineApplication, the provider's current live
identity used for later baseline checks; replaced identities classify capture as Superseded.
Selected pack raw artwork uses its recorded modifier pipeline for edits and sheet previews, without
reentering the pack source stage. Owned Typeface/upload values are consumed by text generation and
the Upload tab, and owned bytes remain available for recapture until explicit replacement.
Construction is cached on the preparation; unattached disposal invalidates it. The reconstruction
API has no production caller. **7C3b3** integrates first-composition rendering; **7C3c** adds the
offer/error UI and autosave/storage hooks after the binding checkpoints.
The 7C3b1 device checkpoint is confirmed. Nineteen new tests cover reconstruction, frozen-source
rendering, owned resources, Upload composition, expired live identities and a Roborazzi rendering
reference. All 1,085 tests across 161 suites, compileDebugKotlin and assembleDebug pass with the
local test-JVM C2 workaround. An intentional color change fails ordinary screenshot verification;
Roborazzi is absent from debugRuntimeClasspath.

**7C3b2 (confirmed and verified)** adds dormant validated acceptance on the same
EditorSessionViewModel. The preparation records that VM's identity and generation. Acceptance
obtains a checkpoint lease outside the profile/storage gate, then constructs the unpublished
session. Gate acquisition and baseline validation run on Default, including any storage recovery
triggered by acquisition. Only the synchronous publication dispatches to Main with both gates held;
Main callers acquire these gates through suspension, never blocking waits. A short synchronous
store fence rejects any save/discard/invalidation admitted since
the lease; no store mutex or I/O is acquired during publication. Native ownership transfers once,
so offer disposal cannot recycle live artwork. Release/replacement/VM cleanup drop live references
without recycling bitmap values that a closing Compose preview may still draw. A runtime-only
StateFlow exposes the attached session identifier/component/token even when cancellation loses
the returned result after publication. Failed/stale acceptance leaves preparation disposal to the
offer owner after preview detachment. Unavailable storage recovery returns Superseded without
logging a draft error. The preparation's VM owner is a required non-null constructor argument.
No offer/autosave/storage-replacement caller is enabled.
The device checkpoint is confirmed. Twenty-five new tests cover acceptance/attachment ownership,
domain/checkpoint expiry, concurrent writes during Main handoff, storage unavailability, off-Main
recovery, cancellation before/after publication, native disposal and admitted-write lease expiry.
All 1,110 tests across 161 suites, compileDebugKotlin and assembleDebug pass with the local
test-JVM C2 workaround, including ordinary Roborazzi verification.

**7C3b3 (confirmed and verified)** projects recovered generation options from the
captured request plus explicit control changes. OptionsDialog uses that projection for its main
render and modifier-sheet preview inputs, preserving untouched nullable groups, independent high
edge thresholds, source/export values, shape fills and native masks even when current UI defaults
or wallpaper-derived colors differ. Unchanged brush geometry reuses bound masks across mounting;
edited geometry uses the normal rasterizer. Upload zoom/vector edits preserve unrelated options;
a replacement source adopts current source presentation and pack styling while keeping unrelated
modifier values. Explicit modifier restoration drops this recovery projection.
The projected request is memoized by session, UI options, witness, recovery projection and replay,
avoiding repeated projection on unrelated recompositions. Source changes retain the projection's
native masks because untouched shared modifiers and edit reversals still use them.
Tab entry is remembered by the session, so mounting/recreating a dialog does not clear imageEdit;
an actual tab switch retains the existing reset behavior. First initialization of a recovered
Vector tab acknowledges its working inputs without selecting a new vector source; unlike live
initialization, it preserves the captured origin/replay until an actual edit. The recovery
offer/autosave remain disabled. Twenty-five new regressions cover exact request preservation,
modifier changes/reversals, native masks, session tab mounting, production Vector-tab composition
and recovered-stage rendering against the existing Roborazzi reference.
All 1,135 tests across 163 suites, compileDebugKotlin and assembleDebug pass with the existing
test-JVM C2 workaround; ordinary unit tests verify both Roborazzi scenarios.

**7C3c1 (confirmed and verified)** wires a shared `EditorDraftInvalidation`
singleton into committed backup imports/Previous state, rollback, confirmed keep-current repair
and profile deletion. Invalidation runs under the domain storage gate before profile writes;
import invalidation also precedes the callback that replaces the provider's live session.
Profile deletion attempts to rotate the generation to expire queued writers, retaining another
profile's draft record/artifacts on success. An unreadable catalog is logged and replaced with an
empty generation before deletion. Initial import/Previous-state invalidation failure prevents the
domain mutation. Profile-deletion fallback, emergency rollback, startup recovery and keep-current repair instead log draft
I/O failure, attempt to remove the owned cache without following symlink targets and continue.
A process-local pending flag blocks old draft access until an empty generation can be published;
acceptance leases expire even when rotation fails. The invalidation dependency is required in
both production and standalone constructors. The store never acquires the domain gate,
preserving storage/profile gate → store mutex → acceptance fence order.
`inspectCheckpoint` can supply a process-local confirmed-reset lease for unreadable metadata;
`discardUnreadable` compares store identity, admitted-write version and exact metadata fingerprints,
then refuses reset if retry now reads a valid catalog. Thirty new regressions cover store invalidation/reset and
backup integration, including draft failures during rollback/startup recovery/Abandon and strict
failure before import/Previous-state session replacement. All 1,165 tests across 164 suites,
compileDebugKotlin and assembleDebug pass, including both Roborazzi scenarios.

**7C3c2a (verified)** adds the activity-scoped
`EditorDraftRecoveryViewModel` and shared recovery/confirmed-discard dialogs. Its serial action
queue coalesces observations of provider session/storage readiness and editor lifetime;
app-list revisions never trigger catalog rereads or repeated preparation during bulk refresh.
Normal editors supersede pending recovery through the VM's existing generation checks. Retry
preserves unreadable metadata; only confirmed Discard uses its observed reset lease. Another
profile is named without switching; obsolete drafts have a discard option. Valid offers also
allow Not now/Back/outside dismissal, preserving files with memory-only postponement until restart.
Preparation and acceptance run off Main, with authoritative attachment publication on Main; the offer has no
native preview consumers. Superseded results refresh automatically. The host opens an accepted
session id with its captured themed flag. Explicitly closing the restored editor releases it and
clears only its checkpoint; failed cleanup stays visible and can be retried. Unexpected session
expiration/loss only detaches the editor and preserves its draft for another offer. No automatic
capture/save is enabled. Debounced lifecycle persistence and normal-editor cleanup remain 7C3c2b.
After device confirmation, 21 owner/Compose cases were added; all 1,186 tests across 166 suites pass,
including both Roborazzi scenarios. compileDebugKotlin and assembleDebug also pass.

**7C3c2b (confirmed and verified)** connects autosave to the same recovery-owner
queue. Its Main-owned `EditorDraftAutosave` collaborator admits empty checkpoints or explicitly
accepted recovery; existing/postponed/other-profile data requires confirmed Discard before a new
editor can save. Lifetime-driven snapshotFlow observes coherent completed frames, debounces 600 ms
before Main artwork copies, coalesces pending saves and bypasses delay for background flush.
Opening frames are recorded at publication and remain unwritten until changed; accepted recovery
starts with its original frame already saved. Reverting a later saved edit still updates the draft.
Immutable edit content is derived once per frame for autosave comparison; it excludes tab/pack
browsing and untouched vector initialization. Full witnesses retain these fields for coherent capture
and recovery. Navigation alone neither creates nor rewrites drafts. Ordinary Vector seeding now
preserves the active source, like recovery; actual edits and returning to the seed still publish.
MainActivity connects the activity-scoped owners and requests asynchronous onStop flush.
Capture/resource/encoding work uses the existing dispatcher boundaries; baseline, generation,
profile token, original storage epoch and store checkpoint fence stale work. Each publication UUID
supports exact reconciliation after ambiguous fsync failure without claiming a foreign draft.
Private prepared bytes go directly to the read-only writer without another full payload copy.
Explicit Apply/Clear/dismiss fences captures synchronously, then queued cleanup waits for an
in-flight write and removes only the latest owned checkpoint. Unexpected loss/clearing preserves
the draft. Routine autosave is silent; only Blocked/Failed show Retry and confirmed Discard,
identifying the stored application's name (package fallback) from an optional metadata-only inspection.
Unsupported witnesses report failure once and wait for Retry or supported values before resuming.
Queue coalescing flags are set before channel sends: Main.immediate can consume a request inline,
and a post-send assignment must not undo the receiver's reset or disable later saves/observations.
The developer confirmed recovery after the autosave fix; the explicit follow-up device scenario
changed Scale before process death. The earlier combined Scale/Colorize/Outline/brush scenario
exposed the original failure, so it is not recorded as a successful combined regression check.
Twenty-nine new tests cover semantic edit comparison, repeated immediate-dispatch saves, debounce,
background flush, recovery edits, admission/reset and close/publication races. All 1,215 tests across
168 suites pass, including both Roborazzi cases; compileDebugKotlin and assembleDebug pass with
the existing local test-JVM workaround. Debug capture logs include copy time, payload size and heap
observations; peak memory and large-draft device performance still need measurement.

**7D1 (verified)** starts feature packaging with the preset library.
`feature.presets` contains `PresetLibraryViewModel`, the colour/modifier library composables and
their preset-to-editor mapping. MainActivity and GlobalOptionsActivity still supply activity-owned
library flows/callbacks, and editor colour/modifier controls explicitly import the library entry points.
The feature imports shared UI controls, shape tokens, editor adjustment state and preview callbacks;
it introduces no repository facade, duplicated owner or Gradle module. Room entities/repository,
modifier payload codecs and rendering remain in their existing data/creator packages.
Only file locations, package declarations and imports change. Existing libraries, partial-group
application, preview/selection lifetimes and error/toast handling retain their behavior. Review-requested
test package/import alignment moves the preset library tests alongside the feature and updates shared
UI test imports without changing any test scenarios or bodies. The device checkpoint passed.
All 36 targeted tests and all 1,215 tests across 168 suites pass without failures or skips, including
both Roborazzi cases; compileDebugKotlin and assembleDebug pass with the existing local test-JVM
workaround. No new scenarios are needed for this package/import-only move.

**7D2 (verified)** groups the icon-watch presentation in
`feature.watch`: `WatchViewModel`, `WatchScreen`, `WatchRuleEditor` and `WatchApplyModal`.
MainScreen and AppQuickActions import the feature entry points explicitly; Hilt still supplies
the activity-owned ViewModel. The feature imports shared UI controls, icon previews, filtering,
haptics and shape tokens. Rule storage remains in `data.watch`, checks and scheduling remain in
`service`, and profile/session-aware Apply remains with MainViewModel/ApplicationProvider.
Worker class names stay unchanged so existing WorkManager requests retain their targets.
This checkpoint changes only file locations, packages and imports, with no new owner, Gradle
module, persistence format or notification route. Rule editing, per-profile subscriptions,
completed badges, manual/debug checks and suggestion acceptance retain their existing behavior.
Static source comparison verifies unchanged bodies, and the device checkpoint passed.
All 30 targeted Watch tests and all 1,215 tests across 168 suites pass without failures or skips,
including both Roborazzi cases; compileDebugKotlin and assembleDebug pass with the existing
local test-JVM workaround. Existing scenarios cover this package/import-only move.

**7D3 (verified)** groups online icon browsing in `feature.online`:
`OnlineIconBrowserViewModel`, its preview/vector/image import result types and the browser/detail
composables in `OnlineIconBrowser`. VectorEditTab and IndividualOptions import the feature handoffs
explicitly. Hilt ownership, session reset, debounced search and editor import behavior are preserved.
Shared UI controls and shape tokens stay in UI, HTTP/disk cache ownership stays in `data.online`,
and SVG parsing/rasterization stay in `vector`. The package-only commit is `2d4938b8` on
`feature/online-icons-feature-package`; the loading changes are separate on `fix/online-icon-previews`.

**Online preview loading fix (verified)** addresses confirmed device HTTP 429 responses and
scroll-driven request churn. Only visible, stationary items start new network previews; cache-first
lookup remains available while scrolling or waiting for the server, including disk lookup after
parsed/raster memory-cache eviction. Cached lookup distinguishes missing content from an SVG that
cannot be rendered, avoiding duplicate parsing/logging through the network path.
All HTTP starts are spaced by 300 ms. HTTP 429 shares a monotonic deadline across requests and hosts,
using Retry-After capped at five minutes or a 60-second fallback when absent. It does not rotate
hosts. Pending work waits without holding any of the six SVG network permits; cache reads and
retry backoff also bypass those permits. The browser shows seconds until the next attempt in an
isolated composable. Another 429 can start a new pause; the countdown does not promise completion.
Transport/transient server failures can use the documented `api.simplesvg.com` and `api.unisvg.com`
mirrors; the last successful host is preferred. Connections use 3-second connect/5-second read
timeouts. Canonical source URLs/cache keys stay unchanged and input URLs are checked before host
substitution. Cancellation disconnects HTTP work and propagates without mirror retries or spurious
network errors. Loading/Ready/Failed previews and detail Retry remain explicit. Debug OnlineIcons
logs include HTTP timing and distinguish invalid SVG/render failures. The F-Droid description
lists the contacted domains and submitted search/icon names.
The Hilt constructor retains the production HTTP transport, elapsedRealtime clock and IO dispatcher;
an internal constructor lets regression tests control responses and time without contacting Iconify.
Nine loading tests cover shared pauses, pacing, bounded/fallback waits, cancellation, permit release,
cache availability, mirror preference and permanent failures; a parser case covers the five-minute
bound for numeric/date Retry-After values. The device checkpoint passed. All 34 targeted tests and
all 1,225 tests across 169 suites pass without failures/errors/skips, including both Roborazzi
comparisons; compileDebugKotlin and assembleDebug pass with the existing local test-JVM workaround.
Logs are under build/verify-7d3-{compile,targeted,full}.log. No new production dependency, Gradle
module or persistence format is introduced.

**7D4 (verified)** groups the help presentation in `feature.help`:
`IntroShowcase`, `InfoDialog` and `WhatsNewDialog` (including the full history screen).
The guided-tour targets, composition local and placement helper move with the tour. MainScreen
and Settings import the feature entry points explicitly; shared dialog/scroll/text/icon helpers
and shape tokens remain in UI. MainScreen and MainViewModel retain startup visibility, dismissal
and preference ownership. Root `WhatsNew.kt` retains the version constants and display policy.
Only file locations, packages and imports change: tour steps, target revelation, saved step state,
About attribution and release content are preserved. No new ViewModel, Gradle module, dependency
or persistence format is introduced. The existing tour-placement test moves to the matching
feature test package without changing scenarios. Static source comparison verifies unchanged
bodies, and the device checkpoint passed. All 10 targeted tests and all 1,225 tests across 169
suites pass without failures/errors/skips, including both Roborazzi comparisons;
compileDebugKotlin and assembleDebug pass with the existing local test-JVM workaround.
Logs are under build/verify-7d4-{compile,targeted,full}.log. No new scenarios are needed for
this package/import-only move.

**7D5 (verified)** groups the backup presentation in `feature.backup`:
`BackupRecoveryScreen`, its restore-progress dialog and `BackupRestorePreviewDialog`.
MainActivity imports the startup recovery screen, and MainScreen imports the preview/progress
entry points. Shared confirmation/dialog/text helpers and shape tokens remain in UI.
MainViewModel and BackupUiCoordinator retain operation and UI-state ownership; BackupManager,
AutoBackupManager and StorageOperationGate retain persistence, scheduling, recovery and locking.
Settings actions and profile-switcher import routes keep their existing owners and callbacks.
Only file locations, package declarations and imports change. Import preview metadata, progress
stages, preparation cancellation, commit restrictions and Retry/Keep current behavior are unchanged.
No Gradle module, dependency, ViewModel, schema or backup-format change is introduced.
Static source comparison verifies unchanged bodies. Existing backup tests keep their packages and
scenarios because they exercise the unchanged owners/services. The device checkpoint passed for
import preview/progress and cancellation. The startup failure screen is not reached by ordinary
imports; its move is verified by source comparison and compilation. All 106 targeted tests and
all 1,225 tests across 169 suites pass without failures/errors/skips, including both Roborazzi
comparisons; compileDebugKotlin and assembleDebug pass with the existing local test-JVM workaround.
Logs are under build/verify-7d5-{compile,targeted,full}.log. No new scenarios are needed for
this package/import-only move.

**7D6 (verified)** groups the build/install presentation in
`feature.buildinstall`: `BuildPack`, `InstallerPickerScreen` and `PackChangesSheet`.
MainScreen imports the Build action and installer prompt, Settings imports the installer picker,
and HeroPackCard imports the pending-changes sheet. WallpaperPreviewActivity retains its class,
manifest registration, wallpaper theme, session/result handoff and activity-owned state; only
its preview-content import changes. The preview list ordering helper moves with its screen.
The shared summary test retains its package/scenarios and imports that helper explicitly.
Shared icon previews, editor entry point, dialogs, scrolling, haptics and shape tokens remain
in UI. MainViewModel/BuildInstallCoordinator retain build/install ownership and preferences;
APK generation/signing, installer services/catalog, session gates and diff baselines stay in
their existing domain packages. This is a file/package/import-only move: preview ordering,
calendar warnings, pending-change filters, editor handoff, installer selection/Shizuku actions,
build progress and activity-result checks are unchanged. No new owner, Gradle module,
dependency, manifest entry or persistence format is introduced. Static source comparison
verifies unchanged bodies, and the device checkpoint passed. All 38 targeted tests and all
1,225 tests across 169 suites pass without failures/errors/skips, including both Roborazzi
comparisons; compileDebugKotlin and assembleDebug pass with the existing local test-JVM workaround.
Logs are under build/verify-7d6-{compile,targeted,full}.log. No new scenarios are needed for
this package/import-only move.
This package is not an independent module boundary: PackChangesSheet still opens the editor
through `ui.OpenAppOptions`, while UI hosts import `feature.buildinstall`. Before extracting
Gradle modules, replace this editor entry-point dependency with a host-supplied callback.

**7D7 (verified)** groups profile management presentation in
`feature.profiles`: the title/dropdown, profile action menu and create/edit form in
`ProfileSwitcher`. MainScreen imports the title explicitly. MainViewModel and ProfileManager
retain profile mutations, session transitions, Save/Discard behavior and persistence; export
and import still use the existing ViewModel/backup owners. Shared dialogs, portability warnings
and preference/text helpers remain in UI. Only the file location, package and imports change.
Form limits/state, default-profile protections, sharing warnings, launcher contracts and
save-before-switch/create prompts retain their behavior. The existing action-menu Compose test
moves to the matching feature test package without changing scenarios. No new owner, module,
dependency, manifest entry or persistence format is introduced. Static source comparison
verifies unchanged bodies, and the device checkpoint passed. All 136 targeted tests and all
1,225 tests across 169 suites pass without failures/errors/skips, including both Roborazzi
comparisons; compileDebugKotlin and assembleDebug pass with the existing local test-JVM workaround.
Logs are under build/verify-7d7-{compile,targeted,full}.log. No new scenarios are needed for
this package/import-only move.

### 7D8 — closing architecture review (reviewed)

This documentation-only checkpoint reconciles the historical assessment after 7B4 (`f031a40`)
with the source at `8cec2744` (verified 7D7). Its applicable decisions now live here; the separate
historical assessment is retired and remains available in Git history. No production/test code,
dependencies, manifest or persistence formats change. Room/portable-backup/recovery-prefs versions
remain 21/4/3. The 7D7 verification above is the tested baseline, not a new 7D8 test run.
Structural graph hints were checked against imports, declarations and representative callers;
this is not a line-by-line correctness, security or performance audit.

| Historical recommendation | Current assessment and decision |
| --- | --- |
| Finish domain state ownership | Completed in 7B: ApplicationProvider publishes read-only application/runtime StateFlows; related application fields share an aggregate. ProfileManager's active-profile field is an operation-time snapshot, not observable Compose state. Keep local ViewModel/UI Compose state where it has a clear owner. |
| Add durable individual editor drafts | Completed in 7C: bounded capture, validated recovery, explicit offer/acceptance, debounced autosave and storage invalidation are wired into the application. The developer confirmed recovery after the autosave fix, with an explicit Scale follow-up; the earlier failing combined Colorize/Outline/brush scenario is not claimed as a successful combined device check. Resource limits and memory measurement remain separate concerns; see [editor-draft-recovery.md](editor-draft-recovery.md). |
| Group independent presentation responsibilities | Completed in 7D1–7D7: presets, watch, online, help, backup, build/install and profiles have feature packages. Provider and ViewModel orchestrators remain broad where they still own cross-feature policy. Do not add forwarding owners merely to reduce file sizes. |
| Measure artwork and backup memory | Open: Room drawable/baseDrawable payload strings and BackupCodec's JSON tree remain. Draft payload limits do not bound total Java/native peak memory. Measure before choosing a file or streaming migration. |
| Close CI/toolchain verification gaps | Open: CI compiles Kotlin and runs unit tests; it does not run lintDebug, assemble an APK or validate release/R8 behavior. Local debug assembly and Roborazzi checks do not close those gaps. |

#### Package boundaries

These are presentation packages inside one app module, not independently extractable Gradle
modules. All seven feature packages import shared UI while UI/activity hosts import feature
entry points. The reciprocal dependency is permitted by the current module, but a future module
split must separate shared widgets/theme contracts from host-specific behavior first.

| Feature package | Remaining UI dependency relevant to extraction |
| --- | --- |
| `feature.presets` | Shared dialogs/search/theme plus editor AdjustmentState, ModifierPreviews and modifier shape labels; define a shared editor-preview contract before separating modules. |
| `feature.watch` | Shared app/pack previews, sorting/filtering, preferences and haptics; WatchScreen also obtains MainActivity directly. Pass host actions explicitly before extracting a module. |
| `feature.online` | Shared dialogs/search/empty state, toaster, scrolling and theme; import-result types cross into the editor. Keep resource ownership explicit when moving that contract. |
| `feature.help` | Shared fullscreen/dialog/link/text/icon/scroll/theme helpers. |
| `feature.backup` | Shared confirmation/alert/text/theme helpers; recovery and transfer owners stay in the domain layer. |
| `feature.buildinstall` | Shared previews/dialogs/scroll/haptics/theme plus PackChangesSheet → ui.OpenAppOptions. Replace the direct editor call with a host-supplied callback before a module split. |
| `feature.profiles` | Shared dialogs, portability warnings and preference/text helpers; MainViewModel/ProfileManager retain mutations and session transitions. |

A source import scan under `apk`, `data`, `service`, `icon` and `vector` found no imports of
`ui.*` or `feature.*`. This checks one dependency direction; it does not establish platform
independence or complete separation of Compose concerns. In particular, DataPreferences still
contains Compose adapters, as noted below.

#### Large UI files and visibility

At the reviewed baseline, MainScreen has 1,419 lines and IndividualOptions has 1,359. Their size
is a maintenance concern, not evidence that the recent package moves changed behavior.
MainScreen combines home routing, list/header/filter presentation and the OpenAppOptions editor
host. A future split can separate those presentations while keeping state and host actions
hoisted; it must not introduce competing profile/editor/autosave owners. IndividualOptions
combines editor sections/layouts with preview effects, completed-frame witnesses, replay and
source/mask handoffs. Extract sections or rendering coordination only with the existing
EditorSession ownership, effect keys, recovered seed/mask behavior and exact Apply preserved.
No line-count-driven extraction is required to close 7D.

Several default-public types are candidates for `internal`, but narrowing a type requires
narrowing its exposing signatures in the same reviewed change:

- OnlineIconPreview, OnlineIconImport and OnlineImageImport are exposed by public ViewModel
  methods and browser/editor callbacks. Review those entry points together, rather than only
  changing the model declarations.
- ColorPresetStore and ModifierPresetStore are exposed by public composition locals/providers;
  ModifierPresetApplication and ModifierPresetGroup also appear in public mapper functions.
  Narrow the connected interfaces, locals and functions together.
- Existing private/internal helpers (including BuildPreviewApps, IntroTargets and
  OnlineIconCachedPreview) already limit implementation details. Framework/Hilt entry points
  need an accessibility review; public is not automatically a defect.

No visibility change is made here. A follow-up must compile KSP/Hilt-generated callers and run
the affected/full suites plus debug assembly; public types cannot simply become internal while
public functions still expose them.

#### Residual seams and follow-up scope

DataPreferences still mixes preference adaptation with lifecycle-aware Compose getters. Separate
UI adapters only while preserving ProfileStyleAccess bootstrap/repair ownership; replacing its
resolver with raw DataStore reads would bypass that contract. UploadedImageThumbnail still
initiates file loading from a composable effect, although decodeFile already runs on IO with
inSampleSize=2. Moving it to a loader/repository is a separate ownership cleanup, not evidence
that decoding currently runs on Main. WatchViewModel injects its repository/provider but still
constructs WatchChecker for operations; consider an injected factory when changing checker
lifetime or test substitution. These seams remain explicit backlog, not completed work.

Three earlier notes also need an explicit disposition:

- Baseline retry limits are already implemented: MainViewModel.loadChangeBaselines uses
  MAX_BASELINE_READ_ATTEMPTS=3 and leaves the diff unready if publication keeps losing its
  revision. This is resolved, not an unbounded-loop backlog item.
- Refresh recipe validation still reconstructs both preference snapshots using the same current
  Context. Review capturing the effective displayed night mode/resource configuration with the
  refresh plan, then rejecting commits after a change; the captured recipe does not currently
  carry that configuration independently. Do not fold a behavior fix into this documentation.
- Review preview-cache visual keys during performance work: distinguish visual changes from
  bookkeeping-only option changes and mutable drawable identity/revision. PackBrowserPreviews
  already keys displayed night mode, pack version and Material You capability; this is not a
  claim that every preview cache lacks those inputs. Global/app/pack caches need separate checks.

The proposed editor-session-checks document has no tracked history or current file to reconcile;
there is no outstanding decision to retain it. Current editor contracts remain in this guide
and [editor-draft-recovery.md](editor-draft-recovery.md).

Preserve storage → profile lock order, avoid waiting for UI emissions while holding their gate,
and keep rendering/signing/SAF copying outside critical sections where the existing contract
permits. Preserve cancellation propagation, intentional NonCancellable cleanup/rollback, exact
Apply/replay, bounded refresh, stale-result rejection and artifact cleanup. Structural moves do
not justify weakening these contracts.

The documentation review is approved; no further checkpoint is planned inside 7D. Follow-up
order is CI/toolchain/release hardening first, artwork/backup memory and performance measurement
second, then legacy prefsSnapshot retirement. Each may require multiple checkpoints; the
residual UI/visibility seams above require their own scoped change if pursued. For memory work,
record realistic profile/backup sizes, Java/native peaks, capture Main-copy times and refresh/UI
latency before choosing storage changes. For CI work, validate lint, debug assembly and suitable
release/R8 checks without exposing signing secrets, and investigate the local JVM workaround
independently. No dependency replacement or database migration is included in 7D8.

**CI1a (verified)** addresses 17 existing debug lint errors exposed by the first CI
verification run. The isolated fix branch adds adaptive API contracts and SDK-check
annotations, protects notification publication, preserves exact bundled-font reads and
atomic close-on-exec on older APIs, and clarifies the Watch pager branch. After developer
device confirmation, 66 targeted tests and the full 1,240-test suite (170 suites) pass without
failures, errors or skips, including both Roborazzi comparisons. Compilation, lintDebug and
assembleDebug pass; lint retains zero errors, 174 warnings and seven informational findings.
API-boundary and notification-revocation cases are covered by Robolectric; older-device
execution is not claimed. CI configuration/release integration remains separate. See
[ci-lint-findings.md](ci-lint-findings.md) for compatibility sources, verification limits and
integration order.

A separate compatibility cleanup remains from 7A: retire `Profile.prefsSnapshot` and its Room
dual writes. It is not another source of truth. Current keep-current recovery repairs damaged
typed documents from this copy, and supported legacy recovery/bootstrap still consumes it.
Before removing the column, explicitly design its replacement recovery behavior, conversion of
unconverted active/inactive rows, portable legacy adapters and non-additive snapshot migration.
Do not couple that Room version bump to the StateFlow migration. Until its own reviewed/device
checkpoint, keep the current paired writes and the documented format-4 compatibility field.
Before installing that schema-changing checkpoint, export a full backup outside app-owned
storage. Its migration must advance the then-current Room version (currently 21); do not fix
the target to 22 if an intervening phase has already advanced the schema. Older builds cannot
be assumed to open the upgraded database.

- Heavy work lives in `suspend` functions that hop to `Dispatchers.Default` internally
  (`withContext`). View models call them from `viewModelScope` (Main) safely.
- `lifecycleScope.launch` is the Main dispatcher — never touch Room / PackageManager from it
  directly; go through a suspend function with `withContext(Default)` inside.
- Installed-pack readiness uses read-only StateFlow. Applications, lock keys, application
  readiness and session ownership publish in one `ApplicationListState`; UI collection is
  lifecycle-aware. Provider startup/transition readiness and live identity use a separate
  immediate StateFlow. ViewModel UI and baseline bookkeeping remain Compose state owned by
  the ViewModel; domain providers/profile managers no longer require Compose state for identity.
- Active-profile mutations run through the provider's profile-operation gate. Callers capture a
  `ProfileSessionToken` before launching work; the provider checks it after acquiring the gate.
  Tokens include the profile, session revision and provider instance, so leaving and returning to
  the same profile or recreating the process cannot revive a stale edit. Switch, full restore,
  app-list reload and clearing all icons invalidate the session. Refresh and Global Save capture
  their inputs under the gate, render outside it, and reacquire it to commit. Hand-picked icons
  commit the already reviewed editor/watch draft in one critical section, without reapplying
  saved global modifiers. This mutation invalidates older render requests for that target.
  Each target has a render-request ticket and a mutation revision: a newer request, reset or edit
  prevents an older result from replacing it. Commits also compare the captured profile recipe
  with the current effective generation/modifier options. Bulk refresh commits batches of at
  most 20 results, reports completed/total progress, and keeps newer edits. It captures lightweight
  generation targets rather than retaining the old icon payloads; only the rows needed for Undo
  retain their previous artwork. Global Save observes revisions without reserving new requests
  and rejects the entire operation if any captured row or recipe changed. Its staged
  preferences are persisted only after validation, together with the rendered icon list.
- The provider records unsaved icon keys in the same critical section as each edit, so profile
  switching and app-list reload cannot miss a change awaiting ViewModel bookkeeping. Undo
  captures committed changes; subsequent edits remove their rows from that offer. Snackbar
  actions identify the original operation and cannot undo a newer, unrelated change. Undo restores
  the captured dirty state as well as the icon; its snackbar follows the remaining live row count.
- Builds capture profile identity, recipe, icons, calendar selections and lock policy under the
  gate. Calendar drawable loading, APK assembly and signing run outside it. Build jobs serialize
  separately around the shared keystore and use distinct APK directories. Unsigned APKs are
  removed after signing; completed directories are pruned on the next build. Pending installer
  choices and conflict/replacement retries retain their APK reservation; dismissing a choice or
  clearing its ViewModel releases it. Cancelled build handoffs remove their working directory.
  Unmarked orphaned or timed-out artifacts have a 24-hour grace period before pruning. Installation persists
  the captured profile snapshot only if a newer save has not superseded it; otherwise it records
  the built baseline separately and preserves the newer saved rows. A full storage restore
  invalidates pending build persistence entirely. Later live edits remain unsaved.
- MainViewModel commits change baselines and edited keys against that same token. Global options
  saves its owner as primitive saved-state fields and returns it with its activity result; expired
  results cannot affect the current profile. Full imports hold the gate through storage replacement
  and live-session reload. This protects session ownership, not backup crash recovery.
- Refresh, Global Save and APK generation take an exclusive long-operation lease for their
  owning profile session. Recipe writes are rejected immediately while that lease is active;
  unrelated sessions can proceed after a switch. Global options opening/Save is refused during
  an active long operation rather than competing for render tickets. Switching profiles cancels
  refresh with a notice; already committed batches participate in save-before-switch. Watch
  applies remain serialized with notification-driven switches by MainViewModel's mutex.
- Option writes wait at most 300 ms to acquire the provider gate, allowing short bookkeeping
  operations to finish. The timeout covers acquisition only, never a preference write already
  in progress. Busy, loading and expired-session rejections produce a notice. The outline slider
  keeps a session-owned local draft and writes once on release (or ruler dismissal), reverting
  a rejected draft without overwriting a newer gesture. Successful writes still check ownership
  under the gate. App-list reload failures report the restored token from the provider rather
  than making the ViewModel infer its revision. Verdict changes only re-evaluate held-back icons;
  explicit pack synchronization also rediscovers installed packs.
- Global-options modifier previews use ViewModel-scoped, shared jobs and a byte-bounded bitmap
  LRU. Lazy-grid disposal therefore does not discard an in-flight/current preview, while a new
  modifier configuration cancels obsolete work. Cache misses drive the shared `WavyLoadingBar`.
- Individual editing is owned by `EditorSessionViewModel`, keyed by a primitive saved session
  identifier and `ProfileSessionToken`. The owner retains at most one active editor; opening
  another replaces any orphan left by a configuration change. `ui/EditorSession.kt` retains the
  captured application, source selection, active editor tab, draft artwork, vector paths,
  upload path/zoom/adaptive choice, attribution, calendar selection, adjustment controls and
  transient brush strokes together. Only browsing and layout state remains local to the dialog.
  Configuration-driven Activity recreation retains its ViewModel; explicit dismissal, Apply,
  reset and ViewModel clearing release the draft. Composition disposal alone does not discard
  it, so reattaching a lazy item or rebuilding the layout can retain the same live owner.
  Saved state includes a primitive attachment marker as well as the session identifier and
  profile token. Reattaching an already
  opened editor requires its live draft to exist: an Activity destroyed without configuration
  retention cannot silently create a default replacement. A missing draft closes with a notice;
  a changed profile/provider token closes with the existing session-expired notice before
  displaying controls. Activity-only destruction and process death have no draft persistence in
  this phase. Keeping the draft across composition disposal does not extend the Activity's
  ViewModel lifetime after non-configuration destruction.
  Source defaults are captured once, after the configured font preference has loaded.
  `ui/IconDraftState.kt` renders only the active source and remembers completed Create inputs,
  so reattaching an unchanged editor does not reinterpret the initial saved icon as a new edit.
  Composition-scoped render effects still cancel on disposal and retry unfinished work on
  reattachment; this is live-state ownership, not a persistent source/recipe database migration.
  Upload selection remains owned above the tab, with decoded artwork matched to its selected
  path before publication. An online raster import opens Upload and retains its attribution.

## Persistence

- **DataStore Preferences** (`data/DataPreferences.kt`) — persists application settings and active
  profile selection; the shared owner overlays Room profile values for readers. Typed accessors:
  Composable `DataStore.getXValue()` for reading in composition; `Preferences.getXValue()`
  for reading a captured snapshot off the main thread.
- **Room — `RenkinPackDatabase`** (file `"renkinPack"`, v21) — profiles + rendered and base icons
  of the last built/saved pack per profile. `isCustomIcon` marks hand-picked vs refresh-generated
  rows; `isLegacyIcon` records pre-classification uncertainty without guessing the origin. In the
  Global options UI, only unsaved refresh output is Generated; saved/built non-custom rows are
  Existing and have their own apply toggle. Individual Apply stores exactly the reviewed draft
  as both base and current render. The Custom target toggle controls an explicit Global Options
  Apply; it does not automatically restyle later editor or watch picks. Custom reference rows
  also materialize without the global layer, including after their source pack becomes usable.
  Saved rendered payloads retain any previously confirmed Global Apply. Generated icons continue
  using the saved Generated setting on refresh; the Custom setting selects only current targets
  of Global Apply. Global
  modifiers are a derived render layer, never destructively baked into the stored base. The
  rendered payload remains in exports for backward compatibility. `sourceUrl` is the attribution
  reference for icons picked from an online FOSS library (informational only — never fed into
  the verdict/lock logic). Loaded into the app list.
  `DbApplication.modifierRecipe` (v19) is optional metadata describing the last confirmed local
  modifier stage. Existing rows migrate with an empty string and keep both artwork payloads.
  `baseDrawable` already contains local edits; this recipe must never be applied onto it or
  used to seed live editor controls that would render those edits again. It is not a source
  snapshot, a complete edit history, or persisted recovery of an unsaved editor draft.
  Non-destructive re-editing still needs a separate original source and source/export settings.
  `StoredIconModifierRecipe` captures the resolved shape fill and retains only stages that
  round-trip exactly through the bounded codec. Brush masks/operations cannot be represented;
  those edits retain their baked image with no recipe. Invalid/unsupported imported metadata
  is discarded independently of the artwork. Backup format 2 and shared-profile JSON carry the
  optional field; older files omit it and older readers can still use the baked drawable.
  `IconDraftState` publishes metadata with its winning completed render, never from pending
  control values. Initial Apply without an edit retains the existing recipe. Replacing or
  clearing a base drops its old recipe unless new matching metadata is supplied; global render,
  calendar changes, save/build locking and undo retain it. Metadata does not enter the shipped
  icon fingerprint because it changes no exported pixels.
  Phase 5C1 appends `DbApplication.modifierInput` (v20, empty for existing rows). It retains
  the input to a known, completed local modifier stage, its complete source/export settings,
  a bounded modifier recipe, its origin (existing icon/upload/vector) and an explicit flag for
  older local edits baked into that input. The input boundary is after upload cropping/zoom or
  vector hand edits, before the shared local modifiers; it does not reconstruct those tools'
  source-file history or missing gallery files.
  Capture covers upload/vector modifier calls and modifications of an existing icon
  without a new pack pick. The latter baseline is conservatively marked as containing prior
  baked edits. Phase 5C2a also captures fresh Create sources through `IconSourceStage`: pack
  picks (including calendar artwork selected by the editor), application-icon variants and text.
  Source creation returns the actual immutable input and the kind of local render stage; the
  preview renders that stage once and publishes its result/input pair only when it wins the
  draft's generation check. It does not reconstruct input from the completed image or generate
  a second neutral preview. Source-specific text colour, app-icon monochrome/Material You
  construction, normalization and export wrapping happen before this boundary. These are
  source choices, not recoverable raw glyphs, font files or application resources.
  Adaptive pack layers use image editing followed by adjustments. Generic modified sources
  retain the actual canonical bitmap passed to bitmap image editing; untouched generic sources
  retain their vector/inset/bitmap presentation. Text retains its existing adjustments-only
  contract; its completed-stage options and v19 recipe record `ImageEdit.NONE`, even if the
  editor supplied an unused image effect. `IconGenerationResult` carries those effective options
  independently of optional input capture, so capture failure cannot publish the ignored effect.
  A generator reuses one lazy stage renderer and its image/adjustment pipelines across primary,
  secondary and fallback output; bulk rendering does not copy effective stage options per icon.
  The pipeline kind preserves these differences instead of guessing that all paths
  use the same vector recolour or rasterization rules. Material You pack restyling performed
  during resource parsing (especially vector stroke width) is not reversible from captured PNG
  layers, so fresh pack picks with active pack restyling retain only their baked result and v19
  recipe for now. Brush geometry remains unsupported. Bulk refresh/fallback and watch keep
  their existing persistence contract; this step does not add retained input to generated rows.
  Initial controls remain neutral until the user requests verified restoration in the Modifier
  tab. Reopening without a new
  render preserves the retained pair; a completed new stage replaces it with that stage's
  actual input, never an older source paired with a newer cumulative result. New artwork,
  reset and unsupported brush geometry discard it; derived global renders, undo, calendar and
  save/build locking preserve it. Optional corrupt metadata cannot discard usable artwork.
  `modifierInput` contains only a bounded v3 document (64 KiB in characters and UTF-8 bytes),
  settings and a SHA-256 artwork hash. Artwork is stored once per hash in
  `filesDir/icon-inputs/<hash>.input`, capped at 32 MiB per file. It retains the existing drawable
  serialization, outside Room rows and portable/recovery JSON. Loading a profile reads only
  small metadata and checks file presence; no input artwork string or second bitmap is loaded.
  Replay loads only that file off Main when the editor requests it, checks the bounded byte
  stream and SHA-256, and validates the decoded drawable's XML/adaptive flags and preview scale.
  The storage gate protects only the read/hash snapshot; decoding that immutable byte buffer
  and verifying the replay happen outside the gate. Reading allocates one bounded byte array,
  rather than a growing byte stream followed by a full Base64 string copy.
  Optional replay decoding also caps bitmap artwork at 16 megapixels and XML at 100,000 nodes
  with depth 64; XML limits are enforced before nodes/attributes enter the parsed tree. Adaptive
  PNG layers retain the existing exact 750 x 750 bounds validation in `AdaptiveIconPayload`.
  Sources exceeding these limits remain usable through their baked result.
  Input encoding is pure. The persistence owner writes, fsyncs and atomically renames source files
  under the storage gate before committing rows. Only after successful row persistence does it
  cache the file reference and release the live source; pixels used elsewhere are never recycled.
  Codec/file failures discard only optional metadata. Obsolete inline-input metadata from
  pre-release v20 builds is removed on database open, before row queries, and during recovery
  restore; baked artwork and the v19 last-stage recipe remain intact. That input-storage change
  used DB version 20 without another schema bump.
  Full backups carry referenced files as separate `icon-inputs/` ZIP entries, validate filenames,
  size and SHA-256, and clear missing/corrupt optional references without dropping baked icons.
  Recovery captures the referenced files and restores their exact set alongside DB and prefs.
  Shared profiles carry the reviewed baked image and last-stage recipe, without pre-edit inputs:
  sharing must not implicitly reveal hidden source uploads. Startup collects unreferenced input
  files and interrupted temporary writes only after recovery and before live drafts/undo exist;
  it queries metadata alone, rather than loading all profiles' artwork. Runtime saves do not
  collect files that an unsaved draft/undo entry may still use. Retained recovery snapshots own
  independent copies, unaffected by input-file collection.
  Metadata v3 adds the explicit render pipeline and bitmap preview scale; existing v2 documents
  remain readable as the shared modifier pipeline with the former default scale of 1. No Room
  schema changes were needed for that metadata update. Optional capture/codec failure preserves
  the rendered artwork. Full backups/recovery retain the metadata through the common codec;
  shared profiles continue omitting all input documents and source files.
  Returning to a 5C1 build is not metadata-preserving: its v2-only reader discards v3 optional
  input references on save, and startup collection can remove their unreferenced files. Baked
  artwork remains usable, but development builds should not alternate across that boundary.
  The codec rejects known incompatible stage/source/origin and representation flags: canonical
  bitmap editing requires a plain bitmap with scale 1; text's adjustments-only stage requires
  non-adaptive XML and a disabled image effect; XML artwork cannot carry bitmap preview scale.
  Replay decodes the file and verifies the actual drawable type/adaptive representation against
  these flags before rendering. Metadata validation does not establish payload validity.
  Retained Material You application artwork freezes the confirmed source colours, including a
  wallpaper palette resolved during creation. Replay must preserve that snapshot after a
  wallpaper change. Explicit source reselection starts a new pair using the current palette;
  it never happens silently during reopening or modifier preview. The restored-editor hint
  explains that source reselection replaces the captured artwork and refreshes its colours.
  Phase 5C2b offers **Restore saved modifiers** only before the editor starts a new stage. Its
  cancellable job belongs to `EditorSessionViewModel`, survives configuration changes and is
  invalidated by release, replacement or a newer restore request. The provider checks the profile
  session, storage epoch and base/input identity before loading and after off-Main verification;
  publication rechecks the epoch and untouched-draft ownership. Rendering holds no profile gate.
  `VerifiedModifierInput` requires matching concrete/export type and bitmap preview scale. Bitmap
  pixels must match exactly; XML must match exactly as well as rasterized presentation, covering
  adaptive/monochrome layers, vector geometry and resolved reference colours. Conservative
  mismatches leave the baked editor usable and explain that saved modifiers are unavailable.
  An input containing earlier baked edits restores only its last stage, with an explicit hint.
  Active Material You pack restyling remains unsupported; its original vector stroke semantics
  cannot be reconstructed from captured PNGs. Text restoration disables image effects and retains
  the adjustments-only pipeline; text/font/colours are frozen source artwork, not editable glyphs.
  `EditorModifierReplay` seeds the existing controls and preserves original source/export settings
  and values not represented exactly by those controls until their corresponding control changes.
  It captures the UI projection baseline during restoration, without writes during composition.
  Create initially shows a neutral **Saved source** panel and source pills. During replay, opening
  the pack browser changes only the browsing source; the active source, current draft and replay
  remain unchanged until an artwork is picked. Outside replay, the Icon pack pill still activates
  the saved artwork without requiring a new pick. App-icon/text selection and actual upload/pack
  picks begin a new stage; leaving the neutral source restores a valid Create source selection.
  Once verified, the raw saved artwork remains the session's baseline for returning to an unpicked
  Icon pack source or seeding vector tools, even after another source ends replay. Neither path
  reapplies restored controls onto the baked base. Vector edits inherit the baseline's earlier-baked
  flag; replacing the paths with an imported vector drops that flag and the seed's inset wrapper.
  Unchanged seed publications cannot detach replay:
  the editor compares complete input snapshots, and publishes only a still-current snapshot.
  Shape fills remain separate from the source background. All modifier-sheet previews and the
  completed draft use the same verified input and full current stage. Replacing the source detaches
  replay, invalidates outstanding renders and starts a new pair. Apply retains source attribution
  and publishes the input/settings from the winning completed render alongside its artwork.
  A failed replay preview retains the last completed artwork/metadata, reports the failure and
  disables Apply until a successful render or return to the last completed controls.
  The persistent-editor feature uses an immutable input drawable from before local
  modifiers, together with the complete source/export settings and local modifier state. Pack
  and drawable references are provenance, not a substitute for retained input artwork: reopening
  must work without the original pack, uploaded file or online resource. Every preview and Apply
  must render the full current state from that immutable input, not from the previous baked
  result. Apply updates the baked base/current payloads for compatibility; a later Global Apply
  remains a separate derived layer. Choosing a new source starts a new input/state pair.
  A v19 recipe alone cannot enable this mode, even when non-empty: it describes only the last
  stage over a possibly cumulative base. Older icons, invalid/missing recipes and unsupported
  brush geometry keep their baked result and must never be presented as recoverable original
  artwork. They can adopt that result as a new immutable starting point with neutral controls,
  making only subsequent edits reversible. The editor must state that previous edits are baked
  in. Restoring their actual original requires explicitly choosing the source again. Fully
  persistent brush editing requires separately serialized geometry; omitting masks from the
  recipe must not imply that the whole edit can be reopened. Availability of retained input,
  settings and all required geometry, rather than recipe presence alone, determines whether
  the complete editable state can be restored.
  **Never lower the version once any build was installed** — schema-identical bump +
  migration instead (see the v5/v6/v7 history in `DbApplication.kt`).
- **`ColorPreset` table** (same database, added in v15) — the saved colour/gradient library.
  Deliberately NOT per profile: a palette belongs to the device, so it is shared by every
  profile, rides the full backup (`BackupData.colorPresets`) and is never part of a shared
  profile file. The row stores the style as one encoded string (`encodeColorizerStyle`).
- **`ModifierPreset` table** (same database, added in v18) — reusable Modifier-tab recipes,
  ordered by last use and shared across profiles. Payload groups contain only portable settings,
  including the optional final shadow; position, picked areas, segment targets and brush strokes
  remain owned by the edited icon. The library rides full backups but is not included in
  shared-profile exports.
- **Room — `WatchDatabase`** (v3) — icon-watch rules, suggestions and per-rule baselines, owned per
  profile via `WatchRule.profileId`.

### Backup import, export and recovery (phases 3A–3B)

`BackupManager` is a Hilt singleton shared by manual operations and `AutoBackupWorker`
(through an application entry point). Its mutex serializes archive inspection, local export preparation and
import. `StorageOperationGate` also serializes profile commits, repository mutations,
preference writes, watch commits, gallery writes and APK signing/credential creation. Export holds this
shared gate while producing a complete ZIP in registered local staging, so it cannot mix preferences from one
Global Save with icons from another. SAF/cloud transfer reads only that immutable ZIP after
releasing the storage, manager and ViewModel profile-session locks. Staging is released on
success, failure and cancellation; automatic exports still remove incomplete SAF documents.
Network verdict lookups and interactive APK installation
stay outside the gate; their eventual database commits acquire it. APK resource generation
also stays outside the gate, using its immutable build plan; only signing reads/writes the
live credentials under the gate. A storage replacement invalidates that build's storage epoch
before installation or persistence can use its result.

Lock order is ViewModel profile-session mutex (when present) → storage gate → backup manager
mutex → provider session gate; watch commits use storage gate → Room transaction,
and preference helpers use storage gate → preference mutex. Initial load also acquires the
storage gate before its load mutex. APK builds have a separate mutex, then acquire the storage
gate for signing; backup/profile operations never acquire the build mutex. Nested calls
inherit ownership through coroutine context.
Watch checks capture rule details, baselines, candidate rows and a storage epoch under the gate.
PackageManager scans, pack parsing and bitmap hashes then run outside it. Before committing
one rule, the checker verifies that its captured rows and epoch still match. Concurrent checks
cannot complete the same rule twice, and edits, deletion or storage replacement invalidate stale
results. Rule saves also compute their baselines outside the gate and validate the existing rule
and provider session at commit. Import baseline initialization intentionally remains inside the
storage commit. Notification posting and last-check metadata acquire the gate again and verify
the result epoch, so a restore cannot reuse old suggestion IDs. No checker mutex is held across
work that later reacquires the storage gate.
The bounded settings path limits lock acquisition, not the subsequent preference transaction.

Import copies the input into a unique staging directory under `filesDir/backup-imports`
and reads it with `ZipFile`, requiring the central directory. Every entry, including unknown
ones, is read to verify its actual size and CRC before any persistent store changes. Paths,
duplicate entries, format versions, profile identities, active-profile ownership, icon
uniqueness, known preference types and preset decodability are validated. Formats 1–3 remain
supported; format 4 additionally requires typed profile documents and matching compatibility
projections. Older optional fields keep their codec defaults. JSON nesting is capped at 64.

Watch data is normalized on both export and import, without changing the exporting DB.
Invalid or duplicate app/pack children, stale baselines, orphan suggestions and unusable
candidates are removed. The newest usable baseline/suggestion is retained for duplicate
owners. Completed rules with no remaining suggestion are skipped, matching the repository's
legacy import policy. Core profile/icon identity problems still reject the archive. The
normalized export is validated before the output URI is opened.
Normalization logs discarded rule, completed-rule, app/pack, baseline, suggestion and
candidate counts at info level, including cascaded removals, without logging payloads.

Production reads/writes `data.json` through `JsonReader`/`JsonWriter` rather than allocating
a complete JSON string and a second UTF-8 byte array. Identical `drawable`/`baseDrawable`
strings share a reference during decoding. Models and a JSON tree still retain the icon
payloads; very large imports can still exceed a device's heap. A future format with separate
icon entries and a streaming storage commit remains necessary for bounded payload memory.

Both export and import cap archives at 10,000 entries, 64 KiB for the manifest, 512 MiB for
`data.json`, 32 MiB per modifier-input file, 64 MiB per other file and 1 GiB total uncompressed.
Import also caps the source ZIP at 1088 MiB. These are transport limits, not a guarantee that every supported file fits
every device's heap. A fraction of `maxMemory()` cannot reliably predict the live heap,
JSON object overhead or the number of distinct payload strings; it is not used as an
additional compatibility cutoff. Export streams the JSON into a bounded temporary file before opening
the destination and checks actual file bytes while writing. `StatFs` checks staging space
before extraction and periodically while reading/writing. Low storage, size limits and low
memory have distinct UI messages; OOM logs include available payload sizes and heap capacity.
Space checks retain a 32 MiB minimum reserve. Full restore additionally reserves the current
database/WAL sizes plus the incoming JSON bytes, checked before file preparation and again
before the first live mutation. This estimates SQLite write space, not a guarantee against
other concurrent storage writers. Large-archive transfer progress remains future work.

The supplied real format-3 backup measured 3,096,120 ZIP bytes and 6,286,362 `data.json`
bytes, with 192 icons (none adaptive) and seven watch rules. It fits the former 64 MiB cap;
it is not evidence of the upper size of an adaptive-heavy profile.

Inspection records SHA-256 and produces a `PreparedImport` owning verified staged files.
The ViewModel retains this handle during the confirmation dialog and imports that exact
snapshot, even if the original URI changes or disappears. JSON is parsed again at commit,
but the ZIP is not copied or extracted twice and icon models are not retained in the dialog.
Cancellation, replacement of a pending import, completed jobs and ViewModel cleanup release
the staging handle. Main-process application startup removes abandoned staging/export directories on IO,
without waiting for another import or a 24-hour expiry. Directory creation, active ownership
and cleanup share a process-local lock so startup cleanup cannot remove a live preview,
export or file transaction. Abandoned file-commit batches are preserved and reported in logs
because they may contain the only surviving originals. Journal-owned batches are removed after
recovery; legacy phase-3A batches without ownership metadata are preserved and logged.
Secondary processes skip cleanup; running backup operations in multiple processes would also
require cross-process coordination beyond the current registry and manager mutex.

The provider acquires its session gate only for the validated commit. Archive work already
owns the outer storage gate before entering the backup manager mutex and provider gate.
An invalid archive leaves the live session intact.
Restored files are synced and moved from the verified staging directory into a temporary
batch under `filesDir`, keeping all final renames on the destination filesystem. Only an
`EXDEV` failure falls back to a checked, synced copy. Preparation consumes the staged files;
the import handle is released after that attempt, including cancelled preparation. Import
failure messages tell the user to select the file again when retrying. File changes are applied before database
writes; old files remain in the batch until storage commit succeeds. The full local recovery
snapshot, described below, rolls back files and both databases/preferences together. Failed
rollbacks preserve the snapshot, pending marker and original file batch for recovery.
An absent keystore preserves both local signing files; restoring a legacy keystore
removes a stale random password. Coroutine cancellation is accepted through file preparation
and blocked once file mutations start. Cancelled automatic exports delete their output and
propagate cancellation rather than scheduling a retry.

Before either a full-backup import or an additive shared-profile import mutates live storage,
`BackupRecoveryStore` captures the complete persisted local state under
`filesDir/backup-recovery/<id>`. Both Room databases are written row by row in an internal
format retaining every table, column, row ID and `sqlite_sequence`, including built-icon
records and cached verdicts. Preferences include device-local keys; the gallery includes
trash, file timestamps and the presence or absence of each signing credential. This snapshot
is deliberately separate from portable backups, whose normalization and regenerated IDs
would lose local state during rollback. Unsaved editor drafts are not part of it.

Every snapshot file is synced, sized and SHA-256 hashed before the manifest and directories
are synced. An atomic, directory-synced `pending.json` points to that immutable snapshot
before the first live mutation. File batches are recorded in its manifest before staging is
consumed. Import and rollback run non-cancellably after activation. Caught failures restore
both databases, all preferences and files; failed rollback keeps the marker and blocks
subsequent writers. Database WAL checkpoints and changed directories are flushed before
clearing the marker. These stores still commit separately internally: the guarantee comes
from rollback before exposing an interrupted state, not a distributed database transaction.

Application `attachBaseContext` installs recovery before content-provider initialization,
including WorkManager, can start UI/repository writers or workers. A pending import is rolled back to its previous saved state, even if most steps had
already finished. Recovery is idempotent; another process death during rollback repeats the
same snapshot on the next launch. Failure displays a recovery screen with explicit retry,
retains all recovery material and blocks writes. The gate exposes a typed failure reason:
schema change, missing/corrupt snapshot, I/O, or unknown. Schema/corruption messages recommend
the confirmed keep-current option; storage advice is reserved for I/O failures. The failure
classification also checks nested and suppressed rollback causes. Failed retry/abandon actions
show inline feedback on the recovery screen, and their buttons are disabled while running.
Retry reloads the provider session before
releasing the UI. A confirmed “Keep current data and abandon recovery” action is the escape
when corruption or an incompatible schema makes retry ineffective. It warns that current stores
may be inconsistent, atomically moves the entire journal to a logged
`backup-recovery-abandoned-<id>` directory and syncs filesDir. This preserves even unreadable
metadata and snapshots outside normal cleanup. Only then may the gate become READY; the
provider reloads current persisted data, with a normal startup retry screen if loading fails.
Quarantined recovery material remains on disk for troubleshooting and is not the normal undo
state. Settings exposes a separately confirmed "Delete abandoned recovery copies" action when
these directories exist. Only directories with the exact abandoned prefix and UUID suffix are
selected; deletion does not follow symbolic links. Current storage, the active journal and
normal undo snapshot are untouched. Partial deletion failures remain visible and can be retried.
No automatic expiry removes potential troubleshooting/signing material. Room flows cancel their source subscription whenever recovery leaves READY
and resubscribe once it finishes; ordinary app start shows a checking
state, and only a pending marker shows the interrupted-import message. This coordination is
process-local; adding database writers in another process requires a cross-process protocol.

Success retains one pre-import snapshot through `previous.json`. Settings exposes
“Restore previous saved state”, with confirmation that unsaved edits will be lost and that a
changed signing key requires uninstalling/reinstalling packs signed after import. Restore is
hidden for incompatible database versions or columns; “Discard previous saved state” remains
available with confirmation, including for unreadable or incompatible undo metadata. Discard
removes and syncs the previous pointer before normal snapshot cleanup. This restore action
also captures the current persisted state and uses the same pending-marker protocol, making
its own interruption recoverable. It restores all profiles, watch IDs, presets, device-local
preferences, gallery and signing credentials, rather than importing a portable archive.
Unused snapshots are cleaned after completion and at startup; active and retained snapshots
are kept. Gallery trash callbacks carry a generation so delayed undo cannot overwrite a
restored gallery. Existing watch notifications are cancelled before replacing watch records;
system notifications are not reconstructed from the internal snapshot. After a successful
restore or explicit recovery retry, `BackgroundWorkScheduler` reconciles periodic watch and
automatic-backup jobs with the restored preferences. Startup repeats that reconciliation to
cover process death after the durable storage commit but before rescheduling. Interval
setters also hold the storage gate through submitting their WorkManager update.

The import/undo dialog centers its indicator and labels the current operation. During capture
it distinguishes icons/profiles, watch rules, preferences, files and verification.
Preparation can be cancelled from the button or Back; outside taps do not dismiss it. Accepted
cancellation and the transition into the durable commit share one synchronized boundary, so a
Cancel accepted while preparing cannot later enter the non-cancellable mutation phase. The
ViewModel cancels the owning job and retains the busy UI until cleanup releases staged state.
Snapshot capture, file preparation and space checks hold the storage gate without entering
the provider session gate. The cancellation boundary closes before entering the replacement
callback; its whole commit/reload runs non-cancellably. Cancelling or failing during preparation
therefore preserves live unsaved icons, profile tokens and undo, for both full import and
previous-state restore. Only a durable restore replaces the live profile session.
After commit begins the cancel action is unavailable; rollback/recovery must finish normally.
Database snapshots and portable JSON buffer character output before UTF-8 encoding: Android's
JsonWriter emits strings one character at a time, so an unbuffered OutputStreamWriter makes
large base64 icon payloads appear stuck while the encoder consumes CPU. Debug builds log stage
timings without payloads to distinguish slow work from blocked operations; release builds log
only the total successful capture time.
Recovery needs additional disk space for immutable snapshots and SQLite writes. Capture
checks free space while streaming database rows and before copying files; commit reserves
both snapshot database sizes, incoming JSON, the largest file to restore, and 32 MiB. This
remains an estimate against other apps consuming storage. One retained snapshot can occupy
roughly the saved databases plus referenced modifier inputs, gallery and signing files.

Internal database recovery requires identical schemas except for explicitly supported
snapshot migrations. Audited additions (18 → 19, 19 → 20 and 20 → 21) compose by table to
support v18/v19/v20 snapshots on the current schema without enumerating every pair. All old
tables/columns must still match exactly. Only the explicitly appended `DbApplication.modifierRecipe`,
`DbApplication.modifierInput` and `Profile.profileStyle` columns applicable to that version chain
are omitted from inserts so their SQL defaults supply empty strings, with the expected
TEXT/NOT NULL/empty-string defaults checked. Restoring an older snapshot replaces Profile rows,
so it cannot retain a newer typed style alongside restored legacy preferences. Old row identities,
preferences, artwork, watch data, gallery and signing state are preserved. This supports both pending startup recovery
and retained previous-state snapshots from v18/v19/v20. Every future Room schema bump must add
compatible recovery handling before shipping: a pending snapshot from the preceding version
must not strand startup behind a schema mismatch.
Matching column-name prefixes and SQL defaults alone do not authorize snapshot compatibility.
An additive Room migration may backfill new fields from old values or change existing row
semantics: v11 → v12, for example, copies drawable fields into the new base fields and marks
old generated rows as legacy. Restoring those rows with empty/false defaults would skip that
migration's meaning. Each supported version transition therefore needs an explicit adapter
and regression coverage; future transitions must compose the required transformations rather
than accept older versions just because inserts succeed.
Both database schemas are preflighted before either database is mutated. A mismatch fails
closed and preserves the snapshot, with the explicit abandon option above. Portable backup DTOs
are not substituted for exact local recovery: they normalize data and do not preserve every ID
or local column. Unsupported schema changes still require new snapshot adapters. Large portable JSON payload memory, byte transfer progress and image/keystore semantic validation remain separate
follow-ups; CRC verifies transport integrity only.

## UI conventions

- **Fullscreen screens** (Settings, Crash logs, Watched icons) are fullscreen dialogs with
  the same M3 `TopAppBar`: back arrow, primary-tinted title, actions on the right.
- **Fullscreen content width**: single-column screens use `CenteredFullscreenContent` so rows
  stay at a readable 720 dp maximum on tablets and occupy the wider side of a separating fold.
- **Feedback**: plain notices go through the shared `Toaster` (`LocalToaster` + `ToastHost`);
  a `SnackbarHost` exists only where an action is attached (upload gallery's Undo).
- **Shapes**: use the tokens in `ui/theme/Shapes.kt` (`DialogShape`/`CardShape`/`FieldShape`/
  `InnerShape`/`IconShape`) — no raw `RoundedCornerShape(n.dp)` at call sites.
- **Scroll chrome** (`ui/ScrollChrome.kt`): `OverlayHeaderLayout` lays a collapsing header
  *over* scroll-under content (Mihon-style — the content keeps its size, so flings stay
  smooth while the bar animates; used by the edit dialog), and `Modifier.drawVerticalScrollbar`
  adds the transient scrollbar used on every long list/grid.
- **Loading**: the startup shell and full-page application-list loader use Material's
  morphing-shape `LoadingIndicator`, preserving the established startup animation. Linear
  progress uses the shared `WavyLoadingBar` (ControlWidgets.kt); no hand-rolled indicators.
- **Dialogs**: `RenkinAlertDialog` is the app-wide dialog chrome; `ConfirmDialog` (destructive
  confirmations) delegates to it.
- **Tooltips**: `RenkinTooltipBox` (UIHelper.kt) — a custom position provider clamps the popup
  into the window (the stock M3 plain-tooltip provider doesn't, so edge tooltips ran off
  screen) and text wraps in a width-capped rounded bubble. Pack icons show their
  `prettyDrawableName()` on long press.
- **Pack picker sheet**: two anchors only (`skipPartiallyExpanded`) plus a nested-scroll
  connection that swallows fling leftovers at the list edges — workaround for the M3
  ModalBottomSheet jitter bug (issuetracker.google.com/issues/486562294); the connection can
  go once material3 ships the fix.
- No pull-to-refresh on the home list **on purpose**: its nested-scroll handler fought the
  collapsing large top bar (glitches, ghost taps). The app list reloads from Settings.

## Colourizing

The colour pipeline is shared by three entry points — the per-icon Colorize modifier, the
pack-wide colourize in Global/Advanced options, and the outline colour — so they behave
identically and none of them re-implements the maths.

- **`ColorizerStyle`** (`icon/creator/`) is the whole description: single colour or gradient,
  gradient type/angle, 2–10 stops (`MIN_GRADIENT_STOPS`/`MAX_GRADIENT_STOPS`), plus the Solid
  fill / Lighten / Monochrome / Inverse flags. Those flags apply to gradients too: Solid replaces
  the artwork's RGB through its alpha, the default Multiply blend darkens/tints it, and Lighten
  uses Screen (`source + tint - source*tint`) for pastel results without Plus-mode clipping while
  retaining each source pixel's alpha, so transparent canvas space never becomes a colour fill.
- **`ui/AdjustmentState.kt`** owns four immutable styles: `colorizerStyle`, `shapeStyle`,
  `outlineStyle` and `shadowStyle`. Editor controls and preset mapping read/replace these groups
  directly; per-colour getters/setters and forwarding apply/read functions have been removed.
  Colorize's first stop still comes from the dialog's shared icon colour, which also drives
  the Create tab. `colorizerStyleWith` applies that hoisted colour when capturing a preset;
  generation applies the same first-stop rule when mapping editor adjustments into the stack.
  Individual adjustment and Material You pack state now belong to the live editor session.
  Their unused Bundle Savers, legacy restoration adapters and Saver-only projections have been
  removed; they are not placeholders for database recipes. Global Options' per-icon state stays
  remember-only. Brush masks and strokes are live geometry rather than portable recipe fields.
  Rendering still inherits the presentation background and replaces Colorize's first stop with
  the dialog's shared colour. `ModifierRecipeCodec` and `GlobalModifierStateSaver` remain active;
  this removal changes neither portable presets nor Global Options' saved-state compatibility.
- **`GenerationOptions.withColorizerStyle`** is the common transfer into generation for
  callers replacing a colour style. It replaces the complete effect colour group and preserves
  the caller's image-edit choice and segment layers. The editor's
  `asColorizePreview` explicitly selects plain Colorize and clears segments.
- **`GenerationOptions`** owns `IconSourceOptions`, `ModifierStack` and `IconExportOptions`,
  plus the replacement policy (`override`). Source options describe primary/secondary source
  selection, text, fallback styling and the application-icon variant. Export options own vector
  output, PATH monochrome selection, themed presentation, one background `ColorFill` and foreground
  style. Application-icon variants and PATH monochrome output are independent; only the legacy
  preferences boundary and editor explicitly preserve their previous relationship. Creating a
  PATH monochrome export does not require selecting the application's Material You variant.
  Primary image editing belongs to `modifiers.effect`; `source.secondaryImageEdit` belongs to
  the secondary source attempt and runs only when the primary source cannot supply an icon.
  `withSource`, `withModifiers` and `withExport` update one owned group; `withEffect` updates
  only the stack's effect group. Other groups are retained unchanged. There is no flat generation
  constructor or read-only flat modifier/source/export API.
- **`ModifierStack`** groups image effects (colour style, segments, edge detection and background
  removal), transform, shape, outline, shadow and pack-specific `MaterialYouPackModifier`.
  `GenerationOptions` owns the stack as a constructor field; source-only `copy()` calls retain it rather than rebuilding a flat mapping.
  Editor adjustments, global preferences and preset application construct or replace groups
  directly. Editor adjustment mapping copies only its owned groups into the existing stack,
  retaining Material You pack controls edited in their separate state. Pack restyling is applied
  before the image edit and is included in `ModifierStack.hasVisibleEffect`; it does not change
  the geometry-only rasterization or flattening predicates.
  `IconImageEditPipeline` takes the stack plus the owned `IconExportOptions`; it no
  longer builds a separate presentation projection. `IconAdjustmentPipeline` consumes the
  stack directly. Shared predicates decide whether adjustments are present, pixels
  are required and the output loses its adaptive presentation. Transform-only operations retain
  vectors or adaptive layers, while outline alone retains an existing bitmap source's adaptive
  export flag and preview scale. Groups may be absent, or retain inactive configuration through
  `IconShape.NONE`, `OutlineMode.NONE` and the shadow's enabled flag. Disabling a configured
  modifier therefore retains its colours and geometry. An invisible shadow also leaves the
  source presentation unchanged. Render order
  remains image edit → transform → outline → shape → shadow.
  `ShapeModifier.fill` defaults to null and inherits the presentation background at render time;
  a background-only `copy()` therefore updates the plate without copying colour state into the
  stack. Editors, global options and presets use this inherited fill to preserve the previous
  shared-background behaviour. An explicit fill instead belongs only to the shape, and a shape
  colour preview updates that override without changing the presentation background.
  The renderer receives the inherited background explicitly; it never guesses a fallback colour.
  New modifiers belong in their groups. Data-class equality includes every source, modifier and
  export group. Preferences and preset payloads keep their existing boundary formats; Global
  Options retains its recipe envelope in saved state. The individual editor uses a live session
  and the grouped colour API throughout. Stored per-icon recipes describe the last baked stage;
  persistent non-destructive source/recipe replay and direct ownership of geometry groups in
  the editor remain follow-ups.
  Equality deliberately retains inactive configuration; a visual-only cache key may later
  normalize disabled groups, but session/recipe comparisons must continue distinguishing their
  full configuration.
  `OutlineModifier.eraseMask` retains bitmap reference equality; do not treat stack equality as
  a comparison of mask pixels when designing cache keys.
- **`ModifierRecipeCodec`** owns the shared effect, shape, outline and shadow field definitions
  for full `ModifierStack` recipes and partial portable presets. Full recipes include transform,
  picked removal colours, ordered segment layers and Material You pack controls. They use named
  keys and enum names, including nested colour styles, with an explicit recipe version. Missing
  optional groups remain absent; present inactive groups retain their configuration. A shape's
  inherited fill remains distinct from an explicit fill, whose flat fallback colour may differ
  from its style's first stop. The compatible version range starts at v1; future versions may
  extend it only when the older wire schema remains readable, otherwise they need an adapter.
  Unsupported versions and duplicate keys are rejected.
  `decode` is the untrusted-input boundary: it caps documents at 1 Mi characters / 8192 fields and
  128 segment layers, clamps geometry, tolerances, opacity and line weight to supported ranges,
  and limits target lists to 256 colours and gradients to 10 stops including the first colour.
  Numeric/list parsing uses finite values and bounded sequences. Empty imported gradient-stop
  lists get a black fallback; positions are bounded, monotonic and must match the stop count.
  `decodeSavedState` is reserved for our own Bundle data and retains finite editor values and
  collection sizes without these import limits. `GlobalModifierStateSaver` uses this trusted entry point.
  `encode` omits session erase masks and background brush operations without throwing. Callers
  must store these separately; `requireEncodable` offers an explicit strict check for persistence
  contracts and tests that require a complete mask-free recipe.
  Preset v1 delegates to the same group readers/writers through a compatibility policy: its wire
  keys, inline legacy style encoding, missing-group semantics, numeric limits and fallback colours
  remain unchanged. It continues excluding icon-specific position, targets, segments, masks and
  pack controls. Existing colour presets, adaptive payloads and preference style strings retain
  their older colour codec; there is no Room or portable-backup format migration in this step.
  The full recipe serves Global Options saved state and optional per-icon metadata in Room v19.
  The individual editor retains source/export choices, image edit, first colour
  and adjustment state together in its live editor session; it does not serialize a partial
  draft into saved state. Global Options' per-icon controls remain remember-only.
- **`ui/GlobalModifierState.kt`** owns Global Options' staged `ModifierStack`; its controls update
  immutable effect, transform, shape and outline groups. Disabled groups retain their settings.
  Shape and outline keep explicit styled fills in this draft. Preview and Save build their options
  with `globalModifierOptions(overlay(emptyPreferences()))`, using the same global preference
  reader as refresh, empty-slot generation and profile reload. This applies the persisted precision
  (whole scale percentages, outline widths and gradient degrees) before rendering, and retains the
  shared inherited-background contract for plates.
  Source selection and target-category flags remain separate from modifiers.
  `GlobalModifierSnapshot` carries the same stack, rounding scale percentages, outline width and
  gradient angles at the existing preference precision for the dirty check. The preference overlay
  is the only writer of legacy global keys; seeding uses the shared global-generation reader and
  also retains the inactive plate style. `ColorizerStyle.normalizedFlags()` enforces monochrome →
  flat → lighten precedence at both global preference boundaries, effect updates and snapshot
  comparisons. Restoring either the draft or its baseline applies the same normalization to old
  conflicting flags, preventing a false dirty state after rotation. Colours, gradients, inverse and
  disabled groups' configuration remain intact. The individual editor and full recipe codec retain
  their existing complete-style semantics; this normalization belongs only to the global colourizer.
  `GlobalModifierStateSaver.kt` encodes this snapshot with the trusted recipe codec for both the
  draft and its dirty-check baseline. It still reads the older keyed style snapshot and positional
  pipe-delimited snapshots. Malformed recipes reset the draft. Valid recipes with missing shape
  or outline groups get the configured disabled defaults in both draft and baseline restoration;
  fills without a style retain their colour in a new single-colour style. Direct restoration and
  group updates enforce the same invariant, so the controls always have both configured groups.
- **`ColorizerShader.kt`** builds the actual `Shader` and is called by BOTH `IconGenerator` and
  the editor preview — a second implementation in the UI would drift from the built output.
- **`ui/ColorStyleSheet.kt`** is the one editor UI: a bottom sheet with a docked live preview
  (rendered through the caller's real generation pipeline, debounced), the mode pill, the stop
  list and the angle dial. `ColorStyleCard` is the row that opens it.
- **`ui/ModifierTab.kt`** composes the modifier editor and coordinates its preview/brush jobs,
  preset scroll anchor and host-dialog visibility. Sections live in the `ui.modifier` package:
  Material You pack styling, scale/position, shape, outline, shadow and external-editor actions.
  `ModifierDialogHost` presents the colour, position and brush dialogs through the same host callbacks.
  Each section retains its own transient sheet state; values stay in `AdjustmentState`, and preview
  callbacks still come from `ModifierPreviews`. Shadow's encoded Cancel snapshot remains local to
  its section. `IconShapeSection.kt` also supplies the shared geometry-based `ShapeSwatch` and
  `shapeLabel` used by Global Options and preset summaries.
  `ui/MaterialYouPackAdjustmentState.kt` owns the pack-specific draft, compatible Saver and centred
  line-weight slider mapping; its package and saved format are unchanged.
- **Segments** (`icon/creator/ColorSegments.kt`) cluster an icon into colour regions (k-means over
  RGB). A pick is stored as COLOURS plus a tolerance, never a pixel mask, so it survives
  regeneration and rescaling. `ImageEdit.COLORIZE_SEGMENTS` stacks `SegmentLayer`s — each layer's
  picker shows the output of the layers before it, and generation matches against that same
  accumulated image so the stored colours describe exactly what the user selected.
- **Adaptive panes**: `ProvideAdaptiveLayoutInfo` supplies current WindowMetrics and a separating
  vertical fold. Pane layouts switch only when both sides meet their content-specific minimum
  width; nested editors such as the segment picker decide from their actual container width.
- Preferences carry gradients as comma-separated ARGB (`COLORIZER_GRADIENT_COLORS`, and the
  outline's own `OUTLINE_GRADIENT_*` keys); the pre-gradient single-colour keys are still written
  so older builds and older backups keep working.

## The build "change bar"

The Options card shows a segmented bar (blue = already built, green = added since last
build, red = removed since). It is a **diff** of the current app list against
`MainViewModel.builtKeys` (the keys saved in the last built pack), not event tracking — so
it stays correct even when icons are added via the Refresh button. `builtKeys` reloads after
each successful build and after "Clear icons". `updatedKeys` tracks this session's hand edits.

## Icon pack build

`IconPackBuildService` receives an immutable active-profile snapshot from `ApplicationProvider`,
then resolves calendar/lock data and delegates assembly to `IconPackBuilder`. The provider holds
the profile-operation gate throughout the build and persists that same snapshot after the install
attempt, so switching profiles cannot mix build inputs or results. Installation itself does not
hold the gate. The built result carries its session token: completing an old install cannot clear
another session's undo or lock newer live refresh output.

`IconPackBuilder` assembles the APK with reandroid (no external build tools). The generated
pack's dex classes come from prebuilt smali assets (`app/src/main/assets/{R,RLayout,
MainActivity,BuildConfig}`); the pack's applicationId is parameterized per profile, but the
smali class package must match IconPackBuilder's activity FQN string. Signing uses
`renkinpack.keystore` in filesDir. Each launcher activity of a package gets its own drawable
file name (a package with several activities must not overwrite one icon with the other).
Packs identify apps by `ComponentInfo` in appfilter.xml — never by name.

### Adaptive icons imported from packs

`AdaptiveIconPackDrawable` owns separate foreground/background PNG layers and an optional
monochrome layer. Source vectors, bitmaps and drawable wrappers are rendered independently at
750 px (108dp layer space); Android applies the adaptive mask and 1.5x viewport expansion only
when composing the 500 px preview. Original layer insets are not removed or normalized. Layers
stay compressed in memory, and the exported resources never reference another installed APK.
Adaptive drawables do not retain full-size rendered/preview bitmap fields. List and browser
previews render directly to their requested size with the same 108/72 viewport compensation;
small previews decode sampled PNG layers. Export and modifier rasterization retain their own
uncompensated geometry and the stored/exported PNG payloads remain at full resolution.
Generated-icon previews use `IconPreviewCache` with a total 8 MiB budget: 6 MiB for previews
up to 256 px and 2 MiB for larger editor/painter images, so detail previews cannot evict list
thumbnails. Keys weakly reference drawables and verify identity rather than relying on identity
hashes alone; replaced source drawables are not retained by cached thumbnails. Mutable sources
also include their edit revision, while immutable adaptive instances share entries across list,
painter and `draw()` requests. Adaptive painters load off Main, show cache hits in the current
frame, and retain the previous image while a cache miss renders; an initially empty painter still
has a stable intrinsic size. Rendering is serialized per drawable; future main-thread View users
must prewarm previews to avoid waiting for an in-flight render through synchronous `draw()`.
Render failures produce an empty preview, cancellation propagates, and a failed allocation
clears both caches without recycling bitmaps still used by visible composables. Default preview
downscaling preserves aspect ratio in both lists and the browser.

Position/scale transform foreground and monochrome together while leaving the background in
place. Colorize changes only the foreground; gradients retain its alpha. Shape, Outline,
Remove background, Path, Edge and segment colorize consume the complete masked composition
and produce a non-adaptive result. Global modifiers continue deriving from the saved base icon,
so disabling a flattening modifier restores the adaptive layers. The app-icon source's
"Full app icon" behaviour and the explicit global themed export remain separate policies.

The per-icon Shadow modifier runs last from the final alpha silhouette. Blur, direction, distance,
opacity and the shared colour-or-gradient style are deterministic at the 256 px working scale.
Direction can use an offset angle or a centred shadow whose distance expands the silhouette evenly
in every direction. Since a launcher clips an adaptive drawable to its mask, enabling an outside shadow deliberately flattens only that icon
to a legacy bitmap; disabling it keeps or restores the adaptive layers through the normal base-icon
flow. Shadow settings are portable in modifier presets, while the applied result remains baked in
the stored drawable like the other per-icon adjustments. On phones its editor is a modal dialog
with a large live preview and one fine-tuning control at a time; the Direction control reuses the
gradient angle dial. Wide two-pane layouts keep those controls inline beside their persistent
preview.

The existing rendered/base XML columns store a `renkin-adaptive-icon` payload. Version 2 also
stores the selected Material You pack scheme, its custom styles, line weight and the original
foreground/background when recolouring changed them. Reopening the per-app editor therefore
restores both the adaptive type and its highlighted editing controls, and Original can restore
the source layers without the source pack being installed. Version 1 layered payloads remain
readable. `XmlNodeParser` validates the format before ordinary vector or inset decoding. Room
did not need a schema change for the adaptive payload itself; v19 adds only local modifier metadata. Backup
format 2 prevents older importers from silently losing this new drawable type; format 1
archives remain readable. Already-flattened saved icons cannot recover their missing layers:
the user must select their source again.

Normal export writes a base bitmap fallback plus same-name `drawable-anydpi-v26` adaptive XML
and, when available, `drawable-anydpi-v33` XML with monochrome. Each XML has a distinct archive
path. `appfilter.xml` points to the versioned resource; `appmap.xml` and `theme_resources.xml`
point directly to a legacy bitmap alias. Qualifiers select by Android version, not launcher
capability: a launcher using appfilter on API 26+ must accept/render adaptive drawables itself.
Monochrome is captured from the framework on API 33+, or from the selected source XML on older
systems. A source variant available only under v33 cannot be selected by Android 26–32.

Global themed builds additionally declare `org.icontheme.CHANGES_WITH_MATERIAL_YOU_COLORS` on
their ADW theme intent filter. Smart Launcher uses that capability to reload packs whose resources
refer to Material system colours when the wallpaper palette changes. Plain preserved adaptive
icons do not declare it because their original colour layers are intentionally static.

The Material You swatches in the per-app editor read Android's public `system_accent*` resources.
They are refreshed when Android reports a configuration change. Some OEM launchers, including
OnePlus modes that explicitly match the home wallpaper, calculate a separate private icon palette;
Renkin cannot reproduce that launcher-only selection through the standard resource API.

## Testing

- Pure JVM unit tests for pure functions (`DataPreferencesTest`, `ColorHexTest`).
- **Robolectric** tests for the data layer with an in-memory Room database
  (`WatchRepositoryTest`, `RenkinPackRepositoryTest`). They use a plain `Application` and a
  pinned `@Config(sdk = [33])` to stay off Hilt and the very new `compileSdk`.
- Every Room version bump must commit the schema JSON generated by Room for that version.
  Migration tests must retain representative old row values and verify defaults/backfills of
  added fields. Recovery tests must cover both the supported preceding-version snapshot and
  rejection of unsupported schema changes; column-name compatibility alone is insufficient.
  Follow the device-confirmation checkpoint in CLAUDE.md before creating/running these tests.
- v20 input-storage coverage must include v19 migration with unchanged artwork/recipe,
  v18/v19 recovery adapters, source/export option round-trips, invalid/oversized optional
  documents, matching completed-render ownership, reset/undo, full-backup file round-trips and
  shared-profile source exclusion. Cover pure encoding, failed-save live-source retention,
  deduplicated files, missing/corrupt references, recovery file replacement, startup-only orphan
  collection and obsolete inline v20 metadata cleanup. Loaded inputs must retain only small file
  references; repeated saves must reuse retained artwork. Large original drawable/base columns
  still have their existing CursorWindow/JSON heap limits; externalizing optional inputs does
  not externalize those older columns.
- Recovery/Application tests in a shared JVM must call `StorageOperationGate.resetForTests()`
  between isolated scenarios, only after all gate owners/jobs have stopped. Production still
  rejects installing two recovery callbacks.
- MainActivity handles orientation and size changes through `configChanges`. Rotation checks
  layout continuity, but does not exercise ViewModel retention across Activity recreation.
  Changing system font size exercises recreation because `fontScale` is not handled in the
  manifest. Keep "Don't keep activities" disabled for configuration-retention checks; enabling
  it tests Activity destruction and ViewModel loss instead. Use fixed colours when comparing
  artwork, as dark-mode or wallpaper changes may legitimately change dynamic colours.

## Gotchas (don't relearn these the hard way)

- **Never** put `Drawable` / `ResourceDrawable` / `IconPackDrawable` in `rememberSaveable` —
  they aren't `Parcelable` and crash on background save. Use plain `remember`.
- Adaptive (XML) launcher icons can't be loaded by `painterResource`; go through
  `packageManager.getApplicationIcon(...).toSafeBitmapOrNull()?.asImageBitmap()`.
- Third-party inset/vector icons can report `-1` intrinsic dimensions (Lawnicons does); modifier
  rasterization must request the explicit 256 px working size instead of relying on intrinsic size.
- Downscale list icons to their on-screen size (`toSafeBitmapOrNull(px, px)`, never upscale)
  to avoid scroll jank.
- `LazyColumn`/grid keys must be unique — duplicate keys crash.
- `ic_launcher_monochrome` PNGs are alpha glyphs (Material You tints alpha only) — don't
  replace them with opaque images.
- Composition locals (`LocalToaster`, `LocalMainActivity`) are provided per-activity — a new
  activity hosting shared composables must provide its own (see WallpaperPreviewActivity).
