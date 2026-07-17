package dev.renkinProject.renkin.ui

import android.app.Application
import dev.renkinProject.renkin.vector.SvgRasterizer
import dev.renkinProject.renkin.vector.SvgVectorImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SVG rasterisation against a realistic heroicons-style document: viewBox only (no
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

    // Gradient + defs/use document in the style of devicon's full-colour brand icons: the
    // strict vector importer must reject it while the rasteriser still renders it.
    private val gradientIcon = """
        <svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 128 128">
          <defs><ellipse id="disc" cx="64" cy="64" rx="58" ry="58"/></defs>
          <radialGradient id="grad"><stop offset="0" stop-color="#67c5d5"/>
            <stop offset="1" stop-color="#596aad"/></radialGradient>
          <use href="#disc" fill="url(#grad)"/>
        </svg>
    """.trimIndent()

    @Test
    fun heroicon_parsesAndSizesToFullImport() {
        val svg = SvgRasterizer.decode(heroicon)

        assertNotNull(svg)
        assertEquals(1024 to 1024, SvgRasterizer.renderSize(svg!!, 1024))
    }

    @Test
    fun explicitSize_scalesUpKeepingAspect() {
        val svg = SvgRasterizer.decode(
            """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="12" fill="currentColor">
               <rect x="0" y="0" width="24" height="12"/></svg>"""
        )

        assertNotNull(svg)
        assertEquals(1024 to 512, SvgRasterizer.renderSize(svg!!, 1024))
    }

    @Test
    fun gradientDocument_rasterisesButIsNotImportable() {
        assertNull(SvgVectorImporter.parse(gradientIcon))

        val bitmap = SvgRasterizer.rasterize(gradientIcon, 128)
        assertNotNull(bitmap)
        assertEquals(128, bitmap!!.width)
        assertEquals(128, bitmap.height)
    }

    @Test
    fun garbageMarkup_isNull() {
        assertNull(SvgRasterizer.decode("<svg this is broken"))
    }
}
