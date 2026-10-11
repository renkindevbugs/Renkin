# Lint compatibility findings (CI1a)

The first CI1 debug lint run on the post-7D source reports 17 errors, 174 warnings and seven
informational findings. Debug assembly succeeds and 1,225 fresh unit tests pass; that does not
make lint green. The original reports remain local under app/build/reports/lint-results-debug.*
and build/verify-ci1-debug.log in the original checkout.

This isolated fix/ci-lint-api-contracts branch starts from b138de24. CI1's workflow/build-script
changes remain uncommitted on feature/ci-release-verification in the original checkout.
Preserve those independent changes when integrating the fixes. Merge the verified compatibility fix first, then update/revalidate
CI1 against dev before merging CI configuration. Neither branch changes persistence formats.

## Reported errors and fixes

| Finding | Count | Change |
| --- | --- | --- |
| MissingPermission | 2 | Retain the existing notification permission/toggle check and handle SecurityException around both notification publications, including revocation between check and notify. |
| ResourceType | 1 | Isolate exact bytes of the bundled, file-backed font in a private helper. A local ResourceType annotation documents the mismatch between openRawResource's lint resource category and its valid font-file use; no global lint suppression is added. |
| NewApi | 13 | Annotate PackageVersion.is26OrMore/is33OrMore with ChecksSdkIntAtLeast, retain API contracts/guards at adaptive capture/render/restyle boundaries and the remembered UI capability check, and require API 33 before directly reading monochrome artwork. For exact resource reads before API 27, pass the Android bionic O_CLOEXEC flag value (0x80000) instead of referencing an unavailable SDK field. |
| SuspiciousIndentation | 1 | Add braces to the existing Watch pager else branch; retain the same conditional behavior. |

Drawable.isAdaptiveIconDrawable and AdaptiveIconDrawable.haveMonochrome already carry
ChecksSdkIntAtLeast contracts in the baseline. The new annotations make their underlying
PackageVersion checks explicit too; the remembered Compose boolean still needs a direct SDK
guard at the cast. The bundled-font helper is suspend, preserving readStream cancellation.

The close-on-exec flag remains atomic at open, with O_NOFOLLOW, bounded reads, path ownership
and before/after file checks retained. O_CLOEXEC is public from API 27; SOCK_CLOEXEC is public
from API 29, so the proposed alias is not a valid older-API fallback. See
[OsConstants](https://developer.android.com/reference/android/system/OsConstants.html#SOCK_CLOEXEC).
Before API 27, a private compatibility constant supplies 0x80000 (02000000 octal), as defined
by Android's [bionic fcntl.h](https://android.googlesource.com/platform/bionic/%2B/6861c6f/libc/include/fcntl.h).
API 27+ retains the public O_CLOEXEC field. There is no hidden-API reflection, non-atomic
open-then-fcntl workaround, or omission of the flag.

No baseline, broad lint suppression, dependency upgrade or R8 rule change is part of this fix.
The 174 warnings need separate triage; this checkpoint addresses error sites, not every warning.
R8 metadata warnings from the CI1 release verification also remain a toolchain follow-up.

## Verification checkpoint

The developer authorized lintDebug before the device checkpoint. The final local run passes
in 2m 28s, including Kotlin/Java compilation and Hilt processing. Its XML report contains zero
errors, 174 warnings and seven informational findings. The warnings/informational findings
match the original CI1 report by issue id and message; none were added or removed. No
propagated RequiresApi caller errors remain. The earlier compile failure exposed a missing
suspend modifier on the font helper, and the first completed lint exposed the unsupported
SOCK_CLOEXEC fallback; both are corrected in the verified source.

Reproduce with JAVA_HOME set to the local JDK and ./gradlew lintDebug --stacktrace. The final
log is build/verify-ci1a-lint-final.log and reports are app/build/reports/lint-results-debug.*
in this isolated worktree. Earlier failed-run logs remain under build/verify-ci1a-lint*.log.

The developer confirmed device behavior and authorized tests and merge. The targeted suite
passes 66 tests in six suites, with zero
failures, errors or skips, including both existing Roborazzi image comparisons. Its log is
build/verify-ci1a-targeted-final.log. The initial notification-test shadow needed an explicit
per-test reset of its publication counter; the corrected fixture passes both revocation cases.

The complete verification passes compileDebugKotlin, testDebugUnitTest, lintDebug and
assembleDebug in 5m 54s. JUnit reports contain 1,240 tests in 170 suites, zero failures,
errors or skips; both Roborazzi comparisons pass without recording reference images.
Lint still reports zero errors, 174 warnings and seven informational findings. The full log
is build/verify-ci1a-full.log; JUnit XML/HTML and lint reports are under app/build/.
The existing local test-JVM init script is used only for the JBR workaround; it is not added
to CI or tracked application settings. No install, app launch, push or remote CI run occurred.

Regression coverage exercises protected upload reads and bundled-font capture on API
23/26/27/33, adaptive application fallback on API 26/32, ordinary notification publication on
API 23/26/33, denied permission and SecurityException from either suggestion or summary
publication. File tests retain bounded-read, ownership, mutation and cancellation coverage.
Older-platform checks here are Robolectric tests, not an emulator or physical-device run.
CI1 integration and both release variants remain a separate pending checkpoint.

1. Open an adaptive application/pack source, select Material You when available, adjust
   Scale and Outline, and Apply. Expected: the preview and saved icon retain their appearance.
2. Select Material You for a source without monochrome artwork. Expected: the existing
   generated fallback is shown.
3. Select a Material You restylable pack and change line weight. Expected: restyling works
   as before.
4. Select text with a system font, edit the icon, wait three seconds, terminate the process,
   reopen and Restore. Expected: font and edits survive. Also repeat with the bundled font
   to exercise the suspend resource-stream helper.
5. Repeat process-death recovery with an uploaded image. Expected: image and edits survive.
6. If an API 23-26 device/emulator is available, repeat system-font/upload recovery there.
   Expected: autosave and recovery do not crash. Otherwise record this platform as unverified.
7. Open Watch with an empty filter, then a multipage filter. Expected: empty state, paging
   and selection work as before.
8. With notifications allowed, run a Watch check that discovers a new icon. Expected: the
   suggestion notification is delivered.
9. Disable Renkin notifications in Android settings and repeat the Watch check. Expected:
   no notification and no crash. Automated tests also cover revocation at each publication.

The agent does not install or launch the app. The local worktree has only SDK-location
configuration and the upstream font prepared for local builds, without signing
credentials. The debug application id stays .dev; do not uninstall existing apps to resolve
signature mismatches. A combined successful Scale/Colorize/Outline/brush recovery run is still
required before recording that device scenario as verified.
