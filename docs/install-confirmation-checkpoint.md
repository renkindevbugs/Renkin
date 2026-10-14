# Install confirmation checkpoint — 2026-10-14

Branch: `fix/install-confirmation-persistence`, based on `dev` at `6f57e411`.

## Status

The developer approved priorities 1–2 on-device and explicitly authorized tests, commit and merge
into `dev`. The post-install blocking delay is gone by manual observation; exact timings remain
unmeasured. At final approval, the developer requested removal of the opening toast; the success
toast and both launcher-guide dialogs remain. Debug and release verification passed; the change
was committed as `f3e23d69` and merged into `dev` as `acc638ee` after explicit approval.
Both historical stashes and unrelated worktrees are preserved.

## Installation flow

- An external attempt registers its own package receiver before launching the installer.
  `ACTION_PACKAGE_ADDED` and `ACTION_PACKAGE_REPLACED` for the exact target package wake a
  conflated channel. The waiting coroutine rechecks version/update time against its captured
  baseline; broadcasts never publish success themselves. Duplicate signals cannot complete
  the coroutine twice, and another attempt never shares its channel or receiver.
- Package polling remains a 250 ms fallback. A monotonic 120 second deadline and 2 second
  return grace replace poll counters, so additional broadcasts cannot shorten either window.
  Active installer sessions still protect an installation continuing after its UI closes.
  The package is checked again before classifying a return without a session as cancellation.
- Receiver registration failure falls back to polling. Success, timeout, failure and coroutine
  cancellation unregister the receiver and release the URI grant.
- Each attempted route captures its display label before launch. Progress follows the actual
  external, Shizuku, system-session or compatibility route. The success notice uses the last
  successful attempt, including a fallback, rather than the originally requested method.
  First installs and updates have separate wording; conflict replacement retains update wording.
  External opening updates the loader text without a toast. Confirmed success emits one
  `<pack> installed/updated via <installer>` toast before saving, including an explanation
  when Shizuku falls back. MainViewModel forwards these through the existing shared ToastHost.
  Both first installs and updates also show the existing launcher guide with the same styling.
  Updates explain how to refresh cached launcher icons by switching to another pack and back.
  Android controls text-toast visibility above other apps and may rate-limit background toasts.

## Confirmed installation and profile saving

The provider publishes confirmed installation through a suspend callback before acquiring
the persistence gates. The coordinator emits the success toast and shows the applicable
launcher guide with a small saving status. The blocking loader ends on publication.

The same awaited job completes persistence on Default in a non-cancellable section after
success. Keeping the installer and completion on Default avoids losing a confirmed result
at an intervening dispatcher return if the UI owner is cancelled. Closing the dialog does not
cancel saving. This does not make saving survive process termination.

An operation-owned completion marker rejects another build until persistence and UI follow-up
finish, even after dialog dismissal. An older conflict attempt cannot clear a newer attempt's
marker. Provider persistence retains the storage → profile gate order, storage epoch and saved
revision checks, exact build snapshot, matching-session Undo checks and artifact cleanup.
Newer saved rows and live edits retain their existing protection.

`IconPackInstallCompletion` reports a profile-save failure separately from `ApkInstallOutcome`.
The confirmed installer result remains successful. A visible success dialog shows the saving
error; dismissed dialogs emit one failure notice instead. Failed saving skips build
baseline UI synchronization; installed-pack discovery remains a best-effort follow-up.
Persistence and artifact cleanup run in `finally` even if the publication callback throws.
Callback failures are logged separately; cancellation propagates after completion cleanup.

## Timing evidence still needed

No new device timings have been collected. The previously reported 2+ second delay is not a
measured baseline for this branch. A read-only ADB check found no running debug Renkin process
and no retained `InstallPerformance`/`ProfilePerformance` entries; it yielded no duration samples.
The developer's report that the blocking delay is gone is a manual observation.
Debug logs now distinguish:

- `InstallPerformance`: elapsed time until Renkin observes the installer result; this includes
  user interaction and is not Android's internal install duration.
- `InstallPerformance confirmedToUiPublicationMs`: observed success → coordinator state
  publication, before profile persistence; this is not a rendered-frame timestamp.
- `ProfilePerformance install.persistConfirmedSnapshot`: post-confirmation completion,
  including gate acquisition, snapshot persistence and artifact release.
- `ProfilePerformance install.finishAttempt`: completion for non-successful install attempts.

Collect these measurements during authorized verification without exporting private profile,
database, icon or backup payloads. The device skill excludes operating system/foreign app UI;
installer confirmations need the developer's interaction within that boundary.

## Manual device checks

