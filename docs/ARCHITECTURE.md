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
        │                                    build/install, writes generated icons back
        ├── InstalledAppCatalog            ← launcher activities, labels and application icons
        ├── IconPackRepository             ← installed packs, app-filter elements, per-app
        │                                    drawables, calendar icons (Compose-state backed)
        ├── IconGenerationService          ← runs IconGenerator to produce icons
        └── RenkinPackStore                ← persistence: DbApplication ↔ drawable serialization
                └── RenkinPackRepository    ← raw Room I/O on RenkinPackDatabase

WatchRepository (data/watch/)             ← icon-watch rules/suggestions on WatchDatabase
```

The UI layer never constructs repositories by hand; view models receive them through
Hilt. `getCurrentMainActivity()` is still used in a couple of places, but only for genuine
Activity operations (`finish()`, starting services, permission requests).

## Activities

- **MainActivity** — the whole app UI; provides `LocalMainActivity` and `LocalToaster`.
- **GlobalOptionsActivity** — wallpaper-backed Global options grid. It uses a lightweight
  view model over the shared provider and performs guarded provider initialization when Android
  recreates it in a cold process without MainActivity/MainViewModel.
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
- Global-options modifier previews use ViewModel-scoped, shared jobs and a byte-bounded bitmap
  LRU. Lazy-grid disposal therefore does not discard an in-flight/current preview, while a new
  modifier configuration cancels obsolete work. Cache misses drive the shared `WavyLoadingBar`.

## Persistence

- **DataStore Preferences** (`data/DataPreferences.kt`) — all settings. Typed accessors:
  Composable `DataStore.getXValue()` for reading in composition; `Preferences.getXValue()`
  for reading a captured snapshot off the main thread.
- **Room — `RenkinPackDatabase`** (file `"renkinPack"`, v15) — profiles + rendered and base icons
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
- **Room — `WatchDatabase`** (v3) — icon-watch rules, suggestions and per-rule baselines, owned per
  profile via `WatchRule.profileId`.

## UI conventions

- **Fullscreen screens** (Settings, Crash logs, Watched icons) are fullscreen dialogs with
  the same M3 `TopAppBar`: back arrow, primary-tinted title, actions on the right.
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
  gradient type/angle, 2–4 stops (`MIN_GRADIENT_STOPS`/`MAX_GRADIENT_STOPS`), plus the Solid
  fill / Monochrome / Inverse flags. Those flags apply to gradients too: solid fill replaces the
  artwork's RGB through its alpha, otherwise the gradient multiplies with it.
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
- **Wide screens**: `WIDE_LAYOUT_DP` (600) switches the sheet and the segment picker to
  side-by-side panes. Same breakpoint idea as `WatchRuleEditor`, but orientation-independent so
  a tablet held upright benefits too.
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

`IconPackBuilder` assembles the APK with reandroid (no external build tools). The generated
pack's dex classes come from prebuilt smali assets (`app/src/main/assets/{R,RLayout,
MainActivity,BuildConfig}`); the pack's applicationId is parameterized per profile, but the
smali class package must match IconPackBuilder's activity FQN string. Signing uses
`renkinpack.keystore` in filesDir. Each launcher activity of a package gets its own drawable
file name (a package with several activities must not overwrite one icon with the other).
Packs identify apps by `ComponentInfo` in appfilter.xml — never by name.

## Testing

- Pure JVM unit tests for pure functions (`DataPreferencesTest`, `ColorHexTest`).
- **Robolectric** tests for the data layer with an in-memory Room database
  (`WatchRepositoryTest`, `RenkinPackRepositoryTest`). They use a plain `Application` and a
  pinned `@Config(sdk = [33])` to stay off Hilt and the very new `compileSdk`.

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
