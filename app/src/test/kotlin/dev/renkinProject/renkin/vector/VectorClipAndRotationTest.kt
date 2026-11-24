package dev.renkinProject.renkin.vector

import android.app.Application
import android.util.Base64
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.renkinProject.renkin.drawable.ImageVectorDrawable
import dev.renkinProject.renkin.icon.parser.XmlNodeParser
import dev.renkinProject.renkin.vector.VectorEditor.Companion.applyAndKeepClipGroups
import dev.renkinProject.renkin.vector.VectorEditor.Companion.applyAndRemoveGroup
import dev.renkinProject.renkin.xml.XmlDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class VectorClipAndRotationTest {
    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    private fun vector(rotation: Float = 0f, clip: Boolean = false, path: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder("test", 24.dp, 24.dp, 24f, 24f).apply {
            addGroup(
                rotate = rotation,
                clipPathData = if (clip) PathData { moveTo(0f, 0f); lineTo(12f, 0f); lineTo(12f, 12f); close() }
                    else emptyList()
            )
            addPath(PathData(path), fill = SolidColor(Color.Black))
            clearGroup()
        }.build()

    private fun roundTrip(vector: ImageVector): ImageVector {
        val xml = VectorExporter(vector, true).toXml()
        val nodes = XmlDecoder.fromBase64(Base64.encodeToString(xml, Base64.NO_WRAP))
        return (XmlNodeParser.parse(resources, nodes, Color.Unspecified) as ImageVectorDrawable).toImageVector()
    }

    @Test
    fun rotatedHorizontalSegmentBecomesTheMatchingVerticalLine() {
        val flattened = ImageVectorDrawable(vector(rotation = 90f) { moveTo(0f, 0f); horizontalLineToRelative(10f) })
            .applyAndRemoveGroup().toImageVector()

        val bounds = (flattened.root.single() as VectorPath).pathData.toPath().getBounds()

        assertEquals(0f, bounds.width, 0.01f)
        assertEquals(10f, bounds.height, 0.01f)
    }

    @Test
    fun clipGroupSurvivesFlatteningAndAnXmlRoundTrip() {
        val kept = ImageVectorDrawable(vector(clip = true) { moveTo(0f, 0f); lineTo(24f, 24f) })
            .applyAndKeepClipGroups().toImageVector()

        val clipped = roundTrip(kept).root.groups().filter { it.clipPathData.isNotEmpty() }

        assertEquals(1, clipped.size)
        assertTrue(clipped.single().single() is VectorPath)
    }

    // The exporter writes the root as its own <group>, so groups are searched at any depth.
    private fun VectorGroup.groups(): List<VectorGroup> =
        filterIsInstance<VectorGroup>().flatMap { listOf(it) + it.groups() }
}
