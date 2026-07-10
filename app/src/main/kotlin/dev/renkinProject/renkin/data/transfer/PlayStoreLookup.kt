package dev.renkinProject.renkin.data.transfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What a Play Store lookup concluded about a pack. */
enum class StoreVerdict { FREE, PAID, UNLISTED, UNKNOWN }

/** A lookup's outcome: the price verdict plus the store-listed app name when readable. */
data class StoreLookupResult(val verdict: StoreVerdict, val label: String? = null)

/**
 * Checks whether an app is paid or free by fetching its public Play Store details page —
 * there is no official price API and no offline source at all, so this is the pragmatic
 * option. A page that doesn't exist (404/410) means the pack isn't purchasable anywhere:
 * per the app's policy those icons stay usable (also covers Icon Pack Studio exports,
 * whose per-user packages were never on the store). Network errors and parse failures
 * yield [StoreVerdict.UNKNOWN] — callers treat that as "not yet verified", never as free.
 */
object PlayStoreLookup {

    suspend fun lookup(packageName: String): StoreLookupResult = withContext(Dispatchers.IO) {
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
                HttpURLConnection.HTTP_OK -> {
                    val html = connection.inputStream.bufferedReader().use { it.readText() }
                    StoreLookupResult(parsePrice(html), parseTitle(html))
                }
                HttpURLConnection.HTTP_NOT_FOUND, HttpURLConnection.HTTP_GONE ->
                    StoreLookupResult(StoreVerdict.UNLISTED)
                else -> StoreLookupResult(StoreVerdict.UNKNOWN)
            }
        } catch (e: IOException) {
            StoreLookupResult(StoreVerdict.UNKNOWN)
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

    /**
     * The app's store-listed name from the page's og:title — the missing-packs dialog would
     * otherwise have to show a bare package name for a pack this device never saw installed.
     */
    fun parseTitle(html: String): String? =
        Regex("<meta property=\"og:title\" content=\"([^\"]+)\"")
            .find(html)?.groupValues?.get(1)
            ?.removeSuffix(" - Apps on Google Play")
            ?.replace("&amp;", "&")
            ?.replace("&#39;", "'")
            ?.replace("&quot;", "\"")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
