# Install and launcher compatibility plan — 2026-10-14

Status: approved direction; C1 implementation is awaiting its manual device checkpoint.
Base: `dev` at `74a87037`. See [C1 checkpoint](install-compatibility-c1-checkpoint.md) for the
actual scope, the unapplied local Ackpine proposal and the stronger digest verification rule.
Owner of each checkpoint: the implementing agent. Reviewer: architecture review before every
device checkpoint. Inputs: `reports/Morphe inštalácia na custom ROM.md` (local, uncommitted),
its notes in `research_notes/`, and an independent second review whose corrections are adopted
below. Neither `reports/` nor `research_notes/` may be committed.

## 1. Goal and non-goals

Goal: the generated icon pack installs and is discoverable on as many devices, ROMs and
launchers as possible, and every failure leaves the user with a working next step.

Non-goals for this plan:

- No ROM/brand detection as a diagnosis. Brand may only select help text after a concrete
  failure class has been observed.
- No silent updates (`UPDATE_PACKAGES_WITHOUT_USER_ACTION`, `ENFORCE_UPDATE_OWNERSHIP` stay
  removed, see `5e95d666`). That is a separate product decision.
- No root/mount backend, no "install as Play Store" spoofing, no automatic uninstall.
- No replacement of Ackpine by a custom session installer, no FileProvider rewrite.
- No change to the pack signing identity, Room schema (21), backup formats 1–4 or draft codecs.

## 2. Verified facts that constrain the design

| Fact | Evidence | Consequence |
|---|---|---|
| Ackpine 0.25.4 is the latest release and issue #221 is open | `gh api repos/solrudev/Ackpine/releases/latest`, issue #221 | We cannot fix the crash by upgrading. |
| #221 crashes in `SessionBasedInstallConfirmationActivity.launchInstallActivity()` via unguarded `startActivityForResult(EXTRA_INTENT)` | Ackpine 0.25.4 source, lines 260–269 | The exception happens in a library Activity, outside our `installWith` try/catch (`ApkInstaller.kt:379-417`). A Renkin catch cannot stop it. |
| Morphe hashes only selected cancelled/failed results; plain `STATUS_SUCCESS` is accepted | Morphe `InstallViewModel.kt`, PR #598/#686 | Copy "verify ambiguous results", not "always hash". |
| Morphe is GPL-3.0 | `gh api repos/MorpheApp/morphe-manager/license` | Code may be reused under GPL-3.0 with notices. Default remains: reimplement the pattern on our own code. |
| The pack key is reused | `PackKeystore.kt:8-42`: `filesDir/renkinpack.keystore`, random password per Renkin install, travels with full backups | Rebuilds do not change the signing identity. Only a clean reinstall without restore creates a new identity. |
| Google developer verification | developer.android.com FAQ | From 2026-09-30 it applies to participating stores in selected regions; direct sideloading is not yet affected; global rollout in 2027. Advanced flow: one-time setup with a one-day wait, then enable for 7 days or indefinitely. ADB is officially exempt; Shizuku is not mentioned. |
| Update guidance already exists | `buildUpdatedText` (switch pack away and back) | L4 extends it; Lawnchair #5513 shows switching may not suffice, a launcher force-stop sometimes does. |
| The pack already declares `com.novalauncher.THEME` and `org.adw.launcher.THEMES` | `app/src/main/res/xml/manifest.xml:9,56` | `org.adw.ActivityStarter.THEMES` is missing, but its absence alone does not explain issue #16. |
| Fallback today only runs for dead sessions | `isDeadInstallerSession` (`ApkInstaller.kt:70-78`), `installThroughSystem` (`:316-344`) | Other technical failures end the attempt without an alternative. |
| Late verification exists only for FAILED/TIMEOUT | `shouldVerifyLateResult` (`ApkInstaller.kt:477`) | ABORTED is never rechecked. |
| Watch suggestions do not touch editor drafts | `WatchApplyModal` uses `applyWatchIcon`; recovery prompt suppressed while `pendingSuggestion != null` (`MainScreen.kt:674-677`); covered by `42899504` | Not part of this plan. |

## 3. Invariants every checkpoint must keep

1. A success is published only on positive evidence: platform success, or the installed package
   changed against the captured baseline (`installationChanged`) and, where the result was
   ambiguous, the installed APK matches the exact submitted artifact.
2. User cancellation stays fast. Ambiguous-result verification is bounded (seconds, not the
   120 s external window) and never starts a second concurrent install.
3. No automatic retry for user cancellation, invalid APK, insufficient storage, signature
   conflict, downgrade or administrator policy. Those are terminal and explained.
4. Exactly one install attempt owns the artifact at a time. Artifacts are released only after
   success persistence, explicit dismissal or a completed export (see I6).
5. Profile persistence semantics from `fix/install-confirmation-persistence` stay unchanged:
   success is published before saving, completion marker blocks a parallel build.
6. Heavy work (hashing, file copy, package queries) runs off Main and never under the profile
   gate. Cancellation propagates; `NonCancellable` only for cleanup.
