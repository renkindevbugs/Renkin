package dev.renkinProject.renkin.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Frame timing of application-list flings after a warm start. */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun applicationListJustInstalled() = scroll(CompilationMode.None())

    @Test
    fun applicationListFullyCompiled() = scroll(CompilationMode.Full())

    private fun scroll(mode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        compilationMode = mode,
        setupBlock = {
            pressHome()
            launchMainActivity()
        }
    ) {
        scrollApplicationList()
    }
}
