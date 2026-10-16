# PERF5b: deferred installed application icon sources

Date: 2026-10-15. Branch: `feature/installed-icon-source-loading`, based on `dev`
at `0f97a0c8`, after the approved PERF5a measurement/design merge.
The developer approved commit and merge. Implementation `2327e89f` was merged into `dev`
by `99c9655b`. Remaining coverage limitations are listed below.

## Implemented ownership boundary

`InstalledAppCatalog` now enumerates labels and `ApplicationIconSource.Installed` descriptors.
Each descriptor retains its launcher component, Android user, APK/split paths, catalog revision
and a defensive resource-configuration snapshot. It retains no Drawable, Resources or Bitmap.
`PackageInfoStruct` copies and recovered-editor reconstruction carry that descriptor. Persistent
package/activity keys and the existing first-entry deduplication policy remain unchanged.
Android users are not Renkin profiles and no UserHandle is written to persistence.

`InstalledIconLoader` resolves the captured component in its captured user and rejects a changed
APK/split path or an unavailable activity. It preserves the existing unbadged `getIcon` policy,
application-icon fallback after failed activity-icon loading, and invalid-dimension placeholder.
Catalog identities follow the existing explicit application-list reload policy; this phase does
not add package/configuration observers. Cached previews can retain the old catalog presentation
until reload, while new full-source requests reject changed APK paths. In-flight native resource
reads are not an atomic package-update snapshot.
API 31+ uses the launcher's ActivityInfo; older APIs retain a metadata fallback. Full results pair
the actual drawable with resource id, ApplicationInfo and configuration. Vector parsing consumes
that pair rather than resolving a same-name package in another user. Older work-profile metadata
fallbacks have synthetic metadata coverage; a real work-profile device remains unvalidated.

The shared 8 MiB preview LRU now lives in `packages`. Its keys contain a source-identity token
and dimensions, never the source Drawable or descriptor. Independent image-loader instances share
this cache without sharing an Activity context. Matching in-flight requests coalesce; a surviving
waiter retries when its first owner is cancelled. Two source acquisitions and two preview renders
can run concurrently. Native/framework calls remain synchronous on worker threads; cancellation
rejects publication at coroutine boundaries rather than promising interruption of native decoding.
Cache eviction and cleanup never recycle pixels still owned by a visible consumer.

The generator acquires full source artwork only in an application-icon or applicable fallback
path. Primary/secondary/fallback attempts reuse one operation-owned result; finally releases its
reference after each application, including failure. Pack/text paths do not eagerly acquire an
unused original icon. Generated artwork and retained modifier-stage inputs keep their existing
full-quality ownership; no generation path reads the thumbnail cache.

`EditorSessionViewModel` prepares one full source, adaptive/monochrome capabilities, brand colours
and an aspect-preserving hero image capped at 512 px off Main. Its job and prepared source survive
configuration recreation with the same editor owner. Replacement, explicit release and clearing
cancel the job; obsolete work cannot attach to another editor. Generation uses that prepared source
while baseline validation still uses the original provider row. Global editor and recovery-original
previews also acquire sources off Main. Restored Create stages, replay and durable artwork remain
captured inputs; the current installed app is not substituted into an untouched recovered render.

The reload mapper now explicitly transfers `modifierRecipe` and `modifierInput` with preserved
unsaved artwork, fixing the omission identified in PERF5a. Fresh system source metadata comes from
the replacement catalog. Session, storage-epoch, render-ticket, Apply, Undo and build/install gates
remain in their existing owners. Room/portable-backup/recovery-prefs versions remain 21/4/3;
no format, dependency, installer policy, string or Compose layout change is included.

## Current device comparison

OnePlus CPH2663, Android 16 / API 36, one accessible Android user, 219 launcher entries,
six Renkin profiles and 938 saved icon rows. Original data was backed up before any launch/install.
The pre-test installed debug APK was the baseline; its testOnly flag required `adb install -t`
when temporarily reinstalling it for the scroll control. The candidate's installed SHA-256 was
verified against the local `assembleDebug` artifact.

