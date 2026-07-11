# Renkin — AI assistant rules

Renkin builds installable Android icon packs on-device (Jetpack Compose, Kotlin, Material 3
Expressive; fork of Alembicons). Read `docs/ARCHITECTURE.md` before touching code — it has the
layer map, naming table, persistence rules and UI conventions.

## Current focus (2026-07: fix the Recolor outline mode)

The Outline modifier (GitHub issue #1) shipped with three modes in the Modifier tab —
Add / Recolor / None plus a live eraser (`icon/creator/IconOutline.kt`, `ui/EraseDialog.kt`).
**Add and the eraser work on-device; Recolor does NOT yet** — on the Komikku test icon
(a blue ring around a pale disc) the recolour still comes out wrong; exact failure mode
unverified (developer parked it; ask for a screenshot with the current build first).

Current Recolor implementation: BFS flood from the transparency boundary inward, stopping
when a 4-neighbour differs by >40 per RGB channel or `outlineWidth` depth is reached, then
an HSV transfer (target hue+sat, per-pixel V scaled against the flood core's max V).

Debug FIRST, then fix — likely tools/techniques, in rough order of value:
1. **Separate selection from recolouring**: add a temporary debug path that tints the flood
   SELECTION magenta in the preview. One screenshot then shows whether the flood picks the
   wrong pixels or the HSV transfer paints them wrong.
2. **Ground truth**: pull the actual drawable the pipeline sees (it may be the pack's
   adaptive foreground, resized, or our vector rasteriser's output — not the on-store PNG)
   by dumping `applyAdjustments`' input bitmap to filesDir and inspecting it.
3. Selection candidates if the flood is wrong: seed-relative tolerance in **CIELAB (ΔE)**
   instead of neighbour-chained RGB; or select by **dominant boundary hue histogram**
   across the whole icon (also catches disconnected outline segments); or compute the
   boundary band with a **two-pass chamfer distance transform** instead of BFS depth.
4. Recolour candidates if the paint is wrong: **hue ROTATION** by (targetHue − dominantHue)
   instead of hue replacement (preserves multi-hue gradients); alpha-weighted handling of
   antialiased fringe pixels (ignore alpha < ~40 as flood seeds, recolour them by blend).
5. Watch out: low-alpha unpremultiplied pixels carry noisy RGB — never let them drive the
   reference colour.

Everything else from the outline feature is merged and device-verified; keep new work on a
`fix/recolor-outline` branch. Remove this section once Recolor is confirmed on-device.

## Build & verify

- Compile: `JAVA_HOME="D:/Android/jbr" ./gradlew compileDebugKotlin` (Git Bash / POSIX syntax)
- Unit tests: `./gradlew testDebugUnitTest`
- You cannot run the app — the developer tests on a physical device. Rely on compile + tests,
  and after implementing anything, tell the developer exactly what to test manually.

## Workflow (non-negotiable)

- New git branch per feature/fix (`feature/...` or `fix/...`), commit there, and **never merge
  until the developer explicitly approves after on-device testing**. Day-to-day work happens on
  `dev`; `main` is releases (ask before dev→main).
- Commits: single line, ENGLISH, descriptive. **No Co-Authored-By or any other trailer, ever.**
- Code comments in English; explain the "why", not the "what".
- Strings: only `app/src/main/res/values/strings.xml` (English). **Never add translations**
  (no values-xx folders).
- Keep it DRY and MVVM: UI → MainViewModel/WatchViewModel → ApplicationProvider/repos; no I/O
  in composables. Reuse the shared widgets (ControlWidgets.kt, UIHelper.kt, ScrollChrome.kt)
  and the shape tokens in `ui/theme/Shapes.kt` — no raw `RoundedCornerShape(n.dp)`.
- For new UX features the developer likes a quick visual mockup / flow plan before coding.
- Never put the developer's real name anywhere (code, commits, docs).
- The gitignored `mihon-reference/` clone in the repo root is the UI inspiration source —
  when asked to mimic Mihon behaviour, read it there.

## Traps — do not break these

- **Never lower a Room DB version** once any build was installed. Schema-identical bump +
  migration instead (see the migration history in `data/DbApplication.kt`).
- `dev.alembiconsProject.imagetracer`, `dev.alembiconsProject.tgCannyEdgeCompose` and the
  `com.github.Alembicons` gradle coordinates are EXTERNAL libraries — never rename them.
- The generated pack's dex classes come from prebuilt smali assets
  (`app/src/main/assets/{R,RLayout,MainActivity,BuildConfig}`); the pack applicationId is
  parameterized per profile, but the smali class package must match IconPackBuilder's activity
  FQN string. Keystore: `renkinpack.keystore` in filesDir.
- `ic_launcher_monochrome` PNGs are alpha glyphs (Material You tints alpha only) — never
  replace them with opaque images.
- Never put `Drawable`/`ResourceDrawable`/`IconPackDrawable` into `rememberSaveable` — they
  aren't Parcelable and crash on background save. Plain `remember` only.
- The upstream attribution stays (InfoDialog F-Droid link, `aboutFork` strings).

## Key concepts (short version — details in docs/ARCHITECTURE.md)

- **Profiles**: multiple named icon sets; each owns its icons, prefs snapshot, watch rules and
  its own pack APK (`dev.renkinProject.renkinpack[.p<id>]`). Default (id=1) is undeletable.
- **Refresh semantics**: bulk refresh fills empty slots and replaces only its own unsaved
  output; hand-picked/built icons are locked unless "Refresh replaces existing icons" is on.
- **Component-first search**: packs identify apps by ComponentInfo in appfilter.xml, never by
  name; the edit dialog shows each pack's appfilter-mapped icon first for the default query.
- **Icon watch**: per-profile rules checked by the periodic WatchWorker; notifications
  deep-link into the owning profile (and explain themselves if it was deleted).
