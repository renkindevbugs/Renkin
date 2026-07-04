package dev.renkinProject.renkin.xml.file

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the appfilter.xml a built pack ships. The core invariant: apps are matched by their
 * ComponentInfo (package + activity), never by name — launchers resolve icons off this, so a
 * regression to name-based mapping would silently stop the pack theming anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class AppFilterXmlTest {

    private fun render(build: AppFilterXml.() -> Unit): String =
        String(AppFilterXml().apply(build).readAndClose(), Charsets.UTF_8)

    @Test
    fun item_mapsComponentToDrawable() {
        val xml = render { item("com.foo", "com.foo.MainActivity", "com_foo") }

        assertTrue(xml.contains("component=\"ComponentInfo{com.foo/com.foo.MainActivity}\""))
        assertTrue(xml.contains("drawable=\"com_foo\""))
    }

    @Test
    fun item_isComponentBased_notNameBased() {
        val xml = render { item("com.foo", "com.foo.MainActivity", "com_foo") }

        // The drawable name mirrors the package, but the mapping key must be the component,
        // not a bare package/name attribute.
        assertTrue(xml.contains("ComponentInfo{"))
        assertFalse(xml.contains("package=\""))
        assertFalse(xml.contains("name=\""))
    }

    @Test
    fun twoActivitiesSamePackage_produceDistinctComponents() {
        val xml = render {
            item("com.foo", "com.foo.Main", "com_foo")
            item("com.foo", "com.foo.Settings", "com_foo_settings")
        }

        assertTrue(xml.contains("ComponentInfo{com.foo/com.foo.Main}"))
        assertTrue(xml.contains("ComponentInfo{com.foo/com.foo.Settings}"))
    }

    @Test
    fun calendar_writesComponentAndPrefix() {
        val xml = render { calendar("com.cal", "com.cal.Main", "day_") }

        assertTrue(xml.contains("<calendar"))
        assertTrue(xml.contains("component=\"ComponentInfo{com.cal/com.cal.Main}\""))
        assertTrue(xml.contains("prefix=\"day_\""))
    }

    @Test
    fun documentIsWellFormedResources() {
        val xml = render { item("com.foo", "com.foo.Main", "com_foo") }

        assertTrue(xml.contains("<resources"))
        // Single root: exactly one opening and one closing <resources> tag.
        assertEquals(1, Regex("<resources").findAll(xml).count())
        assertTrue(xml.trimEnd().endsWith("</resources>"))
    }
}
