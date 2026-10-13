# CI and release verification

CI1 adds verification on `feature/ci-release-verification`. The branch now includes dev at
a0923f46: verified CI1a API/lint fixes, adaptive rendering and replay fixes, Match app color,
and chunked database reads for oversized icon rows. CI1 itself changes only build/workflow
configuration and documentation; existing tests/render references are unchanged.

## Integrated verification

On 2026-10-13, compileDebugKotlin, testDebugUnitTest, lintDebug and assembleDebug passed together
in 6m 53s against a0923f46 plus CI1. Fresh JUnit reports contain 1,270 tests in 173 suites, zero
failures, errors or skips, including both explicit Roborazzi reference comparisons. No reference
images were recorded. Lint reports zero errors, 174 warnings and seven informational findings;
issue ids/messages match the verified CI1a report exactly.

Unsigned assembleRelease passed in 4m 57s, including R8, resource shrinking and release-vital
lint. The actual vital report says "No issues found" and is stored under
app/build/intermediates/lint_vital_intermediate_text_report/release/lintVitalReportRelease/;
the workflow uploads this path as well as any conventional reports. R8 generated mapping,
usage, seeds, configuration and resource diagnostics, with the same 11 Kotlin metadata warnings
as the original baseline. No suppressions or shrinker changes were added.

Ordinary signed assembleRelease then passed in five seconds, reusing the unchanged, verified
R8 output and rebuilding APK packaging with the normal signing configuration. apksigner verifies
v1 and v2 signatures. The preserved unsigned APK contains the expected manifest, dex and resource
entries and is rejected for its missing signing manifest. The verifier also reports META-INF
entry warnings for the signed APK; verification succeeds with both signature schemes. No signing
credentials were printed or copied. APK sizes are 9,707,512 bytes unsigned and 9,780,350 signed.

The historical results below describe the original b138de24 baseline. CI1a separately passed
1,240 tests before the later application fixes were merged. No push, GitHub run, installation
or optimized-app execution was performed during the integrated local verification.

Integrated logs: build/verify-ci1-integrated-debug.log, build/verify-ci1-integrated-release-{unsigned,signed}.log
and build/verify-ci1-integrated-{unsigned,signed}-signature.log. Structured summaries are in
build/verify-ci1-integrated-{debug,release}-summary.json. The unsigned APK is preserved at
build/verify-ci1-integrated-unsigned.apk; the normal release output contains the signed APK.
The local JBR 21 test-JVM init script remains ignored and is not used by the Linux/JDK 17 workflow.

## Historical verification before CI1a

- compileDebugKotlin and assembleDebug passed. The combined debug command failed at lintDebug:
  17 errors, 174 warnings and seven informational findings. Errors: 13 NewApi, two
  MissingPermission, one ResourceType and one SuspiciousIndentation. No baseline or suppression
  was added to the CI branch to hide them.
- A fresh testDebugUnitTest --rerun completed: 1,225 tests across 169 suites, zero failures,
  errors or skips, including both Roborazzi comparisons. The initial combined command reused
  the previous test output, so the separate rerun supplies this checkpoint's fresh evidence.
- assembleRelease with the explicit unsigned property passed, including R8/resource shrinking
  and lintVitalRelease. apksigner rejects that output as unsigned, as intended.
- Plain assembleRelease without the property passed with the existing local signing setup;
  apksigner verifies v1 and v2 signatures. No signing credentials were printed or copied.
- R8 emitted Kotlin metadata parsing warnings. These are recorded as an unresolved toolchain
  follow-up, not suppressed or proof of optimized-runtime correctness. Mapping/usage/resource
  diagnostics were generated. Existing NonCancellable launch deprecation warnings also remain.
- No GitHub run, push, installation or optimized-app device execution was performed at that
  checkpoint. The original lint failure was subsequently fixed in CI1a (0e272048), merged into
  dev before the application fixes listed above.

Local logs: build/verify-ci1-{debug,tests,release-unsigned,release-signed}.log and
build/verify-ci1-{unsigned,signed}-signature.log. Lint reports are under app/build/reports/
lint-results-debug.*; R8 diagnostics are under app/build/outputs/mapping/release/. These
machine-local outputs are ignored. The unsigned APK is preserved at build/verify-ci1-unsigned.apk;
the usual release output now contains the separately verified signed APK.

## Checks

The [workflow](../.github/workflows/ci.yml) runs on pushes/PRs targeting dev/main and supports
manual dispatch. Two independent jobs use Linux with Temurin 17 and recursive submodule checkout:

