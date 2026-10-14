# Installation compatibility C2 checkpoint — 2026-10-14

Branch: `feature/save-failed-apk`, based on `fix/install-confirmation-crash` (C1, `f731f11a`, not
yet merged). Source plan: [install-compatibility-plan.md](install-compatibility-plan.md).

Status: verified on a OnePlus CPH2663 (Android 16) and approved by the developer; I6 committed on
`feature/save-failed-apk`, I3 on `fix/install-technical-fallback` branched from it; not merged.
Device results: Shizuku install unchanged (618 ms, no Save APK on success); system cancellation
ended without a failure dialog or second route; choosing an APK viewer as installer produced the
TIMEOUT report with Save APK; backing out of the document picker left the dialog intact; the
developer saved an APK and installed it manually. Not exercised: Shizuku CONFLICT routing (debug
and release packs share the signing key, so the only conflicting pack was a real one) and a
generic system-session FAILED. After device approval the copy loop moved from
`IconPackBuildService` into `ApkBuildArtifacts.export` so pinning and copying are unit-tested;
behaviour is unchanged. Full unit suite and `assembleDebug` pass.

Observed on the device: OnePlus freezes Renkin in the background, so external-installer
monitoring resumes only after returning (the timeout surfaced after 156 s). Success is still
detected on return because package state is checked first. The TIMEOUT report reuses the generic
"Android rejected the installation" text; a timeout-specific sentence is a follow-up.

## I6 — Save APK from a failure report

- `BuildInstallState.Failed` keeps the `BuiltIconPack`; the failure dialog shows **Save APK** when a
  pack exists. ABORTED (user cancellation) still ends with a toast and no dialog.
- Tap → `beginFailedApkExport()` remembers the pack and suggests `<pack label>.apk` (unsafe
  characters replaced) → system document picker (`CreateDocument(APK_MIME_TYPE)`) →
  `finishFailedApkExport(uri)` copies on IO and shows "APK saved…" or "could not be saved".
- Artifact ownership: a failed attempt has already released its directory (marked `.finished`;
  it is deleted by the next build's prune). `ApkBuildArtifacts.retainForExport` pins the directory
  with a reference count so prune skips it until `finishExport`; installer ownership (`active`)
  is untouched. If the APK was already pruned, the export reports failure instead of guessing.
- The copy checks cancellation per 64 KiB chunk; an incomplete document is deleted through
  `DocumentsContract.deleteDocument`.
- Saving is never evidence of installation: nothing is persisted, no build baseline changes. A
  manual install is discovered by the normal missing-pack refresh.
- The NO_SYSTEM_INSTALLER text now also mentions saving the APK.

## I3 — Selective technical fallback

`shouldTryNextRoute(backend, result)` replaces three ad-hoc checks:

| Route | Moves on after | Next route |
|---|---|---|
| System session | FAILED (generic, exceptional, dead session) | Compatibility intent, once |
| External installer | FAILED (could not launch/share) | System session |
| Shizuku | FAILED, BLOCKED (e.g. Xiaomi "Install via USB" off) | System session |
| Compatibility | never | — |

Everything else (ABORTED, CONFLICT, INCOMPATIBLE, INVALID, STORAGE, TIMEOUT, BLOCKED on the
system route, NO_SYSTEM_INSTALLER) ends the attempt. Ambiguous results are still verified first
(C1), so a verified install never falls back.

Behaviour changes versus C1:

- A non-dead generic system-session failure now gets one compatibility attempt (previously only
  "Session N is dead").
- Shizuku CONFLICT, INCOMPATIBLE, INVALID, STORAGE and TIMEOUT no longer start the system
  installer (it would fail the same way or risk a second concurrent install). CONFLICT still
  reaches the existing Replace prompt.

## Manual device checks

1. Build a test profile with the system installer and confirm. Expect unchanged success, toast and
   dialog; no Save APK anywhere.
2. Cancel the system confirmation. Expect the cancellation toast, no failure dialog, no second
   installer.
3. Produce a failure dialog without touching system settings: pick an app that only views APKs
   (on the test device "File Viewer") as the installer, stay in it for over two minutes, then return.
   Expect a TIMEOUT failure dialog with **Save APK**. Save to Downloads, install the saved file with
   any installer; expect it installs, and Renkin shows the pack as installed after a refresh.
4. Open Save APK and back out of the picker. Expect no toast and the dialog still usable.
5. Save twice from the same dialog. Expect two independent files and two "APK saved" toasts.
6. With Shizuku running, build an update of a pack whose signature differs (Replace scenario).
   Expect the Replace prompt directly, without a system installer appearing first.
7. With Shizuku selected but stopped, build. Expect the system installer as before.
8. Copy details from a failure report. Expect every attempted route listed.

Regression tests after approval: `shouldTryNextRoute` table; `apkExportFileName` sanitisation;
`ApkBuildArtifacts` pin survives prune and is released, nested pins, missing file returns null;
`IconPackBuildService.exportApk` copies bytes, deletes the document on failure, reports false
when pruned.