7. Help text is conditional on an observed failure class or a resolved HOME package; unknown
   launchers or ROMs get neutral text. Never advise disabling protections as a default step.
8. Strings only in `values/strings.xml` (English). Shared widgets and shape tokens. No new
   non-free dependency; any Ackpine patch must keep a public source and a reproducible
   F-Droid build.

## 4. Checkpoints (in order)

Each checkpoint is its own branch, its own device checkpoint, and is merged only after
explicit approval. Behaviour change and refactor never share a commit.

### C1 — Crash-proof system confirmation and verified ambiguous results (I1 + I2)

Branch: `fix/install-confirmation-crash` (I1) and `fix/install-ambiguous-result` (I2) — may
share one device checkpoint but stay separate commits.

**I1 — Ackpine #221**

Root cause: the system hands Ackpine a `CONFIRM_INSTALL` intent targeting a package installer
that is disabled, frozen or removed; Ackpine launches it unguarded and the process dies.

Plan, in this order:

1. Upstream fix first: prepare a minimal PR for Ackpine that wraps
   `startActivityForResult` in `launchInstallActivity()` and completes the session with a
   failure instead of crashing. Track the release; bump `ackpineCore` when available.
2. Until then, a pre-flight check in Renkin before choosing the system session route:
   resolve `Intent("android.content.pm.action.CONFIRM_INSTALL")` and
   `Intent(Intent.ACTION_INSTALL_PACKAGE)` with `setDataAndType(..., APK_MIME_TYPE)` through
   `PackageManager`. If no enabled activity resolves, do not open an Ackpine session.
   This is a heuristic, not proof: the real intent comes from the system later.
3. When the pre-flight fails: report a new result class `NO_SYSTEM_INSTALLER` (or reuse
   `BLOCKED` with a distinct detail) and offer the available alternatives in this order:
   Shizuku (if ready), an external installer from `InstallerCatalog`, Save APK (I6).
4. A local Ackpine fork/patch is acceptable only if the upstream PR stalls; it must be a
   published source dependency that F-Droid can build. Decide with the developer before.
5. Do not install a global `UncaughtExceptionHandler` to swallow the crash.

Files: `apk/ApkInstaller.kt` (route selection), `apk/InstallerCatalog.kt` (availability),
`BuildInstallCoordinator.kt` (presenting alternatives), `libs.versions.toml` (bump later).

**I2 — Verify ambiguous ABORTED/FAILED against the submitted APK**

1. Extend late verification: for ABORTED and FAILED from the system session, Shizuku and the
   compatibility path, re-read `installedState(packageName)` once immediately and then poll
   briefly (bounded, about 3 s at 250 ms; reuse `PackageInstallSignal` to wake early).
2. Positive evidence required: `installationChanged(previous, current)` is true. Because the
   pack's `versionCode` is installed + 1, a version change is strong evidence. If the version
   did not change but `lastUpdateTime` did, additionally compare the SHA-256 of the installed
   `applicationInfo.sourceDir` with the submitted APK.
3. Hashing: stream both files on `Dispatchers.IO` with a 64 KiB buffer, no second APK copy,
   never on Main, never under a gate; compute the submitted hash once per attempt and reuse it.
   If `sourceDir` cannot be read, the result stays as reported (no guessing).
4. If verified: publish success with an attempt detail such as
   "Package state confirmed installation after an ABORTED report." Otherwise keep the original
   result and keep the fast cancellation path.

Files: `apk/ApkInstaller.kt` (`shouldVerifyLateResult`, `finishOutcome`, `verifiedSuccess`),
new small `apk/InstalledApkDigest.kt`.

Device checks (C1):

1. On a normal device, install a new pack through the system installer. Expect unchanged
   behaviour and timing.
2. Cancel the system confirmation. Expect the cancellation notice within about 3 seconds and no
   success.
3. On a test device or emulator, disable the system package installer
   (`adb shell pm disable-user com.google.android.packageinstaller`, re-enable afterwards) and
   build. Expect no crash, a clear message and the offered alternatives.
4. With Shizuku running and the installer disabled, choose the offered Shizuku route. Expect a
   successful install naming Shizuku.
5. Shizuku install of an update. Expect success even if Shizuku reports a late failure while the
   package version changed.

Regression tests after confirmation: verification decision table (version changed, only
`lastUpdateTime` changed with matching/mismatching hash, unreadable source), bounded poll,
cancellation not reclassified, pre-flight routing.

### C2 — Save APK and selective fallback (I6 + I3)

Branch: `feature/save-failed-apk` (I6), `fix/install-technical-fallback` (I3).

**I6 — Save APK**

1. On any non-success that is not user cancellation, the failure dialog offers "Save APK".
2. Export through SAF `ACTION_CREATE_DOCUMENT` with the pack's file name; copy on IO from the
   artifact URI to the chosen document.
3. Artifact lifetime: the failed attempt must not release its artifact while the dialog can
   still export. Extend the coordinator's existing ownership (`finishingPacks` pattern): the
   failure state claims the pack; release happens on dismiss or after the export finishes.
