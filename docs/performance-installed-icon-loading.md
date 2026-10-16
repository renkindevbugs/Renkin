# PERF5a: installed icon source ownership and loading boundary

Date: 2026-10-15. Branch: `feature/installed-icon-memory`, based on `dev` at
`6f7ad1aa`, after the approved APK-export recovery merge.

This checkpoint measures the current implementation and proposes its replacement boundary.
It adds two debug-device measurement methods and this design; production loading is unchanged.
No memory reduction, leak, generation-equivalence result or optimized-release timing is claimed.
PERF4's historical 217 Bitmap objects / 130.4 MiB is an earlier inventory, not an optimization.

The subsequent implementation and its device checkpoint are recorded in
[PERF5b](performance-installed-icon-source-checkpoint.md).

## Verified ownership paths

The codebase-memory graph's catalog callers and generator consumers were checked against current
source. The graph misses some Compose edges, so the UI consumers were checked directly as well.

| Owner / consumer | Current behavior | Required boundary |
| --- | --- | --- |
| `InstalledAppCatalog.getAllInstalledApps` | Starts one Default task per launcher activity; loads labels, English resources, full launcher drawable and resource id before returning | Enumerate source descriptors without retaining full drawable trees |
| `PackageInfoStruct.icon`, `copyWith` | Every edited/locked/calendar copy retains the original Drawable | Copies share immutable source identity, not full source pixels |
| `ApplicationProvider` | Staged/published lists, Undo rows, editor baselines and generation plans can retain those structs | Retained snapshots must stay cheap without changing session, revision or gate checks |
| `reloadPreservingSession` | Keeps the old list for rollback while discovery and saved-profile loading construct its replacement | Preserve rollback and unsaved edits; measure the actual overlap rather than adding two bitmap totals |
| `ApplicationReload.mergeApplicationReload` | Takes fresh system metadata and retained session artwork | Transfer the fresh source descriptor and all required edit metadata explicitly |
| `AppBitmapMemoryCache` | Its 8 MiB limit counts preview values; each key strongly retains a full source Drawable | Keys contain source identity/configuration/size only; budget excludes no hidden source owner |
| `IconGenerator` | Default/full/monochrome/Material You, fallback and vector parsing read the source Drawable | Acquire full artwork and matching resource provenance once per generation operation |
| `IndividualOptions` | Original hero rasterization and adaptive/monochrome checks read `app.icon`; hero conversion currently runs inside `remember` | An editor-owned asynchronous source preparation supplies full semantics and a small hero preview |
| `GlobalOptionsScreen`, `EditorRecoveryPreview` | Original artwork also supplies empty-slot editing and recovery presentation | Route through the same loader; preview consumers own independent small images |

`copyForRendering` can isolate mutable Drawable state while sharing Bitmap backing. This does
not make the retained source pixels small. Conversely, two new drawable trees need not own two
sets of pixels: the controlled overlap below shared Bitmap objects through framework caches.

Two existing identity/provenance limitations matter during migration:

- `PackageInfoStruct` equality and persistent keys identify package/activity only. The catalog
  enumerates Android users, but drops that context after loading and deduplicates component
  collisions. A runtime source descriptor must preserve the winning entry's UserHandle; this
  checkpoint does not change persistent keys or introduce independently stored work-profile rows.
