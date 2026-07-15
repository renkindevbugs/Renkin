package dev.renkinProject.renkin.vector

import android.app.Application
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
        assertNull(SvgVectorImporter.parse("""<svg viewBox="0 0 24 24"><rect width="5" height="5"/></svg>"""))
        assertNull(SvgVectorImporter.parse("""<svg><path d="M0 0h1"/></svg>"""))
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
    fun unsupportedOrPartialSvg_isRejectedInsteadOfSilentlyChanged() {
        assertNull(SvgVectorImporter.parse(
            """<svg viewBox="0 0 24 24"><path transform="translate(2)" d="M0 0h1"/></svg>"""
        ))
        assertNull(SvgVectorImporter.parse(
            """<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/></svg>"""
        ))
        assertNull(SvgVectorImporter.parse(
            """<svg viewBox="0 0 24 24" stroke="black"><path d="M0 0h1"/></svg>"""
        ))
        assertNull(SvgVectorImporter.parse(
            """<svg viewBox="1 1 24 24"><path d="M1 1h1"/></svg>"""
        ))
    }
}
