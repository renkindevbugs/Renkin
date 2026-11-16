package dev.renkinProject.renkin.apk

import android.app.Application
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.value.ValueType
import dev.renkinProject.renkin.xml.XmlEncoder
import dev.renkinProject.renkin.xml.file.AppMapXml
import dev.renkinProject.renkin.xml.file.GrayscaleIconMapXml
import dev.renkinProject.renkin.xml.file.ThemeResourcesXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The extra launcher formats the built pack carries alongside appfilter.xml: the GO-schema
 * XML files and the compiled icon_pack string-array. What's under test is the exact wire
 * format — these schemas are consumed by third-party launchers we can't fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class LauncherFormatTest {

    @Test
    fun appMapXml_writesClassNamePairs() {
        val xml = AppMapXml()
        xml.item("com.android.chrome.Main", "com_android_chrome")
        val text = xml.getBytes().toString(Charsets.UTF_8)

        assertTrue(text.contains("<appmap>"))
        assertTrue(text.contains("<item class=\"com.android.chrome.Main\" name=\"com_android_chrome\" />"))
        assertTrue(text.contains("</appmap>"))
    }

    @Test
    fun themeResourcesXml_writesGoThemeSchema() {
        val xml = ThemeResourcesXml("My Pack")
        xml.item("com.android.chrome", "com.android.chrome.Main", "com_android_chrome")
        val text = xml.getBytes().toString(Charsets.UTF_8)

        assertTrue(text.contains("<Theme version=\"1\">"))
        assertTrue(text.contains("<Label value=\"My Pack\" />"))
        assertTrue(text.contains("<AppIcons>"))
        assertTrue(
            text.contains(
                "<Item component=\"ComponentInfo{com.android.chrome/com.android.chrome.Main}\" drawable=\"com_android_chrome\" />"
            )
        )
        assertTrue(text.contains("</Theme>"))
    }

    @Test
    fun layerListXml_stacksLayersInOrderWithAndroidNamespace() {
        val xml = dev.renkinProject.renkin.xml.file.LayerListXml()
        xml.item("clock_layer0")
        xml.item("clock_layer1")
        val text = xml.getBytes().toString(Charsets.UTF_8)

        assertTrue(text.contains("xmlns:android=\"http://schemas.android.com/apk/res/android\""))
        val first = text.indexOf("<bitmap android:src=\"@drawable/clock_layer0\"")
        val second = text.indexOf("<bitmap android:src=\"@drawable/clock_layer1\"")
        assertTrue(first in 1 until second)
        assertTrue(text.contains("</layer-list>"))
    }

    @Test
    fun grayscaleIconMap_writesComponentEntryPlusOnePackageFallback() {
        val map = GrayscaleIconMapXml().apply {
            item("com.foo", "com.foo.Main", "com_foo_foreground")
            item("com.foo", "com.foo.Second", "com_foo_second_foreground")
        }
        assertFalse(map.isEmpty)
        val text = String(map.readAndClose(), Charsets.UTF_8)

        assertTrue(text.contains("<icons>"))
        assertTrue(text.contains("<icon package=\"com.foo\" component=\"com.foo.Main\" drawable=\"@drawable/com_foo_foreground\""))
        assertTrue(text.contains("<icon package=\"com.foo\" component=\"com.foo.Second\" drawable=\"@drawable/com_foo_second_foreground\""))
        assertEquals(1, Regex("<icon package=\"com.foo\" drawable=").findAll(text).count())
    }

    @Test
    fun grayscaleIconMap_startsEmpty() {
        assertTrue(GrayscaleIconMapXml().isEmpty)
    }

    @Test
    fun grayscaleIconMap_compilesDrawableAsResourceReference() {
        // Lawnchair reads the drawable with getAttributeResourceValue; a plain string reads as 0.
        val packageBlock = TableBlock().newPackage(0x7f, "test.pack")
        val glyph = packageBlock.getOrCreate("", "drawable", "com_foo_foreground")
        glyph.setValueAsString("res/com_foo_foreground.png")
        val map = GrayscaleIconMapXml().apply { item("com.foo", "com.foo.Main", "com_foo_foreground") }

        val document = ResXmlDocument()
        document.readBytes(XmlEncoder(packageBlock).encodeToSource(map, "res/grayscale_icon_map.xml").openStream())
        val drawable = document.documentElement.recursiveElements().asSequence()
            .first { it.name == "icon" }
            .searchAttributeByName("drawable")

        assertEquals(ValueType.REFERENCE, drawable.valueType)
        assertEquals(glyph.resourceId, drawable.data)
    }

    @Test
    fun stringArrayResource_roundTripsThroughTheResourceTable() {
        val tableBlock = TableBlock()
        val packageBlock = tableBlock.newPackage(0x7f, "test.pack")

        IconPackBuilder.createStringArrayResource(packageBlock, "icon_pack", listOf("a", "b", "c"))

        val entry = packageBlock.getEntry("", "array", "icon_pack")
        val values = entry.resValueMapArray.iterator().asSequence().map { it.valueAsString }.toList()
        assertEquals(listOf("a", "b", "c"), values)
    }
}
