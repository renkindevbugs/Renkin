package dev.renkinProject.renkin.data.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure parsing tests for the Play page price extraction (the network part isn't tested). */
class PlayStoreLookupTest {

    @Test
    fun jsonLdZeroPrice_isFree() {
        val html = """... "offers":[{"@type":"Offer","price":"0","priceCurrency":"USD"}] ..."""
        assertEquals(StoreVerdict.FREE, PlayStoreLookup.parsePrice(html))
    }

    @Test
    fun jsonLdNonZeroPrice_isPaid() {
        val html = """... "offers":[{"@type":"Offer","price":"1.99","priceCurrency":"EUR"}] ..."""
        assertEquals(StoreVerdict.PAID, PlayStoreLookup.parsePrice(html))
    }

    @Test
    fun metaTagPrice_isUsedWhenNoJsonLd() {
        assertEquals(StoreVerdict.PAID, PlayStoreLookup.parsePrice("""<meta itemprop="price" content="€3,49">"""))
        assertEquals(StoreVerdict.FREE, PlayStoreLookup.parsePrice("""<meta itemprop="price" content="0">"""))
    }

    @Test
    fun zeroWithDecimals_isFree() {
        val html = """"offers":[{"price":"0.00"}]"""
        assertEquals(StoreVerdict.FREE, PlayStoreLookup.parsePrice(html))
    }

    @Test
    fun pageWithoutPrice_isUnknown() {
        assertEquals(StoreVerdict.UNKNOWN, PlayStoreLookup.parsePrice("<html>captcha wall</html>"))
    }

    @Test
    fun parseTitle_readsOgTitleAndStripsSuffix() {
        val html = """<meta property="og:title" content="Whicons &amp; Friends - Apps on Google Play">"""
        assertEquals("Whicons & Friends", PlayStoreLookup.parseTitle(html))
    }

    @Test
    fun parseTitle_missingMeta_isNull() {
        assertEquals(null, PlayStoreLookup.parseTitle("<html></html>"))
    }
}
