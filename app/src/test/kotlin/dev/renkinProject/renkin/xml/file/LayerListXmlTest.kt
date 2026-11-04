package dev.renkinProject.renkin.xml.file

import android.app.Application
import android.util.Xml
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class LayerListXmlTest {
    @Test
    fun clockHandsKeepLevelDrivenRotationAndLayerOrder() {
        val bytes = LayerListXml().apply {
            rotatingItem("hour", 300f, 5300f, 0.5f, 0.5f)
            rotatingItem("minute", 60f, 60060f, 0.4f, 0.6f)
            rotatingItem("second", 180f, 6180f, 0.5f, 0.5f)
            item("top")
        }.getBytes()
        val parser = Xml.newPullParser().apply { setInput(bytes.inputStream(), "UTF-8") }
        val namespace = "http://schemas.android.com/apk/res/android"
        val sweeps = mutableListOf<Float>()
        val artwork = mutableListOf<String>()
        val pivots = mutableListOf<Pair<String, String>>()
        var rotating = false
        var rotatingBitmaps = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) when (parser.name) {
                "rotate" -> {
                    rotating = true
                    sweeps += parser.getAttributeValue(namespace, "toDegrees").toFloat() -
                        parser.getAttributeValue(namespace, "fromDegrees").toFloat()
                    pivots += parser.getAttributeValue(namespace, "pivotX") to parser.getAttributeValue(namespace, "pivotY")
                }
                "bitmap" -> {
                    artwork += parser.getAttributeValue(namespace, "src")
                    assertEquals("fill", parser.getAttributeValue(namespace, "gravity"))
                    if (rotating) rotatingBitmaps++
                }
            }
            if (parser.eventType == XmlPullParser.END_TAG && parser.name == "rotate") rotating = false
            parser.next()
        }
        // Launcher levels advance hours per minute, minutes per minute and seconds per tenth-second.
        assertEquals(listOf(5000f, 60000f, 6000f), sweeps)
        assertEquals(listOf("@drawable/hour", "@drawable/minute", "@drawable/second", "@drawable/top"), artwork)
        assertEquals(40f, pivots[1].first.removeSuffix("%").toFloat(), 0.001f)
        assertEquals(60f, pivots[1].second.removeSuffix("%").toFloat(), 0.001f)
        assertEquals(3, rotatingBitmaps)
    }
}