Use a disposable test profile and its own generated package. Keep existing packs and drafts.

1. Select an external installer and build a pack that is not yet installed. Expect
   `Opening <installer> …` in progress without a toast, then one `<pack> installed via <installer>` toast and the
   first-install launcher guide. The guide must name the same successful installer.
2. Change one test icon and build again through the same installer. Expect update wording,
   one `<pack> updated via <installer>` toast and an update dialog naming the same installer,
   with the Refresh icon and advice to switch launcher packs and back. The blocking loader must
   end when installation is confirmed. Dismiss the dialog and verify it does not reappear.
3. With an installer that closes its UI while work continues, return to Renkin. Expect the
   active install session to keep the attempt waiting until completion, rather than report
   cancellation just because Renkin resumed.
4. Cancel in the external installer and return. Expect no success toast or dialog and no claim that
   returning installed the pack. Saving the built snapshot retains the existing build semantics.
5. Select Shizuku while it is unavailable, then complete the system fallback. Expect progress
   and success to name the system installer; the single success toast must also explain that
   Shizuku failed. No separate fallback-success toast should be queued.
6. Dismiss either success dialog while the profile is still saving and tap Build again. Expect a notice that
   the previous build is finishing; no second build or install starts until it completes.
7. After saving completes, restart Renkin and reopen the test profile. Expect the exact built
   icons, the correct change baseline and no changes to other profiles. A later edit must
   remain unsaved and must not be cleared by completion of the older build.
8. During the saving phase, switch to another test profile if the UI permits it. Expect the
   captured profile to receive its own build; the current profile's icons and Undo stay intact.

Toast delivery above third-party UI remains a device check, not a guarantee derived from emission
of an app event. End-to-end device fault injection and the full 120 second installer timeout have
not been exercised. Unit tests do inject publication and persistence failures and cancellation.

Release notes belong in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` after
verification, during release preparation. No root changelog or untested release entry is included.
The formatted-toast callback is required; its existing test factory explicitly supplies a failure
guard. Regression tests cover receiver correlation/conflation/cleanup, successful publication
before persistence, caller and callback cancellation, saving/publication failures and fallback messages.

## Automated verification

- Targeted installation suite: 27 tests passed.
- Full `testDebugUnitTest`: 1,310 tests across 180 suites, zero failures/errors/skips.
- `compileDebugKotlin` and `assembleDebug`: passed.
- `lintDebug`: passed with 0 errors and 186 warnings; no lint findings reference the new receiver,
  installer completion helper or coordinator.
- Local test JVM used the existing ignored `build/verify-jvm.gradle` workaround. No CI JVM
  defaults or Roborazzi reference images were changed.
- Unsigned optimized `assembleRelease`: passed with the local 4 GiB heap override. Kotlin/R8
  metadata warnings remain in the CI/release backlog. This is a build check, not signed-device validation.

## Remaining work

- Priority 3: editor recovery dialog implemented on the separate `feature/editor-recovery-preview`
  branch. The developer confirmed the scenarios tried on-device; regression verification and
  the follow-up blocked-state recheck are recorded in
  [editor-recovery-preview-checkpoint.md](editor-recovery-preview-checkpoint.md).
- Installed original-icon memory: UI thumbnails plus on-demand full sources, preserving user,
  activity, badges, adaptive/monochrome and replay/recovery identity. See
  [performance-installed-icons.md](performance-installed-icons.md).
- Backup/export memory: safe representative measurements before choosing streaming or storage
  changes; private-profile export remains unmeasured. Preserve backup formats 1–4.
- CI/release: inspect actual GitHub/Linux execution and artifacts before claiming completion;
  optimized signed runtime, Kotlin/R8 warnings and reproducible R8 memory budget remain open.
  See [ci-release-checks.md](ci-release-checks.md).
- `prefsSnapshot` retirement: last, behind the retirement gate and an explicit Room 22 migration
  with an authorized backup. Room remains 21. See [profile-style-migration.md](profile-style-migration.md).
- Architecture follow-ups remain in [ARCHITECTURE.md](ARCHITECTURE.md): effective dark-mode recipe
  validation, visual cache keys and residual presentation/visibility seams. The baseline-read
  retry bound is already resolved.
- Phases 7C, 7D, PERF4 and the Macrobenchmark/Compose audit are completed work and must not be
  restarted. No new Baseline Profile or stability gate is part of these installation changes.

## Platform reference

Dynamic receiver flags and lifecycle follow the Android documentation:
[Broadcasts overview](https://developer.android.com/develop/background-work/background-tasks/broadcasts).
Text-toast constraints follow the [Toast reference](https://developer.android.com/reference/android/widget/Toast).
