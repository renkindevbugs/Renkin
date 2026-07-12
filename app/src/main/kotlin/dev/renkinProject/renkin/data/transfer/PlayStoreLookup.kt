package dev.renkinProject.renkin.data.transfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * What a store lookup concluded about a pack. FREE/PAID come from Play (its page carries a
 * price); LISTED means "found on a store whose price we don't read" (F-Droid); UNLISTED means
 * found on no known store; UNKNOWN means the lookup couldn't decide (offline/blocked/parse).
 */
enum class StoreVerdict { FREE, PAID, LISTED, UNLISTED, UNKNOWN }

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

/**
 * Existence check against F-Droid's package API — a clean 200/404 JSON endpoint (no scraping).
 * Returns true when the pack is published there, false when it is not, null when the request
 * itself failed (offline/blocked) so the caller can retry instead of concluding "not there".
 */
object FDroidLookup {
    suspend fun exists(packageName: String): Boolean? = withContext(Dispatchers.IO) {
        val url = URL("https://f-droid.org/api/v1/packages/$packageName")
        var connection: HttpURLConnection? = null
        try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            }
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> true
                HttpURLConnection.HTTP_NOT_FOUND, HttpURLConnection.HTTP_GONE -> false
                else -> null
            }
        } catch (e: IOException) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}

/**
 * The pack-verdict resolver used in production: Play first (it prices most packs), and only
 * when Play has no listing does it fall back to F-Droid — the exact moment we'd otherwise
 * UNLOCK a pack, so we double-check it really isn't installable anywhere. Found on F-Droid ->
 * [StoreVerdict.LISTED] (locked, install to use). A failed fallback stays UNKNOWN (locked,
 * retried later) rather than wrongly unlocking. Samsung Galaxy Store has no queryable
 * existence endpoint (its web pages 404 uniformly), so it is deliberately not consulted.
 */
object StoreLookup {
    suspend fun lookup(packageName: String): StoreLookupResult {
        val play = PlayStoreLookup.lookup(packageName)
        if (play.verdict != StoreVerdict.UNLISTED) return play // FREE/PAID/UNKNOWN: trust Play
        return when (FDroidLookup.exists(packageName)) {
            true -> StoreLookupResult(StoreVerdict.LISTED, play.label)
            false -> play // truly on no known store -> stays UNLISTED (usable)
            null -> StoreLookupResult(StoreVerdict.UNKNOWN, play.label) // fallback failed: retry
        }
    }
}
