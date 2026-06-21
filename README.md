<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="Renkin icon">
</p>

<h1 align="center">Renkin</h1>

<p align="center">Build your own Android icon pack, right on your phone.</p>

---

Renkin is an on-device icon pack creator. Instead of installing a finished pack, you make one yourself: pull icons from any installed icon pack, trace or edge-detect app icons, turn app names into clean text icons, recolour everything to taste, and build a real, installable icon pack — no PC, no account, fully offline.

It started as a fork of [Alembicons](https://codeberg.org/kaanelloed/Alembicons) by kaanelloed (originally *Iconeration*). Big thanks to them for the original app — Renkin continues that work with its own direction and a few extra features, like watching icon packs for newly added icons.

## Features

**The basics**
- Pull an icon from any installed icon pack
- Path-trace an app icon (colour quantization)
- Canny edge detection on an app icon
- Turn the app name into a text icon — first letter, two letters, or the full name — using the [Arcticons font](https://github.com/Arcticons-Team/Arcticons-Font)
- Pick your icon colour

**Going further**
- Use an app's vector or monochrome icon when it has one
- Export as themed icons
- Import your own image for a specific app
- Create or hand-edit an icon's vector
- **Watch icon packs** — get notified when a pack you follow adds an icon for an app you don't have one for yet

## Built with

Kotlin · Jetpack Compose (Material 3) · Hilt · Room. The icon pack APK is built and signed entirely on-device.

## For developers

The layered architecture, dependency injection, persistence and the various gotchas are written up in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## License

Renkin is licensed under the GPLv3, the same as the upstream project. See [LICENSE](LICENSE).
