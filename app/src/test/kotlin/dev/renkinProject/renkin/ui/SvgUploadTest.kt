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
 * width/height), stroke via `currentColor`. The old path rendered the 24px document 1:1
 * (blur) or nothing at all — these pin the full-import-size render and the visible output.
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
    fun heroicon_rendersAtFullImportSize() {
        val bitmap = decodeSvgToBitmap(heroicon)

        assertNotNull(bitmap)
        assertEquals(1024, bitmap!!.width)
        assertEquals(1024, bitmap.height)
    }

    // Note: pixel content can't be asserted here — Robolectric's legacy graphics make every
    // Canvas draw a no-op (verified: even a plain drawRect reads back 0). The currentColor
    // substitution in decodeSvgToBitmap is what makes stroke/fill deterministic on-device.

    @Test
    fun explicitSize_scalesUpToo() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="12" fill="currentColor">
            <rect x="0" y="0" width="24" height="12"/></svg>"""

        val bitmap = decodeSvgToBitmap(svg)

        assertNotNull(bitmap)
        assertEquals(1024, bitmap!!.width)
        assertEquals(512, bitmap.height)
    }

    @Test
    fun garbageMarkup_isNull() {
        assertNull(decodeSvgToBitmap("<svg this is broken"))
    }
}
