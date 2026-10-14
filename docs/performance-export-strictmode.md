# Export freeze check with StrictMode (2026-10-14)

A Reddit post about Icon Pack Studio's export going unresponsive also mentioned Renkin. On a second
reading the freeze is in Icon Pack Studio; this check confirms Renkin's own build path.

## Method

Debug builds now enable `StrictMode` (`util/DebugStrictMode.kt`, log-only, never in release):
main-thread disk and network access, custom slow calls, leaked closeables and SQLite objects.
On a OnePlus Nord 4 (Android 16) the debug app was cold-started and the 216-icon `gff` profile
was built through Build icon pack → Pack preview → Build; the in-app installer chooser was
dismissed without installing. App data was backed up first and restored afterwards.

## Results

- The build reached the installer chooser within about 6 s (the first UI poll already saw it).
- During the build no StrictMode violation had a Renkin frame on the main thread. All 34 disk
  violations came from the OEM framework (`com.oplus.uifirst.Utils.writeProcNode`) and from
  `startActivity` binder replies; building, signing and the installer handoff stay on
  `Dispatchers.Default`/`IO` or asynchronous sessions.
- One real leak: the external `ApkSignerCompat` (`app.revanced.library.ApkUtils`) opened the pack
  keystore through a `FileInputStream` it never closed, once per build. `IconPackBuilder.signApk`
  now reads the existing keystore itself with `use {}` and signs through the library's public
  `ApkSigner` API; first-run keystore creation still goes through `ApkUtils`. Packs built before
  and after the change carry the same certificate
  (`5342b3cb2f951c6b5027a287f63b7fac067fd21fc2ad26b9901e27c9d8ae4a53`), so installed packs
  still update.
- Startup: `MainActivity` read the full text of every crash log on the main thread only to
  compare timestamps. The launch check now reads file names only and runs on `Dispatchers.IO`.
  Remaining startup reads are small (`IconPackBuildService` construction through Hilt).

## Follow-ups

- Ask the reporter for device, Android and Renkin versions and profile size if Renkin itself
  ever stalls; the trace above shows no Renkin-side cause.
