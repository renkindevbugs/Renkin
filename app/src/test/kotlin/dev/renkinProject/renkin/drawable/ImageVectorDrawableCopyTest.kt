package dev.renkinProject.renkin.drawable

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageVectorDrawableCopyTest {

    @Test
    fun deepCopy_canBeMutatedWithoutChangingSource() {
        val vector = ImageVector.Builder("copy", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = PathData { moveTo(0f, 0f); lineTo(10f, 10f) },
                stroke = SolidColor(Color.Red),
                strokeLineWidth = 1f
            )
            .build()
        val source = ImageVectorDrawable(vector)

        val copy = source.deepCopy()
        copy.viewportWidth = 48f
        copy.root.children.clear()

        assertEquals(24f, source.viewportWidth)
        assertTrue(source.root.children.isNotEmpty())
    }
}