| Job | Command | Scope |
| --- | --- | --- |
| Debug, tests and lint | `./gradlew compileDebugKotlin testDebugUnitTest lintDebug assembleDebug --continue --stacktrace` | Kotlin/Hilt/Room compilation, full unit suite including explicit Roborazzi comparisons, Android lint and debug APK packaging. `--continue` allows independent tasks to finish after a failure; the job still fails. |
| Unsigned release with R8 | `./gradlew assembleRelease -Prenkin.unsignedRelease=true --stacktrace` | Actual release variant with the existing minification/resource shrinking and ProGuard rules, plus release-vital lint from AGP. No signing credentials are required. |

Lint errors fail with the default Android lint policy. No baseline, broad suppression,
`abortOnError=false`, `continue-on-error` or R8 warning suppression is added. Existing warnings
are not promoted wholesale to errors in this checkpoint. Dependency/toolchain versions remain
unchanged. CI does not use the local JBR 21 test-JVM C2 workaround; its JDK is 17.

Reports are uploaded even after Gradle failure: test HTML/XML, lint HTML/XML, Roborazzi reports
and image comparisons, or release R8 mapping/diagnostics and the internal release-vital text
report when generated. Missing diagnostic
files produce an artifact warning, not a substitute for successful verification. Successful
jobs also upload their APK; a missing APK fails that artifact step. Artifacts expire after
seven days. The workflow token has only repository-content read permission, checkout does not
persist credentials, and newer runs cancel superseded runs for the same ref.

## Signing and local reproduction

`-Prenkin.unsignedRelease=true` skips the build script's loading of `local.properties` for signing
and leaves the release signing configuration absent, even on a configured developer machine.
AGP can still read its normal SDK-location settings; this option does not change SDK discovery.
The property is an explicit boolean: malformed values fail configuration. With the property
absent/false, ordinary local release signing retains its previous behavior. CI neither supplies
release secrets nor publishes a GitHub release. Its unsigned APK is a build-verification artifact
and cannot be installed without signing. Do not replace production signing with a debug key.

From the repository root, with Java 17 available:

```text
./gradlew compileDebugKotlin testDebugUnitTest lintDebug assembleDebug --continue --stacktrace
./gradlew assembleRelease -Prenkin.unsignedRelease=true --stacktrace
# On a developer machine with the existing signing configuration:
./gradlew assembleRelease --stacktrace
```

On the local Windows/JBR machine, after the verification checkpoint is authorized:

```powershell
$env:JAVA_HOME='D:/Android/jbr'
.\gradlew.bat -I build/verify-jvm.gradle compileDebugKotlin testDebugUnitTest lintDebug assembleDebug --continue --stacktrace
.\gradlew.bat assembleRelease '-Prenkin.unsignedRelease=true' --stacktrace
.\gradlew.bat assembleRelease --stacktrace
```

The ignored init script is local and must exist before using the first Windows command. Do not
copy its workaround into CI or application JVM settings. Do not record screenshots to make CI
pass: inspect [rendering-tests.md](rendering-tests.md) and renderer/platform differences first.

## Checkpoint and practical limits

The integrated local gate is now green for the source identified above. Revalidation is needed
if application/build configuration changes before integration. Historical passing tests/builds
do not establish a newer integration's result. The PowerShell
property argument must be quoted; an initial unquoted invocation was rejected as an unknown
task before release work began. No push or remote dispatch is implied. An actual GitHub run is
still needed to confirm Linux dependencies,
native renderer comparisons and artifact paths; a successful local run alone cannot establish it.

Assembly exercises shrinking and generated-class processing, but does not execute the optimized
application. Once a separately signed release is available through the normal release process,
the developer should perform these device checks before declaring release readiness:

1. Cold-start the signed release, open Help/What's new and switch between two existing profiles.
   Expected: screens and profile contents load without missing-class/resource crashes.
2. Edit an icon with Scale, Colorize and Outline, add an erase brush stroke, wait at least three
   seconds in Modifier, then terminate the process and reopen/Restore. Expected: preview and
   controls retain every edit; Apply persists the reviewed appearance. This repeats the combined
   scenario that is not yet established as a successful device check in the architecture review.
3. Open presets, Watch and a cached online icon, then build a generated icon pack with the usual
   installer route. Expected: feature screens, worker entry points and APK generation/signing
   work in the optimized app. Network failures remain distinguishable from rendering failures.

Do not uninstall an existing app to work around a signature mismatch. The CI debug APK has the
existing `.dev` application id; the unsigned release retains the production id. This checkpoint
does not change DB/backup formats, retire prefsSnapshot or certify all runtime reflection paths.
