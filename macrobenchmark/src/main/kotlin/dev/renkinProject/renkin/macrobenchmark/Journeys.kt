package dev.renkinProject.renkin.macrobenchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

/** The app's release-like "benchmark" build, never the installed release app. */
internal const val TARGET_PACKAGE = "dev.renkinProject.renkin.benchmark"

private const val LIST_TIMEOUT_MS = 15_000L
private const val MAIN_ACTIVITY = "dev.renkinProject.renkin.MainActivity"

/**
 * Cold or warm start through `am start -W`. Macrobenchmark's startActivityAndWait() confirms the
 * launch through gfxinfo frame stats, which this OnePlus build never reports; StartupTimingMetric
 * reads the launch from the system trace instead. A fresh install also skips the first-run tour.
 */
internal fun MacrobenchmarkScope.launchMainActivity() {
    device.executeShellCommand("am start -W -n $TARGET_PACKAGE/$MAIN_ACTIVITY")
    device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), LIST_TIMEOUT_MS)
    device.wait(Until.findObject(By.text("Skip")), 2_000L)?.click()
    device.waitForIdle()
}

/** Flings the main application list down and back up once it has loaded. */
internal fun MacrobenchmarkScope.scrollApplicationList() {
    val list = device.wait(Until.findObject(By.scrollable(true)), LIST_TIMEOUT_MS)
        ?: error("The application list did not appear")
    // Keep flings away from the system gesture areas at the screen edges.
    list.setGestureMargin(device.displayWidth / 5)
    repeat(3) {
        list.fling(Direction.DOWN)
        device.waitForIdle()
    }
    list.fling(Direction.UP)
    device.waitForIdle()
}
