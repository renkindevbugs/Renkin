package dev.renkinProject.renkin.drawable

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
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

    @Test
    fun modifierTransform_bakesScaleAndPositionIntoFlatVectorPaths() {
        val vector = ImageVector.Builder("transform", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = PathData { moveTo(4f, 4f); lineTo(20f, 20f) },
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f
            )
            .build()

        val transformed = ImageVectorDrawable(vector)
            .withModifierTransform(scale = 0.5f, offsetX = 0.25f, offsetY = 0f)
            .toImageVector()
        val path = transformed.root.first() as VectorPath
        val bounds = path.pathData.toPath().getBounds()

        assertFalse(transformed.root.any { it is VectorGroup })
        assertEquals(11f, bounds.left, 0.001f)
        assertEquals(19f, bounds.right, 0.001f)
        assertEquals(8f, bounds.top, 0.001f)
        assertEquals(16f, bounds.bottom, 0.001f)
        assertEquals(1f, path.strokeLineWidth, 0.001f)
    }

    @Test
    fun modifierTransform_flattensExistingGroupsAndScalesTheirStroke() {
        val builder = ImageVector.Builder("group", 24.dp, 24.dp, 24f, 24f)
        builder.addGroup(scaleX = 2f, scaleY = 2f)
        builder.addPath(
            pathData = PathData { moveTo(1f, 1f); lineTo(3f, 3f) },
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1f
        )
        builder.clearGroup()

        val transformed = ImageVectorDrawable(builder.build())
            .withModifierTransform(scale = 1f, offsetX = 0f, offsetY = 0f)
            .toImageVector()
        val path = transformed.root.first() as VectorPath
        val bounds = path.pathData.toPath().getBounds()

        assertFalse(transformed.root.any { it is VectorGroup })
        assertEquals(2f, bounds.left, 0.001f)
        assertEquals(6f, bounds.right, 0.001f)
        assertEquals(2f, path.strokeLineWidth, 0.001f)
    }
}
