<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" alt="Renkin">
</p>

<h1 align="center">Renkin</h1>

<p align="center">Make your own Android icon pack, on your phone.</p>

---

Renkin builds a real, installable icon pack from your own choices — no PC, no account, offline.
Pick icons from packs you already have, redraw or recolour them, then compile and sign the pack
on-device and select it in your launcher.

Fork of [Alembicons](https://codeberg.org/kaanelloed/Alembicons) by kaanelloed (formerly
*Iconeration*), with its own direction on top.

## Features

- Source an icon from any installed icon pack, the app's own icon, or its name as text
- Modify icons: path tracing, edge detection, colorize, background removal, vector editing
- Import your own image per app; use an app's vector or monochrome layer when it has one
- Themed (Material You) icon export
- **Profiles** — several named icon sets, each with its own built pack, side by side
- **Icon watch** — get notified when a followed pack adds an icon for one of your apps
- Calendar day icons that rotate with the date

## Build

Kotlin · Jetpack Compose (Material 3) · Hilt · Room. The pack APK is assembled and signed
entirely on-device.

```
JAVA_HOME="<jdk-17>" ./gradlew assembleRelease
```

Architecture, persistence and the gotchas: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## License

GPLv3, same as upstream. See [LICENSE](LICENSE).
