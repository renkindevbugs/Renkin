# Installation compatibility C1 checkpoint — 2026-10-14

Follow-up, 2026-10-17: the [confirmation guard checkpoint](installer-confirmation-guard-checkpoint.md)
adds a pinned source adapter for the actual Activity launch. The unapplied-proposal statements
below describe the original C1 checkpoint; current guard verification is recorded in the follow-up.

Branch: `fix/install-confirmation-crash`, based on `dev` at `74a87037`.

Status: verified on a OnePlus CPH2663 (Android 16) and approved by the developer; committed on
this branch, not merged. The first compile exposed a missing `InstallFailureInfo` field, fixed by
exposing `missingSystemInstaller` from MainViewModel. Device results: the pre-flight found the
platform `InstallStart`; Shizuku first install and update succeeded with UI publication 2–9 ms
after confirmation; cancelling the system confirmation returned ABORTED after the 3 s verification
window with no success and no second attempt; InstallerX success was detected. Not exercised on
device: a successful system-dialog install (needs the developer's tap), a device without a system
installer, and a false-cancel that actually installed. Regression tests cover pre-flight discovery
and the submitted/installed digest. Full unit suite and `assembleDebug` pass.
Follow-up candidate: a shorter verification window for explicit user rejection.

## Scope and decision

C1 is one device checkpoint. Keep I1 discovery/presentation and I2 verification in separate
commits after device approval; C2 artifact export/selective fallback and C3/C4 remain separate.
The source plan is [install-compatibility-plan.md](install-compatibility-plan.md).

The local Ackpine proposal in [patches](../patches/README.md) guards the actual failing
`startActivityForResult` path. The developer explicitly chose not to publish it yet. It is not
applied to the dependency; Renkin still uses the released 0.25.4 artifacts. Consequently this
checkpoint reduces the known missing-handler risk but does not claim that #221 is fully fixed.
The real system-supplied intent can target a different disabled installer even if another handler
resolved, or a handler can disappear between discovery and launch. Shizuku can also be routed
into confirmation by the platform. Those cases still require an upstream Activity fix.

The Renkin pre-flight queries enabled/exported system or updated-system handlers for
`CONFIRM_INSTALL` or APK `INSTALL_PACKAGE`. Corresponding package-visibility queries are
declared; no package/vendor names decide routing. Discovery and package reads run off Main.
If no handler can be verified, no system session starts and NO_SYSTEM_INSTALLER is reported.
Compatibility fallback separately requires an INSTALL_PACKAGE handler before starting its session.
The same discovery marks the System picker entry unavailable. Third-party handlers do not count
as the platform confirmation activity, but remain available through the existing external picker.

User flow: build → system unavailable report → Select installer → choose Shizuku or an external
handler → build again. The existing picker supplies actual discovered options and Shizuku state.
No second install is started automatically by that action. A failed APK follows existing cleanup;
the new choice applies to the next build. Save APK is deliberately reserved for C2, which must
first extend the failed-artifact ownership contract. No dead button or fake export is included.

## Ambiguous-result evidence

Only session ABORTED/FAILED/TIMEOUT outcomes enter the verifier. Ordinary platform success
returns immediately; the existing external monitor and InstallerX active-session protection
remain in place. A result is reclassified only when:

1. The installed package differs from the captured pre-attempt version/update-time baseline.
2. The installed APK's SHA-256 equals the exact submitted APK's SHA-256.
3. A second package read still matches the candidate state whose APK was hashed.

This deliberately strengthens the plan's version-only shortcut: even a version change requires
the digest for ambiguous results, avoiding a coincidental update by another installer. Baseline
change without a readable matching artifact is never sufficient. No bitmap/payload copy is made.

One digest owner belongs to each top-level attempt and is reused across fallback routes. The
submitted digest (including an unreadable/null result) is cached after its completed read. Both
streams close in `use`; hashing uses a 64 KiB buffer on IO and checks cancellation each chunk.
Installed content is hashed once per differing candidate state, rather than on every poll.

PACKAGE_ADDED/REPLACED only wakes the checker; polling remains at 250 ms. Verification is
bounded by a cancellable 3 s window, including hashing. The existing recognized dead-session
path retains its 8 s allowance; it does not wait a second window before compatibility fallback.
A blocking OS file/Binder call may finish after its deadline, but cancellation is checked before
accepting a result. Parent cancellation propagates through the timeout and digest helper.

Failed verification preserves the reported status and adds a diagnostic note distinguishing no
confirmed change, unreadable artifact and digest mismatch. Verified success records the original
reported status and actual backend label. Shizuku ABORTED no longer starts a system attempt
after the verification window. Other existing Shizuku fallback behavior is unchanged; broader
terminal-versus-technical classification belongs to C2. No broad fallback is added here.

Confirmed-success publication still precedes its awaited profile bookkeeping, with the existing
completion owner blocking another build. The existing Build commitment also saves the built icon
snapshot on cancellation/failure; only success records the primary installed-build preferences.
This checkpoint does not alter that distinction, Room, codecs, signing identity, backup formats,
draft recovery, silent-update permissions or artifact release ownership.

## Static architecture review

- Discovery lives in the APK/catalog layer; Compose only presents state and callbacks.
- Missing-system presentation carries a typed outcome, not a string/exception-name heuristic.
- Hash/package I/O runs outside Main/profile gates, using the existing awaited install job.
- A broadcast alone never proves success; raw session success remains the platform's positive result.
- Cancel and failed verification never publish installation success, record primary installed-build
  preferences or claim a matching installed artifact. Existing build-snapshot persistence remains.
- Success publication precedes profile save, and no new asynchronous persistence job is introduced.
- The dependency patch is explicitly unapplied; there is no hidden AAR modification or global
  UncaughtExceptionHandler.
- Root research folders remain untouched and excluded from commits.

Only source/diff checks have run. Runtime behavior and the upstream proposal still need their
respective device/regression checks; no successful build is claimed for C1.

## Manual device checks

Use disposable test profiles/packs and retain existing data.

1. System install of a new test pack and an update: expect the usual confirmation, one success
   toast, the correct first/update guide and no new delay before the success dialog.
2. Cancel the system confirmation: expect no success or extra install dialog; the cancellation
   notice should arrive within about 3 s. The profile must not be marked installed.
3. Install through ready Shizuku, then cancel any confirmation it requires: success must name
   Shizuku; cancellation must not silently start the system installer.
4. Install through your selected external handler, including InstallerX if available: expect the
   existing actual-install detection and background-session return handling.
5. On a separate test device/emulator that already lacks enabled system installer handlers,
   choose System and build: expect a dismissible NO_SYSTEM_INSTALLER report, no system session,
   and Select installer. Choose an available alternative, then build again and verify its route.
6. Reopen the picker on a normal device: System must remain selectable. On the missing-handler
   test device it must be unavailable; changing installer must not switch profiles or claim success.

False ABORTED/FAILED and mismatching/unreadable digest cases require controlled regression
injection after manual confirmation; do not tamper with an installed APK to manufacture them.
After approval, add the verification decision table, timeout/cancellation/stream-close cases,
pre-flight visibility/routing and picker/failure-action coverage; run relevant/full tests and
assembleDebug before commits and merge. Follow upstream rules separately before publishing
or claiming the Ackpine Activity proposal as verified.
