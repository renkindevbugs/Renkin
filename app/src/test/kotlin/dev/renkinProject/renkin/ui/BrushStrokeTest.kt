package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.ui.geometry.Offset
import dev.renkinProject.renkin.icon.creator.BrushAction
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class BrushStrokeTest {

    private fun stroke(action: BrushAction) = BrushStroke(
        brush = 0.1f,
        points = listOf(Offset(0.5f, 0.5f)),
        action = action
    )

    @Test
    fun operationsCoalesceOnlyAdjacentMatchingActions() {
        val operations = buildBackgroundBrushOperations(
            listOf(
                stroke(BrushAction.ERASE),
                stroke(BrushAction.ERASE),
                stroke(BrushAction.RESTORE),
                stroke(BrushAction.ERASE)
            ),
            size = 16
        )

        assertEquals(
            listOf(BrushAction.ERASE, BrushAction.RESTORE, BrushAction.ERASE),
            operations.map { it.action }
        )
    }
}
