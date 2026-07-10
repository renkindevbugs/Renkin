package dev.renkinProject.renkin.ui

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SVG upload decoding against a realistic heroicons-style document: viewBox only (no
 * width/height), stroke via `currentColor`. Pins the parse (currentColor resolution) and
 * the full-import-size math; actual pixels can't be asserted here — Robolectric's legacy
 * graphics make every Canvas draw a no-op (verified: even a plain drawRect reads back 0).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class SvgUploadTest {

    // Trimmed heroicons outline icon: viewBox-only sizing + currentColor stroke.
    private val heroicon = """
        <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24"
             stroke-width="1.5" stroke="currentColor" class="size-6">
          <path stroke-linecap="round" stroke-linejoin="round" d="M12 3v18M3 12h18" />
        </svg>
    """.trimIndent()

    @Test
    fun heroicon_parsesAndSizesToFullImport() {
        val svg = decodeSvg(heroicon)

        assertNotNull(svg)
        assertEquals(1024 to 1024, svgRenderSize(svg!!))
    }

    @Test
    fun explicitSize_scalesUpKeepingAspect() {
        val svg = decodeSvg(
            """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="12" fill="currentColor">
               <rect x="0" y="0" width="24" height="12"/></svg>"""
        )

        assertNotNull(svg)
        assertEquals(1024 to 512, svgRenderSize(svg!!))
    }

    @Test
    fun garbageMarkup_isNull() {
        assertNull(decodeSvg("<svg this is broken"))
    }
}
