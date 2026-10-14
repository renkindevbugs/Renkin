package dev.renkinProject.renkin.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start bounds: right after installation (no ahead-of-time code) and after the background
 * dexopt that sideloaded and F-Droid installs eventually receive.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartJustInstalled() = coldStart(CompilationMode.None())

    @Test
    fun coldStartFullyCompiled() = coldStart(CompilationMode.Full())

    private fun coldStart(mode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.COLD,
        compilationMode = mode
    ) {
        pressHome()
        launchMainActivity()
    }
}
