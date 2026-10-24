package dev.renkinProject.renkin.packages

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** Pack XML is read through [PackageResourceResolver.withPackXml], which must close what it opened. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PackXmlParserTest {
    private val context = RuntimeEnvironment.getApplication()
    private val resolver = PackageResourceResolver(context)
    // The app ships res/xml/manifest.xml, which stands in for a pack's res/xml/appfilter.xml.
    private val pack = context.packageName

    @Test fun readsAResourceXmlAndClosesTheParserAfterwards() {
        var used: XmlPullParser? = null
        val firstTag = resolver.withPackXml(context.resources, pack, "manifest") { parser ->
            used = parser
            while (parser.next() != XmlPullParser.START_TAG) Unit
            parser.name
        }

        assertEquals("manifest", firstTag)
        // A closed resource parser has nothing left to read.
        assertEquals(XmlPullParser.END_DOCUMENT, requireNotNull(used).next())
    }

    @Test fun closesTheParserWhenTheReaderThrows() {
        var used: XmlPullParser? = null
        assertThrows(IllegalStateException::class.java) {
            resolver.withPackXml(context.resources, pack, "manifest") { parser ->
                used = parser
                parser.next()
                error("malformed pack")
            }
        }
        assertEquals(XmlPullParser.END_DOCUMENT, requireNotNull(used).next())
    }

    @Test fun aPackWithoutTheXmlReadsNothing() {
        var called = false
        val result = resolver.withPackXml(context.resources, pack, "no_such_appfilter") { called = true }

        assertNull(result)
        assertTrue(!called)
    }
}