4. Exporting or opening the saved file is never evidence of success and never records the
   profile as built. A later manual install is detected only by the normal missing-pack refresh.

**I3 — Fallback for classified technical failures**

1. Introduce an explicit classification of the final Ackpine failure: technical
   (session dead/abandoned, installer activity missing, session creation error, unknown
   generic failure) versus terminal (user abort, conflict, incompatible, invalid, storage,
   blocked by policy).
2. Only technical failures move on to the compatibility intent route, once per attempt. The
   existing dead-session path becomes one case of this table, not a separate branch.
3. Keep the attempt list so the diagnostic report shows every route tried.

Device checks (C2): failed install → Save APK → file appears in the chosen folder and installs
manually; dismissing the dialog afterwards does not crash or leave the build busy; cancelling
the system dialog never offers an automatic retry; a conflict still shows the Replace prompt.

### C3 — Launcher discovery and guidance (L2 + L1 + L4)

Branch: `feature/launcher-compat`.

**L2 — Missing discovery filter**

Add an intent filter with `org.adw.ActivityStarter.THEMES` (category `DEFAULT`) to
`app/src/main/res/xml/manifest.xml`, next to the existing ADW filter. Verify the generated
pack's manifest. Device check with Lawnchair and Kvaesitso. Do not add another Nova filter (L3).

**L1 — HOME-aware guidance**

1. Resolve the default HOME activity (`Intent.ACTION_MAIN` + `CATEGORY_HOME`,
   `resolveActivity`) in the data layer; expose package and label to the ViewModel.
2. A small, data-driven table maps known package names to guidance. Only entries backed by
   evidence: Lawnchair, Kvaesitso, Nova, Niagara, Smart Launcher (pick the pack in settings);
   Samsung One UI Home (Good Lock → Theme Park); Pixel Launcher (no icon pack support).
3. Everything else, including Launcher3 forks and OEM launchers, gets neutral text: "Your
   launcher (<label>) may support icon packs in its own settings."
4. Show it in the first-install dialog and add the launcher label to the diagnostic report, so
   issue #16 reporters give us the missing data automatically.

**L4 — Extend refresh guidance**

Append to the update dialog: if switching packs does not refresh icons, force-stop or restart
the launcher. Never stop the launcher automatically.

Device checks (C3): Lawnchair, Kvaesitso, Nova (if available), Pixel Launcher and One UI Home
each show the right guidance; the pack appears in Lawnchair/Kvaesitso pickers; diagnostic report
contains the HOME label.

### C4 — Background confirmation, signature pre-check, contextual help (I4 + I7 + I8)

Branches: `feature/install-confirm-notification`, `fix/install-signature-precheck`,
`feature/install-failure-help`.

- **I4**: when Renkin is in the background and the system needs confirmation, rely on Ackpine's
  notification-based confirmation for that attempt; request `POST_NOTIFICATIONS` through the
  existing permission helper before the first install. A denied permission never blocks a
  foreground install. Keep confirmation notifications separate from the success toast.
- **I7**: before installing over an existing pack, compare its signing certificates
  (`GET_SIGNING_CERTIFICATES`, honour signing history via `hasSigningCertificate`) with the
  pack keystore certificate. A mismatch goes directly to the existing Replace prompt with an
  explanation. Never uninstall automatically.
- **I8**: map concrete failure classes to help: user-restriction/USB-install errors on Xiaomi
  ("Install via USB"), Samsung Auto Blocker on policy blocks, Play Protect on scan aborts,
  `INSTALL_FAILED_HYPEROS_ISOLATION_VIOLATION`. Brand only selects the wording.

### Later or separate decisions

| Item | Decision |
|---|---|
| I5 timeouts | Diagnostics only. No short timeout that could start a second install while the user or Play Protect is still deciding. |
| I9 developer verification | Track official changes; add a failure class and help text when a real verification error is observed. No promises about Shizuku. |
| I10 unknown-sources pre-check | Evaluate a shared pre-check for API 30; not before privileged routes, not duplicating Ackpine. |
| I11 silent updates | Deferred; product decision. |
| I12 FileProvider | No rewrite; narrowing shared paths is a separate inventory task. |
| L3 extra Nova filter | Rejected; existing filter matches action-only queries. |
| L5 Lawnchair themed icons | Separate feature: declare `app.lawnchair.icons.THEMED_ICON` only with a valid `grayscale_icon_map.xml` and real alpha monochrome glyphs. |
| L6 pack targetSdk 35/36 | Separate compatibility checkpoint; must verify the generated APK and the bundled framework table; not a proven Play Protect fix. |

## 5. Review checklist for each checkpoint

- Positive evidence for every success; cancellation still fast; no second concurrent install.
- Artifact ownership traced from build to release, including export.
- No new Main-thread or gate-held I/O; cancellation propagates.
- Failure classification table covered by unit tests after device confirmation.
- Diagnostics: every attempted route, its label, the failure class and (from C3) the HOME label.
- Strings English in `values/strings.xml`; changelog entry only for verified user-visible changes.
- Commits single-line English, no trailers; merge only after explicit approval.
