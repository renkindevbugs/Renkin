package dev.renkinProject.renkin.data.online

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.renkinProject.renkin.util.Log
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Icon sets available without the network: the bundled one and those the user downloaded.
 * Tabler ships in the APK as the unmodified `@iconify-json/tabler` `icons.json` plus its
 * `info.json`; it is parsed once on first use and updated only with app releases (refresh the
 * assets from the pinned jsDelivr URLs).
 */
@Singleton
class LocalIconSets internal constructor(
    private val openAsset: (String) -> InputStream,
    private val ioDispatcher: CoroutineDispatcher,
    private val downloads: DownloadedIconSets? = null
) {
    @Inject constructor(@ApplicationContext context: Context, downloads: DownloadedIconSets) :
        this(context.assets::open, Dispatchers.IO, downloads)

    private val loadLock = Mutex()
    @Volatile private var bundled: IconifySet? = null

    fun isBuiltIn(prefix: String): Boolean = prefix == BUNDLED_PREFIX

    internal suspend fun set(prefix: String): IconifySet? {
        if (!isBuiltIn(prefix)) return downloads?.set(prefix)
        bundled?.let { return it }
        return withContext(ioDispatcher) {
            loadLock.withLock {
                bundled ?: readAsset(BUNDLED_ICONS) { IconifyJson.parse(it.reader(Charsets.UTF_8).buffered()) }
                    ?.also { bundled = it }
            }
        }
    }

    /** Set-list entries for the local sets, used when the online list is unavailable. */
    suspend fun collections(): List<IconifyCollection> {
        val bundledInfo = withContext(ioDispatcher) {
            readAsset(BUNDLED_INFO) { stream ->
                val info = stream.reader(Charsets.UTF_8).readText()
                OnlineIconRepository.parseCollections("{\"$BUNDLED_PREFIX\":$info}")
            }.orEmpty()
        }
        return bundledInfo + downloads?.collections().orEmpty()
    }

    private fun <T> readAsset(name: String, read: (InputStream) -> T?): T? = runCatching {
        openAsset(name).use(read)
    }.onFailure { Log.error("OnlineIcons", "Could not read bundled icon set: $name", it) }.getOrNull()

    private companion object {
        const val BUNDLED_PREFIX = "tabler"
        const val BUNDLED_ICONS = "online-icons/tabler.json"
        const val BUNDLED_INFO = "online-icons/tabler-info.json"
    }
}
