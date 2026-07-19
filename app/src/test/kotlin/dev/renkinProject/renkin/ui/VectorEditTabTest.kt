package dev.renkinProject.renkin.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VectorEditTabTest {

    @Test
    fun reloadedFilledPath_usesFillInsteadOfTransparentPersistenceStroke() {
        val vector = ImageVector.Builder("reload", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = PathData { moveTo(2f, 2f); lineTo(22f, 22f) },
                fill = SolidColor(Color.White),
                // XML represents an absent stroke as #00000000 and parses it as non-null.
                stroke = SolidColor(Color.Transparent),
                strokeLineWidth = 0f
            )
            .build()
        val entry = PathEntry(
            path = vector.root.first() as VectorPath,
            filled = true,
            baseStroke = 1f
        )

        val rebuilt = entry.toMutablePath(thickness = 1f)

        assertEquals(Color.White, (rebuilt.fill as SolidColor).value)
        assertNull(rebuilt.stroke)
    }

    @Test
    fun reloadedStrokedPath_usesStrokeInsteadOfTransparentPersistenceFill() {
        val vector = ImageVector.Builder("reload", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = PathData { moveTo(2f, 2f); lineTo(22f, 22f) },
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f
            )
            .build()
        val entry = PathEntry(
            path = vector.root.first() as VectorPath,
            filled = false,
            baseStroke = 2f
        )

        val rebuilt = entry.toMutablePath(thickness = 1f)

        assertNull(rebuilt.fill)
        assertEquals(Color.White, (rebuilt.stroke as SolidColor).value)
        assertEquals(2f, rebuilt.strokeLineWidth, 0.001f)
    }
}
