# Confirmation guard and build identity — 2026-10-17

Branch: `fix/installer-confirmation-guard`, based on `dev` at `6a38c34d`.

Scope: the remaining confirmation crash and ambiguous build identity from the issue audit.
This does not implement global Material You generation (#12), dynamic original app icons
(#15), or global automatic app-color matching (#18). It does not establish the cause of
Evolution X launcher behavior reported in #16, or claim that issue is resolved.

Ackpine 0.25.4 launches the system-provided confirmation intent without catching
`ActivityNotFoundException`. Renkin's discovery check cannot protect that later Activity
boundary. The source adapter in [compat/ackpine-core](../compat/ackpine-core/README.md)
guards the actual launch and reports an exceptional session result. The existing verifier,
fallback classification and failure presentation own what happens next.

The original upstream sources are pinned as a public submodule. Only core is rebuilt;
the remaining Ackpine artifacts retain their existing version. No fork, upstream message,
binary patch, custom installer or global exception-swallowing handler is introduced.
This supersedes C1's unapplied-dependency limitation once its runtime checks pass.

Debug and benchmark versions carry explicit suffixes. Settings, crash logs and install
failure reports include a shared version/build/revision identity. Local tracked edits mark
the revision `-dirty`. Source archive and reproducible-build callers can supply the exact
identity with `-Prenkin.revision=<revision>`; that explicit identity bypasses Git probing.
CI pairs APK and R8 diagnostics artifact names with the commit and keeps release diagnostics
for 90 days. This does not publish a release or change release versionCode 55.

Required verification:

1. Resolve debug and release runtime dependencies: core must resolve to the source adapter,
   including plugin transitives, with no second core implementation.
2. Run the actual patched Activity with a valid Ackpine session and an unavailable
   confirmation target: no process crash, the session ends exceptionally, Activity finishes.
3. Confirm normal launch, explicit cancellation and Activity recreation keep upstream behavior.
4. Build debug and unsigned R8 release; inspect the merged Activity and permission declarations.
5. On a backed-up debug app, inspect the version/build identity and exercise the missing-target
   boundary without disabling system packages. A real successful confirmation and update
   require the developer's system-installer interaction under the device-test rules.

Observed verification:

- Eight Robolectric checks passed across API 23 and 33: missing target, normal handoff,
  explicit cancellation and Activity recreation. The missing target fails only its owning
  session; a launched confirmation does not publish premature success.
- Debug and release runtime dependency resolution substitute core through direct and plugin references.
  The published KTX, privileged and Shizuku artifacts' nine references into core still resolve
  against the rebuilt classes. The upstream source checkout remains unchanged.
- The debug manifest contains the guarded confirmation Activity and the debug-only probe;
  release contains the confirmation Activity without the probe. Neither merged manifest
  contains `UPDATE_PACKAGES_WITHOUT_USER_ACTION` or `ENFORCE_UPDATE_OWNERSHIP`.
- On OnePlus CPH2663 / Android 16, the missing-target probe passed twice with the same
  process PID. Settings displayed `2026.09.02-dev (55)`, `debug` and `6a38c34d8bd1-dirty`.
  This is an Activity-boundary check with a fake session, not an actual APK installation.
- The debug app's original data was restored: all 47 persistent file hashes matched,
  939 icon rows remained across six profiles, and active profile 25 was shown again.
  Local backup archives and database copies were removed after verification.
- The full suites passed: 1,462 app tests plus eight adapter tests, with zero failures,
  errors or skipped cases. Debug assembly and both debug lint tasks passed. Lint reports
  zero errors, 189 app warnings and two upstream resource-qualifier warnings in the adapter.
- Unsigned release assembly passed with R8 and resource shrinking enabled. The release APK
  is 9,779,684 bytes; the installed debug candidate is 36,799,280 bytes. The debug candidate's
  SHA-256 is `ce3b34fc0d05b04f8757f7d27cc1fed607c858dc79d127116ead1d3a6f910109`.

Automated verification is complete. The developer explicitly approved committing and merging
to `dev` on 2026-10-17 after receiving these results and the remaining manual checks.
Actual system-installer acceptance was not observed by the agent.

Remaining manual checks on the installed debug candidate:

1. Build and install a pack using the system route; accept confirmation. Expect a verified
   installation with no crash and no false failure.
2. Rebuild the same profile after changing an icon and accept the update. Expect the new
   pack content to install; launcher application remains a separate action if required.
3. Cancel a confirmation, then retry. Expect cancellation without an automatic alternate
   installer, and a fresh usable confirmation on retry.

The device-test rule prohibits operating the system installer. These remaining interactions
must be performed by the developer; automated evidence does not replace their approval.
