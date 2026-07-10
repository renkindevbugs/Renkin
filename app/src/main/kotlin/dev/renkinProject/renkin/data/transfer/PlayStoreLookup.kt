package dev.renkinProject.renkin.data.transfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What a Play Store lookup concluded about a pack. */
enum class StoreVerdict { FREE, PAID, UNLISTED, UNKNOWN }

/**
 * Checks whether an app is paid or free by fetching its public Play Store details page —
 * there is no official price API and no offline source at all, so this is the pragmatic
 * option. A page that doesn't exist (404/410) means the pack isn't purchasable anywhere:
 * per the app's policy those icons stay usable (also covers Icon Pack Studio exports,
 * whose per-user packages were never on the store). Network errors and parse failures
 * yield [StoreVerdict.UNKNOWN] — callers treat that as "not yet verified", never as free.
 */
object PlayStoreLookup {

    suspend fun lookup(packageName: String): StoreVerdict = withContext(Dispatchers.IO) {
        val url = URL("https://play.google.com/store/apps/details?id=$packageName&hl=en&gl=US")
        var connection: HttpURLConnection? = null
        try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                // The page serves fine to browsers; a bare Java UA sometimes gets blocked.
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            }
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK ->
                    parsePrice(connection.inputStream.bufferedReader().use { it.readText() })
                HttpURLConnection.HTTP_NOT_FOUND, HttpURLConnection.HTTP_GONE -> StoreVerdict.UNLISTED
                else -> StoreVerdict.UNKNOWN
            }
        } catch (e: IOException) {
            StoreVerdict.UNKNOWN
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Extracts the price from the page's structured data. The details page embeds JSON-LD
     * (`"offers":[{"@type":"Offer","price":"0", ...}]`) and a meta `itemprop="price"` tag;
     * either carries the price. Parsing is deliberately tolerant — scraping is brittle, and
     * an unreadable page must degrade to UNKNOWN (locked-pending), not to a wrong verdict.
     */
    fun parsePrice(html: String): StoreVerdict {
        val jsonLd = Regex("\"offers\"\\s*:\\s*\\[\\s*\\{[^}]*?\"price\"\\s*:\\s*\"([^\"]*)\"")
            .find(html)?.groupValues?.get(1)
        val meta = Regex("itemprop=\"price\"\\s+content=\"([^\"]*)\"")
            .find(html)?.groupValues?.get(1)
        val price = jsonLd ?: meta ?: return StoreVerdict.UNKNOWN
        // "0", "0.00", "0,00" and currency-prefixed zeros are all free.
        val digits = price.filter { it.isDigit() }
        return when {
            digits.isEmpty() -> StoreVerdict.UNKNOWN
            digits.all { it == '0' } -> StoreVerdict.FREE
            else -> StoreVerdict.PAID
        }
    }
}
