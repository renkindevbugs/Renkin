package dev.renkinProject.renkin.xml

import android.app.Application
import android.util.Base64
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class BoundedXmlDecoderTest {
    private fun encoded(xml: String) = Base64.encode(xml.toByteArray(), Base64.NO_WRAP)

    @Test fun boundaryAllowsDocumentRootAndOneChild() {
        val tree = XmlDecoder.fromBase64(encoded("<vector><path value='retained'/></vector>"), 3, 2) {}
        assertEquals("retained", tree.children.single().children.single().getAttributeValue("value"))
    }

    @Test fun excessiveNodesAreRejectedBeforeMalformedTailIsParsed() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            XmlDecoder.fromBase64(encoded("<vector><path/><path/><malformed"), 3, 64) {}
        }
        assertEquals("XML exceeds decoding limits", error.message)
    }

    @Test fun excessiveDepthIsRejectedBeforeMalformedTailIsParsed() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            XmlDecoder.fromBase64(encoded("<vector><group><path/><malformed"), 100, 2) {}
        }
        assertEquals("XML exceeds decoding limits", error.message)
    }

    @Test fun siblingAfterNestedGroupsKeepsItsOwnParent() {
        val xml = "<vector><group name='outer'><group name='inner'><path name='deep'/></group></group>" +
            "<path name='top'/><group name='second'><path name='leaf'/></group></vector>"
        val vector = XmlDecoder.fromBase64(encoded(xml), 100, 64) {}.children.single()

        assertEquals(listOf("outer", "top", "second"), vector.children.map { it.getAttributeValue("name") })
        val outer = vector.children[0]
        assertEquals(listOf("inner"), outer.children.map { it.getAttributeValue("name") })
        assertEquals(listOf("deep"), outer.children.single().children.map { it.getAttributeValue("name") })
        assertEquals(listOf("leaf"), vector.children[2].children.map { it.getAttributeValue("name") })
    }

    @Test fun parsingDoesNotSwallowCancellation() {
        assertThrows(CancellationException::class.java) {
            XmlDecoder.fromBase64(encoded("<vector><path/></vector>"), 100, 64) { throw CancellationException("cancelled") }
        }
    }
}