- Baseline APK: `c438f8eac3c8b3846db16f6c5d6c0ff82277ddf7ff80d76c92661d6271198fd5`.
- Final candidate APK: `68aadc9ac8ba9542b4165c29c8d9815a5e03b6e5a0d7b24bc1806346e808aead`.

Three uninstrumented cold observations per APK, with final samples approximately nine seconds
after `am start -W` returned:

| Metric | Baseline | Candidate |
| --- | ---: | ---: |
| Median final PSS | 329.2 MiB | 169.7 MiB |
| Median final native allocation | 199.0 MiB | 31.1 MiB |
| Median final Java allocation | 18.6 MiB | 18.0 MiB |
| Final malloced-bitmap memory | 152.6 MiB | 2.1 MiB |
| Final malloced-bitmap count | 268 | 40 |
| Initial-display times | 1,124 / 1,253 / 1,259 ms | 1,202 / 1,224 / 1,218 ms |

PSS, native/Java allocation and malloced-bitmap memory are separate accounting domains. Do not
add them or assign every byte of the PSS difference to original icons. Initial display is not
full asynchronous readiness. These small debug groups establish the observed memory difference,
not a startup-speed improvement or optimized-release result. The harness issued no separate GC
command, but ordinary meminfo probes request GC inside Android. The later
[PERF6a control](performance-backup-memory.md) confirms that effect. These historical observations
remain valid for their sampled setup; they do not establish natural-collection memory peaks.

Both simultaneously retained catalogs contained 219 descriptors and **zero resident source
Drawables/Bitmaps**. The inventory explicitly reports `retained-source-drawables`; zero does not
describe the whole process or its thumbnail/framework caches. Acquiring all full sources still
exposed 220 per-read Bitmap objects totaling 149.6 MiB in each sequential pass. Those sums are
not deduplicated across reads and must not be called newly allocated bytes.

| Actual deferred loader, sequential acquisition | Pass 1 | Pass 2 |
| --- | ---: | ---: |
| Sources / failures | 219 / 0 | 219 / 0 |
| Sum of acquisition times | 1,860.8 ms | 1,832.2 ms |
| Per-source p50 / p95 | 6.840 / 13.037 ms | 6.811 / 17.549 ms |
| Maximum single acquisition | 101.447 ms | 47.976 ms |

The loader includes component resolution, version/path validation and resource metadata as well
as getIcon. The catalog no longer pins every decoded bitmap, so even the second pass must not be
called fully warm. PERF5a's historical approximately 1 ms p95 measured getIcon alone with an eager
catalog keeping all sources alive. This is a real memory/decoding tradeoff, not an acquisition
speedup. Second descriptor discovery took 391.4 ms; label/resource mapping costs remain.

Two production `MainViewModel.refreshApps` observations used an external meminfo sampler:

| Candidate reload metric | Reload 1 | Reload 2 |
| --- | ---: | ---: |
| Request to ready runtime + matching completed snapshot | 2,206 ms | 1,948 ms |
| Sampled maximum PSS | 452.9 MiB | 405.1 MiB |
| APK mmap PSS at the maximum-PSS sample | 98.6 MiB | 121.7 MiB |
| Independently sampled maximum native allocation | 87.3 MiB | 76.4 MiB |
| Independently sampled maximum malloced-bitmap memory | 25.2 MiB | 76.9 MiB |
| Last valid settled PSS | 263.5 MiB | 261.4 MiB |

The sampler pauses 300 ms between probes; maxima are lower bounds. Instrumentation adds its
own code/runtime cost, so settled instrumented PSS cannot be compared directly with the cold
table. Reload includes pack discovery and saved-row loading, not only original icons. APK
mappings remain substantial and exact mapped files/native allocation stacks were not traced.
PERF5a's older reload numbers are historical evidence, not a fresh paired baseline here.

Eight first list swipes per APK, without simultaneous memory sampling, produced:

| Debug gfxinfo metric | Baseline | Candidate |
| --- | ---: | ---: |
| Modern janky frames | 74 / 374 (19.79%) | 76 / 386 (19.69%) |
| p95 / p99 | 34 / 73 ms | 34 / 61 ms |

