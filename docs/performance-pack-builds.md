# PERF3: generated and stored raster pack builds

## Scope and decision

This checkpoint verifies on-device pack building after the compressed raster ownership change
merged into dev at `f120b747` (implementation `df43c05f`). It changes no application code,
dependencies or data formats. All measurements use the same debug APK on a OnePlus Nord 4,
Android 16 / API 36. The locally built and installed APK SHA-256 was verified to match:
`a0ddcf6fd1248167a4e532e1a67f82a52dfdf35a9ae2ab81fd7a967d36558249`.

The controlled workload built successfully both before and after reloading its saved PNGs.
All 137 exported WebP resources have identical SHA-256 digests in the eager build and all
three stored-source builds. This checks encoded export bytes, including the 124 application
icons and supporting raster resources, rather than just the small UI preview. The measured
builds do not justify another build-pipeline optimization at this point.

## Workload and method

An empty `PerfBuildTEMP` profile selected the installed Crayon pack. The picker reported 125
matches; actual generation produced 124 custom icons among 218 applications, with 94 missing.
No fallback source was added. The first build used those live generated bitmaps. Its installer
prompt was dismissed without installation. The debug process was force-stopped and restarted,
then the same saved profile was built three times from compressed raster backing.

Each observation spans approximately 18 seconds. `dumpsys meminfo` probes run roughly once per
second, with a 0.75 s pause after each probe. A separate loop checks the new signed APK's ZIP
directory every 0.4 s. Reported time ends when a complete ZIP directory is observed, including
ADB/polling overhead; it is not an exact CPU duration or a time to finish installation. The
installer selection prompt was present after each build. Maxima are sampled lower bounds.

| Build | Complete archive observed | Maximum PSS | Maximum native allocation | Maximum Java allocation | Final PSS |
| --- | ---: | ---: | ---: | ---: | ---: |
| eager-build | 2.203 s | 408.4 MiB | 255.7 MiB | 22.6 MiB | 324.7 MiB |
| stored-build-1 | 2.781 s | 408.3 MiB | 229.4 MiB | 26.3 MiB | 312.2 MiB |
| stored-build-2 | 1.812 s | 423.4 MiB | 231.5 MiB | 27.6 MiB | 317.4 MiB |
| stored-build-3 | 1.953 s | 429.5 MiB | 236.0 MiB | 28.5 MiB | 317.8 MiB |

Every build produced a 2,141,827-byte APK (approximately 2.04 MiB), with 156 ZIP entries.
The metadata and SHA-256 computation ran inside the phone's app-private cache. Only metadata
and digests were collected; no artwork, APK, database, private profile or keystore payload was
copied to the computer. No generated pack was installed. Signature verification and launcher
integration are outside this checkpoint; ZIP completion and an installer prompt are not a
cryptographic APK-signature check.

The force-stopped saved-profile startup reached 386.8 MiB sampled maximum PSS and settled at
299.2 MiB after approximately 12 seconds; its first activity frame took 1,355 ms. Cold startup
and eager generation have different caches and JIT state, so this is not an isolated timing
benchmark of PNG decoding. Repeated stored builds retain bounded preview/native working data
in this scenario, but three short runs cannot establish absence of a memory leak.

## Limits and next measurement

This is a 124-icon controlled workload, not a clone or stress build of the existing 224-row
largest profile. An attempted export of that private profile into shared phone storage was
rejected by automatic approval review before Save because that destination was not explicitly
authorized for its sensitive payload. The file picker was cancelled and no alternative export
or private-payload transfer was attempted. The controlled profile was created from scratch.
Full portable backup/profile-export memory remains unmeasured in this checkpoint.

No pre-PERF2 APK build baseline was recorded. Consequently these figures compare eager and
stored sources within the current implementation; they do not establish a pre/post code
speedup. PSS, Java and native allocation are different metrics and must not be added together.
Debug builds, background activity and probe cost also limit conclusions about release speed.

The next memory investigation should attribute the remaining application-catalog/native pixel
ownership before changing activity icons, work-profile badges, adaptive/monochrome layers or
cache lifetimes. The PNG ownership change does not cover newly generated bulk artwork,
installed-app drawables, backup JSON or every editor intermediate.

Raw samples, timing scripts, APK metadata and digest files remain gitignored in
`build/performance/2026-10-13-perf3/`. Original-profile icon state was never Applied, Cleared or
built by this checkpoint. ADB disconnected after the last build and digest verification;
cleanup completed after the phone reconnected. The original active profile was restored,
`PerfBuildTEMP` was deleted through its named confirmation, and all six original profiles
were verified in the profile menu. The last test APK and its completion marker were removed
from their own app-private cache directory. No other cache entries or installed packages were
removed. The final installed-APK hash check also passed after reconnection.
