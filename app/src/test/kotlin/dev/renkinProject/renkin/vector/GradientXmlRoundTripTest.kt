package dev.renkinProject.renkin.vector

import android.app.Application
import android.util.Base64
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.renkinProject.renkin.drawable.ImageVectorDrawable
import dev.renkinProject.renkin.icon.parser.XmlNodeParser
import dev.renkinProject.renkin.vector.brush.GradientMatrix
import dev.renkinProject.renkin.vector.brush.GradientType
import dev.renkinProject.renkin.vector.brush.GradientXml
import dev.renkinProject.renkin.vector.brush.VectorGradientBrush
import dev.renkinProject.renkin.xml.XmlDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stored editor vectors carry gradients as one flat `<renkin-gradient>` child per painted slot, next to
 * a solid fallback colour that builds without gradient support keep painting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class GradientXmlRoundTripTest {
    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    private val radial = VectorGradientBrush.of(
        GradientType.RADIAL, listOf(Color(0xFF8B52F4), Color(0x80EA71EF), Color(0xFF00FF00)),
        listOf(0.114f, 0.6f, 1f), 0f, 0f, 0f, 0f, 1f, TileMode.Mirror,
        GradientMatrix(0f, -12.4917f, 17.6003f, 0f, 11.494129f, 15.977278f)
    ) as VectorGradientBrush
    private val linear = VectorGradientBrush.of(
        GradientType.LINEAR, listOf(Color.Red, Color.Blue), listOf(0f, 1f),
        2f, 3f, 20f, 21f, 0f, TileMode.Clamp, GradientMatrix.IDENTITY
    ) as VectorGradientBrush

    private fun vector(vararg paints: Pair<Brush?, Brush?>): ImageVector =
        ImageVector.Builder("test", 24.dp, 24.dp, 24f, 24f).apply {
            for ((fill, stroke) in paints) addPath(
                PathData { moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); close() },
                fill = fill, stroke = stroke, strokeLineWidth = if (stroke != null) 1f else 0f
            )
        }.build()

    private fun export(vector: ImageVector, includeGradients: Boolean = true): ByteArray =
        VectorExporter(vector, includeGradients).toXml()

    private fun parse(xml: ByteArray): List<VectorPath> {
        val nodes = XmlDecoder.fromBase64(Base64.encodeToString(xml, Base64.NO_WRAP))
        val drawable = XmlNodeParser.parse(resources, nodes, Color.Unspecified) as ImageVectorDrawable
        return drawable.toImageVector().root.flatten()
    }

    @Test
    fun fillAndStrokeGradientsSurviveExactlyAndReExportByteIdentical() {
        val xml = export(vector(radial to null, null to linear, SolidColor(Color.Green) to null))
        val paths = parse(xml)

        assertEquals(radial, paths[0].fill)
        assertEquals(linear, paths[1].stroke)
        assertEquals(SolidColor(Color.Green), paths[2].fill)
        assertArrayEquals(xml, export(vector(paths[0].fill to null, null to paths[1].stroke, paths[2].fill to null)))
    }

    @Test
    fun gradientChildIsFlatAndFallbackColourIsOpaqueMean() {
        val text = String(export(vector(radial to null)))
        assertTrue(text.contains("<${GradientXml.TAG} "))
        assertFalse(Regex("<${GradientXml.TAG}[^>]*[^/]>").containsMatchIn(text))
        assertFalse(text.contains("android:fillColor=\"#00000000\""))
    }

    @Test
    fun apkExportWritesOnlyTheSolidFallback() {
        val text = String(export(vector(radial to null), includeGradients = false))
        assertFalse(text.contains(GradientXml.TAG))
        val fill = parse(text.toByteArray()).single().fill as SolidColor
        assertEquals(radial.fallbackColor().toArgbHex(), fill.value.toArgbHex())
    }

    @Test
    fun malformedGradientChildFallsBackToTheSolidColour() {
        val broken = String(export(vector(radial to null))).replace(Regex("offsets=\"[^\"]*\""), "offsets=\"0.9 0.1 0.5\"")
        val fill = parse(broken.toByteArray()).single().fill
        assertTrue(fill is SolidColor)
        assertNotEquals(Color.Transparent, (fill as SolidColor).value)
    }

    @Test
    fun gradientPathsFollowedBySolidPathsAllSurvive() {
        val xml = export(vector(radial to null, linear to null, SolidColor(Color.Red) to null, SolidColor(Color.Blue) to null))
        assertEquals(4, parse(xml).size)
    }

    private fun VectorGroup.flatten(): List<VectorPath> = flatMap {
        when (it) { is VectorPath -> listOf(it); is VectorGroup -> it.flatten() }
    }

    private fun Color.toArgbHex() = "%08x".format(android.graphics.Color.argb(alpha, red, green, blue))
}