The original data was restored before temporarily installing the baseline control, and the final
candidate was returned afterwards. One scroll run per APK cannot establish a speedup or rule out
all regressions. The separate release-like Macrobenchmark remains the relevant production gate.
The debug PID survived both runs, with no crash for its PID.

## Verification and limits

`compileDebugKotlin`, `assembleDebug` and `assembleDebugAndroidTest` passed for the final production
code. Four final device methods passed:

1. Production reload twice: owning profile, application count and created-icon count stayed intact.
2. Descriptor overlap and actual deferred acquisition: both catalogs stayed source-pixel-free and
   all 219 sources remained retrievable.
3. Independent eager reference from LauncherApps.getActivityList versus deferred resolution:
   all 219 sources matched class/dimensions and 500 px presentation; 527 adaptive foreground,
   background and monochrome layers matched. Four available source kinds produced 48 identical
   serialized outputs for Default/Monochrome/Material You, full-icon and vector option combinations.
   This compares two source routes through the same renderer, not an old-binary APK build.
4. Actual editor clicks on App Icon, Monochrome and Material You published completed artwork
   matching their reviewed generation requests. Apply/Discard were not pressed.

Initial probe builds and an accessibility timing failure were corrected before the final passing
runs. The eager reference deliberately uses the original getActivityList path, not the same
resolveActivity call as the new loader. Tests log aggregate results and generic mismatch messages,
never serialized artwork. No icon pack was built or installed.

The existing unfinished draft was postponed with Not now and blocked recovery autosaves for the
temporary editor as expected. Its files were not discarded or replaced. After testing, all 50
original regular files in databases/files/shared_prefs/no_backup matched bytes, mode, uid, gid and
mtime. Room remained 21, with six profiles, 938 rows and quick_check `ok`. The helper test APK was
removed. The restored debug app opened without a crash, then its data was restored again to remove
launch side effects. Temporary private backup/APK/database/UI copies were deleted after verification.
The final candidate remains installed for developer checks; release Renkin and installed packs were
not operated or modified. Aggregate logs/scripts remain in the session scratchpad's `perf5b/`.

On 2026-10-16, targeted ownership/cache/reload tests and the complete unit suite passed on `dev`
`75c86f5b` with a test-only synchronization correction: 1,382 tests in 189 suites, zero
failures/errors/skips. `assembleDebug` also passed. The cancellation fixture previously blocked
the Robolectric test thread while waiting up to five seconds for the first native draw. It now
suspends for the draw-entry handshake before cancelling either owner or waiter. The production
loader is unchanged. Synthetic cases cover owner cancellation with waiter retry, independent waiter
cancellation, configuration copying, prepared-editor ownership, pre-31 activity/work-user metadata
and modifier-input preservation on reload. No historical suite result is claimed as new.
Lint and optimized-release verification were not repeated. Actual work-profile devices,
unavailable/updated packages, Apply/Undo persistence, retained-input replay and real process death
were not exercised by these device probes. Synthetic metadata/cancellation cases do not replace
those device scenarios or prove absence of every concurrency race.

## Manual developer checkpoint

1. Open debug Renkin and scroll the list. Original icons, optical sizes, colours and existing badge
   presentation should remain unchanged; loading placeholders should settle without stuck blanks.
2. On an icon other than the existing saved draft, select App Icon and exercise Default/full icon,
   Monochrome and Material You. Change Scale/Outline, Apply, then Undo. The applied artwork should
   match New, and Undo should restore the original artwork and dirty state.
3. Reopen a confirmed editable icon and Restore saved modifiers. Its full source, controls and
   replay should survive app-list reload and normal profile Save/Discard switching as before.
4. Resume the existing unfinished edit without reselecting its source. Artwork and controls should
   match its saved state; configuration recreation should retain the same editor and source.

The developer approved commit and merge after this checkpoint. The next performance phase is
safe measurement of backup/export memory; no backup-memory improvement is claimed here.
