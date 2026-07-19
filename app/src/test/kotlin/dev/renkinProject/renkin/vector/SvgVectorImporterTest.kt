package dev.renkinProject.renkin.vector

import android.app.Application
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The vector editor's SVG import: viewBox, path extraction, fill/stroke inheritance. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class SvgVectorImporterTest {

    @Test
    fun heroiconOutline_strokedPathsWithInheritedWidth() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24"
                 stroke-width="1.5" stroke="currentColor" class="size-6">
              <path stroke-linecap="round" stroke-linejoin="round" d="M12 3v18" />
              <path d="M3 12h18" />
            </svg>
        """.trimIndent()

        val imported = SvgVectorImporter.parse(svg)!!

        assertEquals(24f, imported.viewportWidth)
        assertEquals(24f, imported.viewportHeight)
        assertEquals(2, imported.paths.size)
        // Root fill="none" + stroke inherit down: stroked paths at the authored 1.5 width.
        assertTrue(imported.paths.all { !it.filled })
        assertTrue(imported.paths.all { it.strokeWidth == 1.5f })
        assertEquals("M12 3v18", imported.paths[0].pathData)
    }

    @Test
    fun heroiconSolid_isFilled() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor">
              <path fill-rule="evenodd" d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Z" clip-rule="evenodd" />
            </svg>
        """.trimIndent()

        val imported = SvgVectorImporter.parse(svg)!!

        assertEquals(1, imported.paths.size)
        assertTrue(imported.paths.single().filled)
        assertNull(imported.paths.single().strokeWidth)
        assertEquals(PathFillType.EvenOdd, imported.paths.single().fillType)
    }

    @Test
    fun unspecifiedFill_defaultsToFilledPerSvgSpec() {
        val svg = """<svg viewBox="0 0 10 10"><path d="M0 0h10v10H0Z"/></svg>"""

        val imported = SvgVectorImporter.parse(svg)!!

        assertTrue(imported.paths.single().filled)
    }

    @Test
    fun perPathAttributesOverrideRoot() {
        val svg = """
            <svg viewBox="0 0 24 24" fill="none" stroke="black" stroke-width="2">
              <path d="M1 1h4" fill="red" stroke="none" />
              <path d="M1 5h4" stroke-width="3" />
            </svg>
        """.trimIndent()

        val imported = SvgVectorImporter.parse(svg)!!

        assertTrue(imported.paths[0].filled)
        assertFalse(imported.paths[1].filled)
        assertEquals(3f, imported.paths[1].strokeWidth)
    }

    @Test
    fun widthHeightFallback_whenNoViewBox() {
        val svg = """<svg width="48px" height="24"><path d="M0 0h10"/></svg>"""

        val imported = SvgVectorImporter.parse(svg)!!

        assertEquals(48f, imported.viewportWidth)
        assertEquals(24f, imported.viewportHeight)
    }

    @Test
    fun unusableInputs_areNull() {
        assertNull(SvgVectorImporter.parse("not xml at all"))
        // A shape whose attributes describe nothing drawable rejects the document.
        assertNull(SvgVectorImporter.parse("""<svg viewBox="0 0 24 24"><rect width="0" height="5"/></svg>"""))
        assertNull(SvgVectorImporter.parse("""<svg><path d="M0 0h1"/></svg>"""))
    }

    @Test
    fun basicShapes_convertToEquivalentPaths() {
        val svg = """
            <svg viewBox="0 0 24 24">
              <rect x="2" y="3" width="10" height="4"/>
              <circle cx="12" cy="12" r="5"/>
              <polygon points="1,1 5,1 3,4"/>
              <line x1="0" y1="0" x2="6" y2="6" stroke="black" stroke-width="2"/>
              <polyline points="0 0 2 2 4 0" stroke="black"/>
            </svg>
        """.trimIndent()

        val imported = SvgVectorImporter.parse(svg)!!

        assertEquals(5, imported.paths.size)
        assertEquals("M2.0 3.0 h10.0 v4.0 h-10.0 Z", imported.paths[0].pathData)
        assertTrue(imported.paths[0].filled)
        assertTrue(imported.paths[1].filled)
        assertTrue(imported.paths[2].filled)
        // Lines and polylines are never filled — the implicit SVG polyline fill is always
        // an accident in icons.
        assertFalse(imported.paths[3].filled)
        assertEquals(2f, imported.paths[3].strokeWidth)
        assertFalse(imported.paths[4].filled)
    }

    @Test
    fun authoredColours_surviveImportButGradientsReject() {
        val coloured = SvgVectorImporter.parse(
            """<svg viewBox="0 0 24 24">
                 <path fill="#e91e63" d="M0 0h10v10H0Z"/>
                 <path fill="currentColor" d="M2 2h6"/>
               </svg>"""
        )!!

        assertEquals("#e91e63", coloured.paths[0].color)
        // currentColor means "let the renderer decide" — the editor paints those white.
        assertNull(coloured.paths[1].color)

        assertNull(SvgVectorImporter.parse(
            """<svg viewBox="0 0 24 24"><path fill="url(#g)" d="M0 0h1"/></svg>"""
        ))
    }

    @Test
    fun roundedRect_usesArcCorners() {
        val svg = """<svg viewBox="0 0 24 24"><rect x="0" y="0" width="10" height="10" rx="2"/></svg>"""

        val imported = SvgVectorImporter.parse(svg)!!

        assertTrue(imported.paths.single().pathData.contains("A2.0 2.0"))
    }

    @Test
    fun groupPaintAttributes_areInherited() {
        val svg = """
            <svg viewBox="0 0 24 24" fill="none">
              <g stroke="currentColor" stroke-width="2">
                <path d="M1 1h10" />
              </g>
            </svg>
        """.trimIndent()

        val imported = SvgVectorImporter.parse(svg)!!

        assertFalse(imported.paths.single().filled)
        assertEquals(2f, imported.paths.single().strokeWidth)
    }

    @Test
    fun opacityFillRuleAndStrokeGeometrySurviveImport() {
        val imported = SvgVectorImporter.parse(
            """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor"
                     stroke-width="2px" stroke-linecap="round" stroke-linejoin="bevel">
                 <path opacity="50%" stroke-opacity="0.5" d="M1 1h10"/>
                 <path fill="red" fill-rule="evenodd" fill-opacity="25%" stroke="none"
                       d="M2 2h8v8H2Z M4 4v4h4V4Z"/>
               </svg>"""
        )!!

        assertEquals(0.25f, imported.paths[0].alpha)
        assertEquals(2f, imported.paths[0].strokeWidth)
        assertEquals(StrokeCap.Round, imported.paths[0].strokeLineCap)
        assertEquals(StrokeJoin.Bevel, imported.paths[0].strokeLineJoin)
        assertEquals(0.25f, imported.paths[1].alpha)
        assertEquals(PathFillType.EvenOdd, imported.paths[1].fillType)
    }

    @Test
    fun defaultStrokeWidthIsOneSvgUnit() {
        val imported = SvgVectorImporter.parse(
            """<svg viewBox="0 0 48 48" fill="none" stroke="black"><path d="M1 1h10"/></svg>"""
        )!!

        assertEquals(1f, imported.paths.single().strokeWidth)
    }

    @Test
    fun unsupportedOrPartialSvg_isRejectedInsteadOfSilentlyChanged() {
        assertNull(SvgVectorImporter.parse(
            """<svg viewBox="0 0 24 24"><path transform="translate(2)" d="M0 0h1"/></svg>"""
        ))
        assertNull(SvgVectorImporter.parse(
            """<svg viewBox="0 0 24 24"><use href="#other"/></svg>"""
        ))
        assertNull(SvgVectorImporter.parse(
            """<svg viewBox="0 0 24 24" stroke="black"><path d="M0 0h1"/></svg>"""
        ))
        assertNull(SvgVectorImporter.parse(
            """<svg viewBox="1 1 24 24"><path d="M1 1h1"/></svg>"""
        ))
        assertNull(SvgVectorImporter.parse(
            """<svg viewBox="0 0 24 24"><g opacity="0.5"><path d="M1 1h1"/></g></svg>"""
        ))
        assertNull(SvgVectorImporter.parse(
            """<svg viewBox="0 0 24 24" fill="none" stroke="black" stroke-width="wide"><path d="M1 1h1"/></svg>"""
        ))
    }
}
