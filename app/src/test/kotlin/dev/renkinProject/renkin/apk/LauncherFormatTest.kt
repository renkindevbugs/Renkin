package dev.renkinProject.renkin.apk

import android.app.Application
import com.reandroid.arsc.chunk.TableBlock
import dev.renkinProject.renkin.xml.file.AppMapXml
import dev.renkinProject.renkin.xml.file.ThemeResourcesXml
import org.junit.Assert.assertEquals
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
    fun stringArrayResource_roundTripsThroughTheResourceTable() {
        val tableBlock = TableBlock()
        val packageBlock = tableBlock.newPackage(0x7f, "test.pack")

        IconPackBuilder.createStringArrayResource(packageBlock, "icon_pack", listOf("a", "b", "c"))

        val entry = packageBlock.getEntry("", "array", "icon_pack")
        val values = entry.resValueMapArray.iterator().asSequence().map { it.valueAsString }.toList()
        assertEquals(listOf("a", "b", "c"), values)
    }
}
