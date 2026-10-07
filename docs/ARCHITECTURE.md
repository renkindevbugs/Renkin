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

```
Composables (ui/)                         ← stateless-ish views, read VM state, call VM funcs
        │  hiltViewModel()
        ▼
MainViewModel / WatchViewModel            ← @HiltViewModel; own session/UI state, orchestrate
EditorSessionViewModel                   ← owns the individual editor's live draft and controls
        │                                    operations on viewModelScope
        ▼
ApplicationProvider (apk/)                ← orchestrator: owns the app list, profiles, refresh,
        │                                    build snapshots and persisted results
        ├── InstalledAppCatalog            ← launcher activities, labels and application icons
        ├── IconPackCatalog                ← installed icon-pack discovery and package metadata
        ├── IconPackRepository             ← installed packs, app-filter elements, per-app
        │     │                              drawables, calendar icons (Compose-state backed)
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

`PresetLibraryViewModel` owns the device-wide colour and modifier libraries for each activity.
MainActivity and GlobalOptionsActivity obtain it through Hilt and use the same library UI callbacks.
It receives the singleton RenkinPackRepository through constructor injection and reads and writes
the library directly. MainViewModel and GlobalOptionsViewModel retain profile and editor operations.
Repository storage gates, recovery-aware Room flows, preset ordering and persisted formats remain
the same. Success toasts come from the library owner after the write. Write failures are logged
and reported with a failure toast; cancellation still propagates. Profile changes do not own or
reset this device-wide library. AppModule owns the repository binding and the factories that
compose domain collaborators.

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
configured style (`Profile.profileStyle`, with legacy/UI projections of `ProfilePrefKeys`
from `DataPreferences.kt`), its watch rules, and
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

## Threading & state

- Heavy work lives in `suspend` functions that hop to `Dispatchers.Default` internally
  (`withContext`). View models call them from `viewModelScope` (Main) safely.
- `lifecycleScope.launch` is the Main dispatcher — never touch Room / PackageManager from it
  directly; go through a suspend function with `withContext(Default)` inside.
- Loaded lists / flags are Compose `mutableStateOf` so the UI recomposes when they change.
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

- **DataStore Preferences** (`data/DataPreferences.kt`) — all settings. Typed accessors:
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
- **Loading**: the shared `WavyLoadingBar` (ControlWidgets.kt), not hand-rolled indicators.
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
