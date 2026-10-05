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
generation preferences (JSON snapshot in the Profile row, swapped through the shared
DataStore on switch — see `ProfilePrefKeys` in `DataPreferences.kt`), its watch rules, and
builds its own pack APK under its own package (see Naming). The default profile (id = 1) is
undeletable; deleting a profile also deletes its watch rules, and a watch notification whose
profile has since been deleted shows an explanation instead of applying to the wrong profile.
Switcher = the top-bar title dropdown.

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
  app-list reload and clearing all icons invalidate the session. Refresh, hand-picked icons and
  Global Save capture their inputs under the gate, render outside it, and reacquire it to commit.
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

## Persistence

- **DataStore Preferences** (`data/DataPreferences.kt`) — all settings. Typed accessors:
  Composable `DataStore.getXValue()` for reading in composition; `Preferences.getXValue()`
  for reading a captured snapshot off the main thread.
- **Room — `RenkinPackDatabase`** (file `"renkinPack"`, v18) — profiles + rendered and base icons
  of the last built/saved pack per profile. `isCustomIcon` marks hand-picked vs refresh-generated
  rows; `isLegacyIcon` records pre-classification uncertainty without guessing the origin. In the
  Global options UI, only unsaved refresh output is Generated; saved/built non-custom rows are
  Existing and have their own apply toggle. Global
  modifiers are a derived render layer, never destructively baked into the stored base. The
  rendered payload remains in exports for backward compatibility. `sourceUrl` is the attribution
  reference for icons picked from an online FOSS library (informational only — never fed into
  the verdict/lock logic). Loaded into the app list.
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
supported; older optional fields keep their codec defaults. JSON nesting is capped at 64.

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
`data.json`, 64 MiB per other file and 1 GiB total uncompressed. Import also caps the source
ZIP at 1088 MiB. These are transport limits, not a guarantee that every supported file fits
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

The import/undo UI reports saving current state, restoring data, and restoring files instead
of showing an unexplained wait. Recovery needs additional disk space for immutable snapshots and SQLite writes. Capture
checks free space while streaming database rows and before copying files; commit reserves
both snapshot database sizes, incoming JSON, the largest file to restore, and 32 MiB. This
remains an estimate against other apps consuming storage. One retained snapshot can occupy
roughly the saved databases plus gallery and signing files.

Internal database recovery currently requires identical schema versions and columns. Every
future Room schema bump must add compatible recovery handling before shipping: a pending
snapshot from the preceding version must not strand startup behind a schema mismatch.
Both database schemas are preflighted before either database is mutated. A mismatch fails
closed and preserves the snapshot, with the explicit abandon option above. Portable backup DTOs
are not substituted for exact local recovery: they normalize data and do not preserve every ID
or local column. Schema-aware snapshot migrations are still required for automatic recovery
across future updates. Large portable JSON payload memory, byte transfer progress and image/keystore semantic validation remain separate
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
- **`ColorizerShader.kt`** builds the actual `Shader` and is called by BOTH `IconGenerator` and
  the editor preview — a second implementation in the UI would drift from the built output.
- **`ui/ColorStyleSheet.kt`** is the one editor UI: a bottom sheet with a docked live preview
  (rendered through the caller's real generation pipeline, debounced), the mode pill, the stop
  list and the angle dial. `ColorStyleCard` is the row that opens it.
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
stays at v18 because no columns or relational meaning change. Backup
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
- Recovery/Application tests in a shared JVM must call `StorageOperationGate.resetForTests()`
  between isolated scenarios, only after all gate owners/jobs have stopped. Production still
  rejects installing two recovery callbacks.

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
