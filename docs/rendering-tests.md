# Rendering reference tests

Roborazzi 1.41.0 is a test-only dependency, with Apache-2.0 licensed upstream sources. This
version uses the project's existing Robolectric 4.14.1; adding it does not upgrade the app's
Kotlin, AGP, Compose or production dependencies. See the [upstream setup and API](https://github.com/takahirom/roborazzi/tree/1.41.0)
and [license](https://github.com/takahirom/roborazzi/blob/1.41.0/LICENSE).

The initial proof of concept renders a fixed local circle through Renkin's actual canonical
bitmap modifier pipeline, with a color gradient and shadow. It runs with native graphics,
SDK 33 and mdpi, without wallpaper colors, installed packs, network access or system fonts.
The checked-in reference is `app/src/test/screenshots/icon-shadow-gradient.png`.
The same reference also verifies a selected stage after complete draft capture, codec round trip,
native binding and recovered request projection with different current UI colors. Recovery must
produce the original pixels without recording a separate golden or accepting a visual change.

`IconRenderingScreenshotTest` explicitly verifies in an ordinary `testDebugUnitTest` run too;
missing references and visual changes must fail rather than silently skip screenshot capture.
To verify only rendering references:

```text
./gradlew verifyRoborazziDebug --tests '*IconRenderingScreenshotTest'
```

After an intentional visual change, record and visually review the updated reference:

```text
./gradlew recordRoborazziDebug --tests '*IconRenderingScreenshotTest'
```

Review the PNG diff before committing it. Recording is not validation and must not be the
default CI command. Comparison output belongs in `app/build/outputs/screenshots_comparison`,
and the Roborazzi HTML report is under `app/build/reports/roborazzi`.

References were initially recorded on Windows. Check native-renderer reproducibility on the
CI host before treating these as cross-platform goldens. The proof of concept covers one
pipeline; adaptive icons, outlines, masks, vector geometry and recovered-source screenshots
remain candidates for expansion after 7C. Domain recovery tests continue checking exact
control values, native-resource ownership, races and pixel equivalence independently.
