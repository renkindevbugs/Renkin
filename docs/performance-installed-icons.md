# PERF4: installed application icon memory inventory

## Finding

The original launcher-icon catalog exposes **217 distinct Bitmap objects**, with
**130.4 MiB** total `allocationByteCount`, among 218 application
entries. The largest individual bitmap accounts for 6.4 MiB.
This confirms a substantial retained source-pixel owner alongside saved-profile artwork.
It does not establish a memory leak or an exact percentage of process native allocation.

This checkpoint adds passive debug diagnostics, not an icon-loading optimization. Production
source selection, launcher activity identity, fallback handling, badges, adaptive layers,
monochrome, image geometry and persistence remain unchanged. Room remains 21, portable backup
4 and recovery preferences 3. The branch is based on dev `ea4afd0b`, after the PERF3 report merge.

## Collector contract

`InstalledAppCatalog` calls the diagnostic after its existing deduplication and before returning
the same array. `BuildConfig.DEBUG` guards timing and collection. The helper follows public
BitmapDrawable, LayerDrawable and DrawableWrapper references and supported adaptive layers;
it never calls `draw`, `toBitmap`, private cache getters or container `getChildren`. Stateful
containers are counted as opaque rather than initializing inactive children. The inspection
does not change bounds, alpha, filters or source ownership, and does not recycle pixels.

Drawable and bitmap object identity is deduplicated across all catalog entries. Temporary
identity sets are not retained after collection. Traversal is limited to depth 32 and 4,096
drawables; cycles terminate. Recycled bitmaps and read failures are reported instead of reading
invalid allocations. The reporting entry point catches diagnostic failures, and writes only
aggregate JSON to logcat, without labels, components, paths, user IDs or image data.

Counts refer to Java Bitmap objects. Distinct Bitmap objects can alias native storage, while
native/vector/renderer caches and unsupported children can be inaccessible through this API.
`allocationByteCount` sums are therefore an ownership inventory, not a native allocator trace.
Hardware buffers, if present, are reported separately and must not be treated as native-heap
malloc bytes. No heap dump or private icon/profile payload was collected.

## Device evidence, 2026-10-13

OnePlus Nord 4, Android 16 / API 36; debug APK SHA-256:
`975e0b2a91bc08cf8ceb0a85235de0dba0b7692a75cce01cd2672d7dbbf1f61e`.
The installed whole-APK digest is verified separately from the local artifact.

| Inventory metric | Observed value |
| --- | ---: |
| Catalog roots | 218 |
| Distinct exposed drawables | 787 |
| Distinct BitmapDrawable wrappers | 225 |
| Distinct Bitmap objects | 217 |
| Bitmap allocation sum | 136,757,044 bytes (130.4 MiB) |
| Adaptive drawables | 191 |
| Vector drawables | 233 |
| Opaque containers / unknown leaves | 0 / 8 |
| Hardware / recycled bitmaps | 0 / 0 |
| Read failures / truncated branches | 0 / 0 |
| Catalog load / inspection time | 489.5 ms / 1.87 ms |

Only 1 complete inventory record was captured from the startup attempts. Several
other startup and reload attempts had missing diagnostic log lines, including with live logcat
capture. Their absence is not interpreted as zero allocations or a changed catalog. The cause
of the missing log lines has not been established. Further repeatability work may be useful.

At the end of each cold observation, `dumpsys meminfo` reported 260 malloced bitmaps totaling
136,113 KiB (132.9 MiB). The catalog sum of 130.4 MiB is close in magnitude, but the two methods
cover different objects, times and accounting domains and cannot be subtracted to assign every
remaining allocation. There is no pixel-level or package-by-package allocator attribution.

| Three-run cold-start group | Median activity first frame | Median final PSS |
| --- | ---: | ---: |
| Current baseline APK | 1274 ms | 305.9 MiB |
| Instrumented APK | 1331 ms | 303.4 MiB |
| Instrumented APK, live log recording | 1310 ms | 307.9 MiB |

The same original profile stayed active. Samples span about 12 seconds per launch. First-frame
time is not full asynchronous-load completion. Probe cost and background/cache/JIT differences
mean these small groups do not establish a startup-speed regression or improvement.

Two existing Settings → Refresh application list actions preserved the profile and its counts:

| Reload | Sampled maximum PSS | Maximum native allocation | Final PSS after about 8 s |
| --- | ---: | ---: | ---: |
| catalog-reload-1 | 761.4 MiB | 250.7 MiB | 314.9 MiB |
| catalog-reload-2 | 928.0 MiB | 389.4 MiB | 318.0 MiB |

At the maximum-PSS samples, `.apk mmap` alone accounted for approximately 318.2 and 359.8 MiB
PSS, respectively, alongside native and Java heaps. These peaks are not all bitmap memory.
The exact mapped files were not traced. The process returned near its starting memory; this
does not prove that every allocation was freed or that there is no leak. Maxima sampled roughly
once per second are lower bounds, not true peaks. The harness issued no separate GC command,
but ordinary meminfo probes request GC inside Android. These are collection-perturbed observations;
the later [PERF6a control](performance-backup-memory.md) documents this measurement effect.

## Verification and next boundary

Eight targeted tests passed, including six new inventory cases: bitmap sharing, layered/adaptive
and monochrome references without mutation, opaque/unknown drawables without rendering,
recycled pixels, cycles/depth limits and API 23 compatibility. All 1,281 tests in 175 suites
passed with zero failures/errors/skips, including both existing Roborazzi comparisons.
`assembleDebug` and `lintDebug` passed; lint remains at zero errors and 177 warnings.

The combined test/lint/release invocation exhausted the existing 2 GiB Gradle JVM heap inside
R8. A separate unsigned release build passed in a fresh JVM with a command-local 4 GiB limit
(2 min 50 s). The existing 11 Kotlin-metadata warnings remain. No project/CI heap settings,
dependencies or R8 rules were changed to work around that failure. The final release DEX files
contain no diagnostic tag, reporting function name or failure text, and R8 mapping contains no
diagnostic helper/snapshot classes. This confirms removal of the debug-only collector from the
optimized release artifact; release was not installed on the phone.

Before optimizing this owner, preserve full source retrieval separately from small UI previews.
Activity-specific icon resources, user/profile context, fallback icons, adaptive background /
foreground / monochrome, and framework Drawable behavior must survive any deferred-loading
contract. Rasterizing the whole catalog to thumbnails would lose source information used by
generation. A follow-up should measure source acquisition, APK mapping and transient overlap
of old/new catalogs, then test exact generation and lifecycle cancellation before rollout.

The new baseline, controlled catalog-overlap/source-acquisition measurements and proposed
loading boundary are recorded in [PERF5a](performance-installed-icon-loading.md). That checkpoint
does not yet replace production source ownership or claim a memory reduction.

All raw logs, scripts and samples remain local and ignored under
`build/performance/2026-10-13-perf4/`. No temporary profiles were created, no icon Apply/Clear or
pack build occurred, and no original profile data was exported. The original profile's final
home still showed 198 / 218 custom icons, 20 missing and 62 fallback after closing Settings.

Optional device checks before approving this diagnostic checkpoint:

1. Open the main list and compare original application icons with the previous build; optical
   size, colors and badges should be unchanged.
2. Use Refresh application list in Settings; existing saved icons and profile selection should
   remain intact, and generation should still use each selected source normally.
