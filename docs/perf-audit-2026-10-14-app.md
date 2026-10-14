# Compose Performance Audit — 2026-10-14 — app

Run with the `auditing-compose-performance` skill: Measure → Diagnose → Fix → Verify.

## Environment
- Compose BOM 2025.10.00 (Material 3 1.5.0-alpha14)
- Kotlin / Compose Compiler 2.1.20, Strong Skipping on
- AGP 8.9.0
- Device: OnePlus Nord 4 (CPH2663), Android 16, 120 Hz (8.33 ms frame budget)

## Measurement setup
`:macrobenchmark` drives the release-like `benchmark` build type of `:app` (release code and R8,
application id `dev.renkinProject.renkin.benchmark`, debug key, `profileable`), so it never
replaces an installed release build:
`./gradlew -Prenkin.unsignedRelease=true :macrobenchmark:connectedBenchmarkAndroidTest`.
The app starts on a fresh install (219 installed apps, original icons, first-run tour skipped).
Launches go through `am start -W`: Macrobenchmark's `startActivityAndWait()` confirms launches
through gfxinfo frame stats, which this OnePlus build never reports.

## Baseline (Phase 1)
| Scenario | Just installed (`CompilationMode.None`) | Fully compiled (`Full`) |
| --- | ---: | ---: |
| Cold start, time to initial display, median of 10 | 353.0 ms | 350.7 ms |
| App-list flings, frameDurationCpuMs P50 / P90 / P99 | 7.0 / 10.5 / 15.6 ms | 6.8 / 10.2 / 13.7 ms |
| App-list flings, frameOverrunMs P99 | 4.0 ms | 2.4 ms |

Baseline Profile present: no. ART profile tasks are disabled in `app/build.gradle.kts`
(`5d3b9299`) because `assets/dexopt/baseline.prof` broke F-Droid reproducible builds. A profile
generated during this audit could therefore not ship; ahead-of-time compilation changed cold
start by about 2 ms, so the expected gain is small anyway.

## Diagnosis (Phase 2)
Compose Compiler reports for the R8 variant (`-Prenkin.composeReports=true`,
`app/build/compose_compiler/`):
- Restartable-not-skippable named composables: 0 of 279 (Strong Skipping); the 61 others are
  value-returning, non-restartable helpers.
- Unstable classes: 301 of 486 (mostly domain and drawable types). Under Strong Skipping they are
  compared by instance, which the app-list rows already keep stable.
- App-list row (`ApplicationItem`): stable `key` and `contentType`, icons decoded off the main
  thread at their 56 dp size and cached. The only per-recomposition allocation is a
  `BitmapPainter` wrapper, too small to show in frame timing.
- Phase-misplaced reads: none found on the scroll path.

## Fixes applied (Phase 3)
None. No measured hotspot justified a change; the skill's rule is to fix measured causes, not
compiler-report counts.

## Verification (Phase 4)
- Baseline Profile regenerated: not applicable (disabled for F-Droid reproducibility).
- CI stability gate: not added; it needs an extra Gradle plugin and nothing regressed.

## Open items
- Re-run the two benchmarks before releases that touch the main list or startup.
- Revisit Baseline Profiles only if F-Droid confirms that current AGP produces a deterministic
  `baseline.prof`.
