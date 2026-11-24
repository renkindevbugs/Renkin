# Decisions

Why Renkin behaves the way it does, so the same ground is not re-litigated. Read the section for
the area you are about to change. If a change would contradict an entry, stop and ask the
developer instead of "fixing" it. When the developer approves or rejects something, add or
update the entry in the same change.

Entry format: **Decision** (what holds now) · **Why** (the problem it solved, issue/commit) ·
**Rejected** (what was tried and why it failed) · **Do not change without** (the check that
must come first). UI and visual decisions live in `.interface-design/system.md`; this file only
links to them.

## Material You and themed icons

This area has flipped several times. Before touching `ThemedGlyphDrawable`, `ThemedGlyphColors`,
`MaterialYouSchemes.kt`, `IconPackBuilder.themedColors` / `createGlyphResource`,
`IconGenerator.generateMaterialYou*` or `refreshImageEdit`, read all of it.

### MY-1 Live glyphs reference the system palette

- **Decision:** an icon painted in one of the five Material You schemes is exported as a
  `ThemedGlyphDrawable`: an alpha mask tinted with `@color/renkin_scheme<N>_fg` over
  `..._bg`, and from Android 12 those colours point at `@android:color/system_accent*`. The icon
  then follows later wallpaper changes without a rebuild.
- **Why:** before `1d60f86e` (2026-11-15) the variant was a baked bitmap and went stale after the
  first wallpaper change.
- **Rejected:** baking the scheme colours (the old behaviour) for the default colour source.
- **Do not change without:** a build checked on a launcher in themed mode and after a wallpaper
  change.

### MY-2 Custom colours and gradients stay as painted

- **Decision:** a custom foreground/background pair or a gradient is the user's own choice and
  is exported as painted, not turned into a live glyph (`liveMaterialYouScheme` returns null).
  Only the global themed export replaces every colour with the launcher's tint.
- **Why:** users pick custom colours precisely so they do not follow the wallpaper.
- **Open:** PR #34 (2026-11-18) would make every Material You icon live, custom pairs included,
  whenever the themed export is on. The 2026-11-24 review advises against merging it as is
  (see MY-5); the developer decides.

### MY-3 Read the palette from the application's resources

- **Decision:** `ThemedGlyphColors` resolves colours from `context.applicationContext.resources`
  (installed in `RenkinApplication.onCreate`), never `Resources.getSystem()`.
- **Why:** issue #33. `Resources.getSystem()` keeps the palette the zygote inherited and reads
  stale (blue) colours after a wallpaper change. Fix `3863a420` (on
  `fix/material-you-themed-export`, awaiting device check).

### MY-4 Glyph icons carry a `<monochrome>` layer and the Material You category

- **Decision:** a glyph icon gets a `drawable-anydpi-v33` adaptive variant with `<monochrome>`,
  and the pack declares `org.icontheme.CHANGES_WITH_MATERIAL_YOU_COLORS` whenever any icon is a
  `ThemedGlyphDrawable` (not only for a globally themed pack).
- **Why:** issue #33. Without the monochrome layer launchers in themed-icon mode turned the icon
  into a solid square; without the category Smart Launcher never refreshed the icons after a
  palette change. Fix `3863a420` (same branch as MY-3).

### MY-5 Themed export recolours only one-colour glyphs

- **Decision:** in the global themed export a bitmap becomes a tinted mask only when
  `isSingleColorGlyph()` holds. Multi-colour artwork keeps its pixels. Material You packs keep
  their own vectors, insets and stroke scaling (`03f8f0cc`, 2026-11-15).
- **Why:** a silhouette of multi-colour artwork loses every detail drawn with colour instead of
  transparency (a white letter on a red disc becomes a plain disc).
- **Open:** PR #34 replaces the check with "any transparent pixel" and forces the Material You
  variant for every app icon. It fixes icons whose antialiased edges fail the check, but turns
  colourful artwork into blobs and overrides the profile's variant choice. The review suggests a
  tolerance fix in `isSingleColorGlyph` instead; the developer decides.
- **Do not change without:** comparing a Lawnicons pack and a colourful pack in themed mode.

### MY-6 Refresh does not force the Themed modifier under the themed export

- **Decision:** `refreshImageEdit` gives Material You pack icons the Themed modifier only when the
  pack-wide themed export is off (`!export.themed`, `922bf54e`).
- **Why:** the pack-wide themed export already keeps those icons live on its own.

### MY-7 Colour source: Android palette or the wallpaper itself

- **Decision:** Settings → Material You colours offers Android (default, MY-1 behaviour) or
  Wallpaper. With Wallpaper, colours are derived from `WallpaperManager.getWallpaperColors`
  (Tonal spot, like Android) and baked into the pack; the pack then does not declare the
  Material You category, and Renkin offers a rebuild when the wallpaper changes.
- **Why:** OnePlus (2026-11-18) kept the lock screen's blue palette after a home wallpaper change,
  so every app following the system palette (Lawnicons and Arcticons included) stayed blue.
- **Status:** phase 1 on `feature/wallpaper-colors`, not merged yet. Silent Shizuku auto-rebuild
  is phase 2.
- **Design:** `.interface-design/system.md` → "Wallpaper colours".

## Pack export

### EX-1 Adaptive foreground is a single-item layer list, except dynamic clocks

- **Decision:** `AdaptiveIconXml.foreground` wraps the reference in a one-item `<layer-list>`.
  A dynamic clock foreground, which already is the clock's layer list, stays a direct reference.
- **Why:** the Moto launcher casts a clock app's foreground to `LayerDrawable` and drops the icon
  otherwise (PR #36 by Mr-WHT). Wrapping a clock's own layer list again would shift the hour and
  minute indices the launcher reads from appfilter (`690fb010`).

## Editor and home

- **ED-1** The editor closes at once on back or ✕, with no "discard changes?" prompt. The
  developer rejected the prompt (2026-11-15).
- **ED-2** The adaptive switch stays in the Upload tab. Moving it to Modifier was built and
  discarded (2026-11-15).
- **ED-3** No startup dialogs for unsaved or lost work; only the restore icon next to the profile
  name (2026-11-15).
- **ED-4** The install success dialog stays generic. Per-launcher guidance was removed on purpose
  in `3b31c581`.
- **ED-5** An animation must never add waiting time (rejected: pick flight from Create/Upload
  tiles, bell reveal into Watched icons).

## Build

### BD-1 Release builds are shrunk but not obfuscated

- **Decision:** `app/proguard-rules.pro` sets `-dontobfuscate` and keeps `SourceFile` and
  `LineNumberTable`. R8 still shrinks and optimises.
- **Why:** Renkin is open source, so renaming hides nothing; obfuscated crash logs needed the
  exact build's `mapping.txt`, which had to be copied and attached to every release. Mihon and
  Morphe do the same (2026-11-24).
- **Rejected:** keeping obfuscation and publishing a mapping file with each GitHub release.
