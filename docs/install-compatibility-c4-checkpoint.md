# Installation compatibility C4 checkpoint — 2026-10-15

Working branch: `fix/install-signature-precheck`, based on `dev` at `5b495669` (C1–C3 merged).
Source plan: [install-compatibility-plan.md](install-compatibility-plan.md).

Status: verified on a OnePlus CPH2663 (Android 16) and approved by the developer; committed as three
stacked branches, not merged: `fix/install-signature-precheck` (I7) →
`feature/install-failure-help` (I8) → `feature/install-confirm-notification` (I4, plus docs).
Device results: a Shizuku install and update of the same pack passed the signer pre-check without
a Replace prompt; building with the system installer and pressing Home posted Ackpine's
confirmation notification; tapping it and confirming updated the pack (UI publication 8 ms after
confirmation) and the notification disappeared. Not exercised: a real signer mismatch (the only
differently signed pack was the developer's own) and the recognised failure classes; both are
covered by `PackSignatureCheckTest` and `InstallFailureHelpTest`. Each commit compiles on its own;
the full unit suite and `assembleDebug` pass on the final tree.

## I7 — Signature pre-check

- `PackSignatureCheck.canUpdateWithSameSigner` compares the installed pack's current signer with
  the built APK's signing lineage (`GET_SIGNING_CERTIFICATES` on API 28+, signatures below).
  Not installed → true; definite mismatch → false; unreadable on either side → null.
- `IconPackBuildService.install` runs it on IO before any installer starts. Only `false` short-cuts
  to `CONFLICT` with `SIGNATURE_MISMATCH_DETAIL`; `null` never blocks and Android decides.
- The coordinator marks the Conflict state, and the existing Replace prompt shows a
  signature-specific explanation. Nothing is uninstalled without the user's Replace choice.
- The older version/keystore pre-check (`canBeInstalled`) is unchanged.

## I8 — Help for recognised failures

`installFailureHelp(result, report, manufacturer)` inspects the full failure report:

| Evidence | Help |
|---|---|
| `INSTALL_FAILED_HYPEROS_ISOLATION_VIOLATION` | Try Shizuku, an external installer or Save APK |
| `INSTALL_FAILED_USER_RESTRICTED` / "user restriction" on Xiaomi, Redmi, POCO | Developer options → Install via USB |
| same restriction elsewhere | Check "install unknown apps" / device administrator |
| `INSTALL_FAILED_VERIFICATION_FAILURE` / "Play Protect" | Choose install anyway when Play Protect asks |
| BLOCKED on Samsung | Auto Blocker (Settings → Security and privacy) or Shizuku |

Anything else shows no advice. The help sentence appears under the summary in the failure dialog.

## I4 — Confirmation while Renkin is in the background

- At session creation `confirmationFor` uses Ackpine `Confirmation.DEFERRED` (a notification that
  opens the system confirmation) when Renkin is not in the foreground, notifications may be
  posted, the route is not Shizuku and the device runs API 26+. Otherwise `IMMEDIATE`, as before.
- API 26+ only because VIBRATE was deliberately removed (`5e95d666`); from API 26 the
  notification channel owns vibration.
- After the user confirms a build in the preview, Renkin requests POST_NOTIFICATIONS on API 33+
  if missing. The build starts regardless of the answer; Android stops asking after two denials.
- The success toast/dialog are unchanged and separate from the confirmation notification.
- Known limit: the choice is made when the session is created. Leaving Renkin within the fraction
  of a second between creation and commit keeps IMMEDIATE; Android then blocks the activity start
  (`BAL_BLOCK`, observed on the test device), and the confirmation appears as soon as Renkin
  returns to the foreground. Nothing hangs, but no notification reminds the user in that window.

## Manual device checks

1. Normal foreground build with the system installer: unchanged immediate confirmation, success.
2. Start a build with the system installer and press Home while it builds. Expect a "Confirm icon
   pack installation" notification; tapping it opens the system confirmation; confirming installs
   and Renkin shows success when reopened.
3. Same as 2 with Renkin's notifications disabled: no notification, behaviour as before (the
   confirmation appears when Renkin returns to the foreground or the attempt stays pending).
4. Update a pack built by the same Renkin: no Replace prompt (signatures match).
5. Signature mismatch (only on a disposable pack signed by another key): Replace prompt with the
   signature explanation, without any installer opening first.
6. Revoke the notification permission, confirm a build: the permission prompt appears, and denying
   it does not stop the build.
7. Failure help can only be checked when such a failure happens; otherwise covered by unit tests.

Regression tests after approval: `installFailureHelp` table; `installOrReportConflict` with
`sameSigner` true/false/null; `canUpdateWithSameSigner` not installed / unreadable archive.
