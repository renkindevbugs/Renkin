# Architecture

A guide for developers (and AI assistants) working on this codebase. The app builds
installable Android icon packs on-device.

## Naming — read this first

Three different names refer to the same app; this trips up newcomers:

| Thing | Value |
| --- | --- |
| App display name | **Renkin** |
| Application id | `com.kaanelloed.iconeration` (kept for F-Droid update continuity) |
| Kotlin package | `dev.alembiconsProject.alembicons` |
| Generated icon pack package | `dev.alembiconsProject.renkinpack` (`IconPackBuilder.PACKAGE_NAME`) |

It is a fork of **Alembicons** (formerly *Iconeration*). The package and app id are
inherited from upstream and are intentionally not renamed.

## Build & run

- JDK: the project targets **Java/Kotlin 17**.
- CLI build: `JAVA_HOME="D:/Android/jbr" ./gradlew compileDebugKotlin`
- Unit tests: `./gradlew testDebugUnitTest`
- Dependencies live in `gradle/libs.versions.toml` (version catalog) + `app/build.gradle.kts`.
  KSP is used for annotation processing (Room + Hilt) — prefer KSP over kapt.

## Layers

```
Composables (ui/)                         ← stateless-ish views, read VM state, call VM funcs
        │  hiltViewModel()
        ▼
MainViewModel / WatchViewModel            ← @HiltViewModel; own session/UI state, orchestrate
        │                                    operations on viewModelScope
        ▼
ApplicationProvider (apk/)                ← orchestrator: owns the app list, build/install,
        │                                    calendar trigger, writes generated icons back
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

## Dependency injection (Hilt)

- `RenkinApplication` is `@HiltAndroidApp`; `MainActivity` is `@AndroidEntryPoint`.
- `di/AppModule` provides app singletons (`WatchRepository`, `ApplicationProvider`).
- `MainViewModel` / `WatchViewModel` are `@HiltViewModel` and inject their dependencies.
- **Services are intentionally NOT on Hilt** — they construct what they need by hand
  (`ApplicationProvider`, `WatchRepository`). This is deliberate: services use their own
  `ApplicationProvider` instance instead of sharing the UI's singleton.
- Repositories take their database in the primary constructor with a `(context)` secondary
  constructor for production. This is what makes them unit-testable with an in-memory DB.

## Threading & state

- Heavy work lives in `suspend` functions that hop to `Dispatchers.Default` internally
  (`withContext`). View models call them from `viewModelScope` (Main) safely.
- `lifecycleScope.launch` is the Main dispatcher — never touch Room / PackageManager from it
  directly; go through a suspend function with `withContext(Default)` inside.
- Loaded lists / flags are Compose `mutableStateOf` so the UI recomposes when they change.

## Persistence

- **DataStore Preferences** (`data/DataPreferences.kt`) — all settings. Typed accessors:
  Composable `DataStore.getXValue()` for reading in composition; `Preferences.getXValue()`
  for reading a captured snapshot off the main thread.
- **Room — `RenkinPackDatabase`** (file `"alchemiconPack"`, kept for data continuity) — the
  generated icons of the last built pack. Loaded into the app list at startup.
- **Room — `WatchDatabase`** — icon-watch rules, suggestions and baselines.

## The build "change bar"

The Options card shows a segmented bar (blue = already built, green = added since last
build, red = removed since). It is a **diff** of the current app list against
`MainViewModel.builtKeys` (the keys saved in the last built pack), not event tracking — so
it stays correct even when icons are added via the Refresh button. `builtKeys` reloads after
each successful build and after "Clear icons".

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
- Downscale list icons to their on-screen size (`toSafeBitmapOrNull(px, px)`, never upscale)
  to avoid scroll jank.
- `LazyColumn`/grid keys must be unique — duplicate keys crash.
