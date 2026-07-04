# Renkin — AI assistant rules

Renkin builds installable Android icon packs on-device (Jetpack Compose, Kotlin, Material 3
Expressive; fork of Alembicons). Read `docs/ARCHITECTURE.md` before touching code — it has the
layer map, naming table, persistence rules and UI conventions.

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