- The loader calls `LauncherActivityInfo.getIcon`, which is unbadged. Android separately exposes
  `getBadgedIcon`. Keep badge policy explicit and independent from the full generation source;
  do not silently change current icon appearance or bake UI badges into generated artwork.
  [LauncherActivityInfo API](https://developer.android.com/reference/android/content/pm/LauncherActivityInfo)
  documents both policies and the activity's user context.

The current reload mapper also omits `modifierRecipe` and `modifierInput` when rebuilding an
unsaved row. That is a source-inspection finding, not a newly reproduced device failure or a
change made here. Source migration must audit this mapper and retain these fields; any independent
behavior repair needs its own regression/device checkpoint rather than being hidden in memory work.

## New device measurements

OnePlus CPH2663, Android 16 / API 36. Debug APK SHA-256:
`3e09acd7cad54629b9060f99dfd8a94a9ffee668f5a2774815c4b073febbf1c4`.
The installed APK matched the local artifact before measurement. Production code was unchanged;
only the helper androidTest APK was installed. This device exposed one accessible Android user,
219 launcher entries, six Renkin profiles and 938 saved icon rows (Room 21, quick_check `ok`).
The earlier PERF4 inventory had 218 entries; these are different observations of the installed
environment, not an attributable before/after comparison.

Three cold debug launches, without instrumentation loaded, produced:

| Metric | Result |
| --- | ---: |
| Activity initial-display time, three runs | 1,160 / 1,278 / 1,234 ms |
| Final PSS, three runs | 327.5 / 327.9 / 331.3 MiB |
| Final malloced-bitmap count / memory | 268 / 152.6 MiB in each run |

Each final sample was about 9.2 seconds after `am start -W` returned. Initial display does not
measure asynchronous catalog/profile readiness. These debug measurements are for owner analysis,
not replacements for the separate release-like Macrobenchmark audit.

The instrumented ownership probe sends aggregate JSON through instrumentation status instead of
depending on the sometimes-missing PERF4 logcat records:

| Inventory | Live catalog | Second catalog | Both retained |
| --- | ---: | ---: | ---: |
| Drawable roots | 219 | 219 | 438 |
| Distinct exposed drawables | 791 | 791 | 1,582 |
| Distinct Bitmap objects | 220 | 220 | 220 |
| Bitmap allocation sum | 149.6 MiB | 149.6 MiB | 149.6 MiB |
| Adaptive drawables / vectors | 192 / 233 | 192 / 233 | 384 / 466 |

The second catalog loaded in 401.7 ms with the first still alive. Both complete trees were
explicitly retained for two seconds and re-inspected afterwards, with the same result. This is
a controlled ownership case, not a measurement of production's precise catalog overlap duration.
No hardware bitmaps, opaque containers, truncation or read failures were reported; eight unknown
leaves per catalog remain outside the bitmap total. Identity deduplication cannot prove unique
native backing, and framework/native caches remain outside this inventory.

Two sequential warm source-acquisition passes each requested every entry via the existing
`LauncherActivityInfo.getIcon` path, without retaining their returned drawables as a new catalog:

| Metric | Pass 1 | Pass 2 |
| --- | ---: | ---: |
| Acquisitions / failures | 219 / 0 | 219 / 0 |
| Sum of acquisition times | 177.9 ms | 170.4 ms |
| Per-source p50 / p95 | 0.834 / 1.006 ms | 0.794 / 0.978 ms |
| Maximum single acquisition | 1.618 ms | 1.366 ms |
| Wall time, including passive inspections | 184.9 ms | 177.1 ms |

Framework caches were already warm and the original catalog still retained its sources.
These timings do not establish cold deferred-loading cost, Bitmap release behavior, preview
rasterization time or generation/export latency. Per-read allocation sums are not deduplicated
between acquisitions and must not be presented as newly allocated bytes.

The other probe invokes the production `MainViewModel.refreshApps` path twice, with no old
catalog deliberately retained by the test. It checks the owning profile and application/generated
icon counts after each completed aggregate arrives:

| Metric | Reload 1 | Reload 2 |
| --- | ---: | ---: |
| Request to ready runtime + matching completed application snapshot | 3,146 ms | 1,932 ms |
| Sampled maximum PSS | 610.4 MiB | 715.5 MiB |
| APK mmap PSS at that maximum-PSS sample | 103.0 MiB | 161.6 MiB |
| Sampled maximum native allocation | 262.4 MiB | 375.9 MiB |
| Sampled maximum malloced-bitmap memory | 208.6 MiB | 362.0 MiB |
| Last valid PSS after settling | 415.7 MiB | 419.5 MiB |

The native/bitmap maxima are independent samples, not components to add to the maximum PSS.
Instrumentation adds significant runtime/DEX cost, so its settled PSS cannot be compared directly
with the uninstrumented cold group. Meminfo probes cost 94–344 ms, followed by 300 ms pauses;
observed maxima are lower bounds. Readiness is not diff-baseline completion. The harness issued
no separate GC command, but ordinary meminfo probes request GC inside Android. See the later
[PERF6a control](performance-backup-memory.md); these historical results are not unsampled peaks.
These reloads also rediscover icon packs and decode saved rows, so their peaks cannot be assigned
solely to original icons. Exact mapped APK files and native allocation stacks were not traced.

## Proposed implementation contract

1. Introduce an immutable `InstalledIconSource` descriptor containing the exact ComponentName,
   Android user, catalog/package invalidation revision, density and effective resource configuration.
   Keep Renkin's profile token separate from the Android user. Do not persist UserHandle or put
   Drawables, Resources, bitmap-holding closures or a `lazy` full-source cache into each descriptor.
   Catalog discovery preserves label ordering, component deduplication and fallback metadata.
2. An injected `InstalledIconLoader` resolves that component in the captured user through
   LauncherApps, preserving activity-specific selection. Its full-source result pairs the drawable
   with the resource id, matching ApplicationInfo/resources and actual fallback provenance.
   Parsing must consume that pair rather than combining an activity drawable with another user's
   package-only resource lookup. API 31 activity metadata and the older-API fallback need separate
   coverage. [LauncherApps.resolveActivity](https://developer.android.com/reference/android/content/pm/LauncherApps#resolveActivity(android.content.Intent,%20android.os.UserHandle))
   provides component/user resolution; inaccessible or removed users must never redirect to the
   personal-profile app. Keep existing application-icon and invalid-dimension fallbacks explicit.
3. UI requests target-sized previews on a worker dispatcher. A shared byte-bounded LRU stores
   only independent preview bitmaps; keys contain descriptor revision, user, component, resource
   configuration, density, requested dimensions and badge policy. Coalesce matching in-flight
   requests and initially limit source acquisitions to two concurrent operations, then measure.
   A framework getIcon call may still transiently decode full pixels before the small render;
   target-sized output alone does not establish bounded native peak memory.
4. Generation acquires a full source only when its selected or fallback path needs it, outside
   storage/profile gates. One operation reuses that source for regular/full/monochrome/Material You,
   vector parsing and fallback, with existing rendering copies. Preserve background/foreground
   insets, adaptive mask geometry, monochrome and framework wrappers. Never generate from a
   thumbnail. Pack/text/upload paths should not load an unused application source eagerly.
5. An individual editor prepares one source asynchronously and holds it for its live lifetime,
   including configuration recreation. Capture completed immutable source artwork at the existing
   stage boundary. Apply, Undo, replay and durable recovery consume their reviewed/captured artwork;
   an app update, missing package or resumed process must not silently regenerate a restored draft
   from a newer icon. Recovered-original previews remain independent of disposable bound pixels.
6. Cancellation drops unpublished work and wakes shared-request waiters safely. Package/profile/
   configuration changes invalidate preview keys and reject stale publication. Operation/editor
   owners release their full-source references on every exit without recycling framework-owned or
   visible pixels. Revalidate session, render tickets and storage epochs at the existing commit
   boundaries; do not await UI flows under gates or change long-operation/completion ownership.

The first implementation should migrate the catalog, row copies/reload mapper, preview keys,
generator/fallback parser and editor/recovery consumers together through this explicit contract.
Replacing `icon` with a transparent lazy getter would leave hidden full-source retention and
main-thread loading, and would not complete the boundary. Keep the rollout independently reviewable
from backup-memory work, installer behavior, CI and prefsSnapshot retirement. Room/portable backup/
recovery preference versions remain 21/4/3.

## Acceptance and safety checkpoint

Before accepting the optimization, compare cold startup/first scroll, settled Java/native/PSS,
source acquisition and real reload peaks against this same APK/data/environment. Confirm that
old preview cache keys no longer retain source drawables, while reporting framework cache limits.
Do not promise a 149.6 MiB saving before measuring the new implementation.

Regression/device coverage must include activity-specific icons, same component in another Android
user, badge separation, inaccessible/removed work profiles, package updates/configuration changes,
application fallback/invalid dimensions, vector/adaptive/inset/monochrome sources, all app-icon
variants, pack fallback, exact generation pixels/resources, Apply/Undo, retained-input replay,
autosave/process recovery and cancellation during source loading or profile/catalog replacement.
The current phone had one accessible user; work-profile and fallback failures were not exercised.
No Apply, Undo, editor restoration or pack generation/export was performed in this measurement.

Fresh verification here: `compileDebugKotlin`, `assembleDebug`, `assembleDebugAndroidTest`, and
both final `InstalledIconMemoryDeviceTest` methods passed. The production APK stayed unchanged.
The APK-export fix's historical 1,371-unit-test result was not rerun or relabeled as new validation.

The device-test procedure backed up databases/files/shared_prefs/no_backup before launch, including
unfinished-editor data. All 50 regular files were restored byte-for-byte with matching mode, uid,
gid and mtime; Room/profile/icon counts and quick_check matched. The helper test APK was removed.
The restored debug home opened with no crash for its new PID, then the backup was restored again
to remove launch side effects. Temporary private backup/APK/database copies were removed after
verification. Release Renkin and installed icon packs were not operated or modified.
Raw aggregate status/meminfo records and host scripts stay in the session scratchpad's `perf5/`.

Manual checks when reviewing this measurement checkpoint:

1. Open debug Renkin. The original active profile, saved icons and any unfinished draft should
   still be present; no temporary profile or generated pack should have appeared.
2. Refresh the application list from Settings. Profile selection and icon counts should settle
   as before; original icons should keep their current appearance.

The developer approved this measurement/design checkpoint for commit and merge. The source-loading
optimization itself is the next implementation checkpoint, not a completed change in this report.
