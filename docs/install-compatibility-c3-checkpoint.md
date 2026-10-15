# Installation compatibility C3 checkpoint — 2026-10-15

Branch: `feature/launcher-compat`, based on `dev` at `c1d1faa6` (C1 + C2 merged).
Source plan: [install-compatibility-plan.md](install-compatibility-plan.md).

Status: verified on a OnePlus CPH2663 (Android 16) and approved by the developer; committed on
this branch, not merged. Device results: with the OnePlus launcher ("Systémový spúšťač") the
first-install dialog showed the neutral UNKNOWN guidance; with Lawnchair as default it showed the
Lawnchair guidance; the update dialog carried the force-stop sentence; the generated pack answered
`org.adw.ActivityStarter.THEMES` queries (one action more than an older pack). Not exercised: the
pack list inside Lawnchair/Kvaesitso (third-party UI) and the report's `Launcher:` line.
`HomeLauncherTest` covers the support table, resolver cases and the pack manifest action. Full
unit suite and `assembleDebug` pass.

## L2 — Discovery filter

`res/xml/manifest.xml` gains `org.adw.ActivityStarter.THEMES` (category DEFAULT), queried by
Lawnchair and Kvaesitso. `IconPackBuilder.createMainActivity` copies every filter, so the generated
pack declares it. The Material You category stays only on the ADW `THEMES` filter, as before.

## L1 — Home-aware first-install guidance

- `apk/HomeLauncher.kt` resolves the default HOME activity (`MATCH_DEFAULT_ONLY`; the platform
  chooser `android` counts as "no default") and labels it. The resolution runs on IO at the start
  of each install attempt in `BuildInstallCoordinator`, never in Compose.
- `launcherIconPackSupport` is the evidence-backed table: Lawnchair, Kvaesitso, Nova, Niagara and
  Smart Launcher → choose the pack in settings; One UI Home → Good Lock / Theme Park; Pixel
  Launcher → unsupported. Everything else, including OEM launchers and Launcher3 forks such as the
  OnePlus/ColorOS `com.android.launcher`, is UNKNOWN and gets neutral wording.
- The first-install dialog keeps "“pack” was installed via installer." and adds one guide
  sentence; with no default home app it shows the previous generic sentence.
- Failure reports gain `Launcher: <label> (<package>)` so reports like issue #16 carry it.

## L4 — Refresh guidance

The update dialog adds: if icons still do not change, force-stop the launcher in Android's app
settings. Renkin never stops a launcher itself.

## Manual device checks

The test phone has Lawnchair, Kvaesitso, Nova, Niagara, Smart Launcher, Microsoft Launcher and the
OnePlus launcher installed; switching the default home app is the developer's action.

1. With the OnePlus launcher as default, first-install a test pack. Expect "If your launcher
   (System Launcher) supports icon packs, choose “…” in its settings."
2. Make Lawnchair the default home app and first-install another test pack. Expect "In the
   Lawnchair settings, choose “…”". Open Lawnchair's icon pack list: the pack is listed.
3. Repeat with Kvaesitso: guidance names Kvaesitso; the pack appears in its icon pack list.
4. Microsoft Launcher as default: neutral UNKNOWN wording.
5. Update a pack: dialog shows the switch-away-and-back advice plus the force-stop sentence.
6. Produce a failure report (APK viewer as installer, wait over two minutes) and copy details:
   expect a `Launcher:` line.
7. Restore the original default home app.

Regression tests after approval: support table; resolver with no default / chooser / labelled
app (Robolectric); generated manifest contains the new action.
