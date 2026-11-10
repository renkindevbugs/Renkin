package dev.renkinProject.renkin.icon.creator

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.graphics.asImageBitmap
import dev.renkinProject.renkin.BuildConfig
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.RawItem
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.extension.normalizeIconSearchQuery
import dev.renkinProject.renkin.packages.PackBrowserDataSource
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

internal class PackBrowserSession(
    private val index: PackBrowserIndex,
    private val source: PackBrowserDataSource,
    private val indexedNames: (String, InstalledApplication) -> List<String>?,
    private val render: suspend (String, GenerationOptions, ResourceDrawable, Int) -> Bitmap?
) {
    private data class ThumbnailKey(
        val pack: IconPack,
        val identity: String,
        val name: String,
        val options: GenerationOptions,
        val size: Int
    )

    private class Work(val deferred: Deferred<PackThumbnail?>, var readers: Int = 0)
    private data class Validation(val time: Long, val result: Deferred<String>)

    private val epoch = AtomicLong()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val renders = Semaphore(2)
    private val lock = Any()
    private val thumbnails = LinkedHashMap<ThumbnailKey, PackThumbnail>(16, 0.75f, true)
    private val inFlight = mutableMapOf<ThumbnailKey, Work>()
    private val validations = mutableMapOf<String, Validation>()
    private var thumbnailBytes = 0L

    fun prewarm(packs: List<IconPack>) = index.prewarm(packs)

    fun clear() {
        val generation = epoch.incrementAndGet()
        index.clear()
        synchronized(lock) {
            inFlight.values.forEach { it.deferred.cancel() }
            inFlight.clear()
            validations.values.forEach { it.result.cancel() }
            validations.clear()
            thumbnails.clear()
            thumbnailBytes = 0
        }
        mutableRevision.value = generation
    }

    fun close() {
        scope.cancel()
        index.close()
    }

    fun search(
        packs: List<IconPack>,
        sort: IconSortOrder,
        query: String,
        component: InstalledApplication?
    ): Flow<Map<String, PackSearchResult>> = channelFlow {
        val generation = epoch.get()
        val results = mutableMapOf<String, PackSearchResult>()
        val publication = Mutex()
        suspend fun publish(pack: IconPack, result: PackSearchResult) {
            coroutineContext.ensureActive()
            if (generation != epoch.get()) return
            publication.withLock {
                if (generation != epoch.get()) return@withLock
                results[pack.packageName] = result
                send(results.toMap())
            }
        }
        send(emptyMap())
        val formatted = query.normalizeIconSearchQuery()
        for (pack in packs) launch(Dispatchers.Default) {
            var exact = emptyList<String>()
            var identity = ""
            try {
                identity = "$generation|${index.identity(pack)}"
                exact = component?.let { app ->
                    indexedNames(pack.packageName, app) ?: source.getAppFilterRawElements(pack.packageName, listOf(app))
                        .filterIsInstance<RawItem>()
                        .filter { it.component == app.toComponentInfo() }
                        .map { it.drawableLink }.distinct()
                }.orEmpty()
                exact = displayNames(pack, exact)
                if (exact.isNotEmpty()) publish(pack, PackSearchResult(exact, identity = identity))
                val allNames = index.names(pack)
                val ordered = if (sort == IconSortOrder.NAME_DESC) allNames.asReversed() else allNames
                val exactSet = exact.toHashSet()
                val matching = ordered.filter { name -> name !in exactSet && name.contains(formatted) }
                val names = exact + matching
                val distinct = displayNames(pack, names)
                coroutineContext.ensureActive()
                if (identity != "$generation|${index.identity(pack)}") {
                    publish(pack, PackSearchResult(complete = true, failed = true))
                    return@launch
                }
                publish(pack, PackSearchResult(distinct, complete = true, identity = identity))
            } catch (error: CancellationException) {
                coroutineContext.ensureActive()
                publish(pack, PackSearchResult(exact, complete = true, failed = true, identity = identity))
            } catch (_: Exception) {
                publish(pack, PackSearchResult(exact, complete = true, failed = true, identity = identity))
            }
        }
    }

    fun cachedThumbnail(
        pack: IconPack,
        identity: String,
        name: String,
        options: GenerationOptions,
        sizePx: Int
    ): PackThumbnail? = synchronized(lock) {
        thumbnails[ThumbnailKey(pack, identity, name, normalized(options), sizePx)]
    }

    private suspend fun displayNames(pack: IconPack, names: List<String>): List<String> {
        val seen = HashSet<Int>()
        val visible = ArrayList<String>()
        var offset = 0
        // Deduplicate resource aliases only as far as the browser can display. A blank query
        // must not resolve resource IDs for the thousands of remaining metadata candidates.
        while (offset < names.size && visible.size < PACK_DETAIL_LIMIT) {
            coroutineContext.ensureActive()
            val chunk = names.subList(offset, minOf(offset + 64, names.size))
            val ids = source.getIconPackDrawableIds(pack.packageName, chunk)
            for (name in chunk) {
                offset++
                val id = ids[name]
                if (id == null || (id > 0 && seen.add(id))) visible.add(name)
                if (visible.size == PACK_DETAIL_LIMIT) break
            }
        }
        return visible + names.subList(offset, names.size)
    }

    suspend fun thumbnail(
        pack: IconPack,
        identity: String,
        name: String,
        options: GenerationOptions,
        sizePx: Int
    ): PackThumbnail? {
        require(sizePx in 1..512)
        val key = ThumbnailKey(pack, identity, name, normalized(options), sizePx)
        val generation = epoch.get()
        val work = synchronized(lock) {
            thumbnails[key]?.let { return it }
            inFlight.getOrPut(key) {
                Work(scope.async(start = CoroutineStart.LAZY) {
                    renders.withPermit {
                        if (!current(pack, identity, generation)) return@withPermit null
                        val resource = source.getIconPackDrawableEntries(pack.packageName, listOf(name))
                            .firstOrNull()?.resource
                        if (resource == null) {
                            if (BuildConfig.DEBUG) Log.debug("PackBrowser", "Drawable load failed: pack=${pack.packageName}, name=$name")
                            return@withPermit null
                        }
                        val bitmap = render(pack.packageName, key.options, resource, sizePx)
                        if (bitmap == null) {
                            if (BuildConfig.DEBUG) Log.debug("PackBrowser", "Thumbnail render failed: pack=${pack.packageName}, name=$name, id=${resource.resourceId}")
                            return@withPermit null
                        }
                        coroutineContext.ensureActive()
                        if (!current(pack, identity, generation)) return@withPermit null
                        val result = PackThumbnail(resource.resourceId, bitmap.asImageBitmap())
                        synchronized(lock) {
                            if (epoch.get() == generation) {
                                thumbnails.put(key, result)?.let { thumbnailBytes -= bytes(it) }
                                thumbnailBytes += bytes(result)
                                val iterator = thumbnails.entries.iterator()
                                while (thumbnailBytes > MAX_THUMBNAIL_BYTES && iterator.hasNext()) {
                                    thumbnailBytes -= bytes(iterator.next().value)
                                    iterator.remove()
                                }
                            }
                        }
                        result
                    }
                })
            }.also { it.readers++ }
        }
        return try {
            work.deferred.start()
            work.deferred.await()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (BuildConfig.DEBUG) Log.error("PackBrowser", "Thumbnail failed: pack=${pack.packageName}, name=$name", error)
            null
        } finally {
            synchronized(lock) {
                work.readers--
                if (work.readers == 0 && inFlight[key] === work) {
                    inFlight.remove(key)
                    if (!work.deferred.isCompleted) work.deferred.cancel()
                }
            }
        }
    }

    suspend fun resource(pack: IconPack, identity: String, name: String): ResourceDrawable? =
        withContext(Dispatchers.Default) {
            val generation = epoch.get()
            if (!current(pack, identity, generation, fresh = true)) return@withContext null
            val resource = source.getIconPackDrawableEntries(pack.packageName, listOf(name)).firstOrNull()?.resource
            coroutineContext.ensureActive()
            resource.takeIf { current(pack, identity, generation, fresh = true) }
        }

    private suspend fun current(pack: IconPack, identity: String, generation: Long, fresh: Boolean = false): Boolean {
        if (epoch.get() != generation || !identity.startsWith("$generation|")) return false
        val configuration = "|${index.configurationSignature()}|${pack.changesWithMaterialYouColors}"
        if (!identity.endsWith(configuration)) return false
        val actual = if (fresh) index.identity(pack) else {
            val now = SystemClock.elapsedRealtime()
            val validation = synchronized(lock) {
                validations[identity]?.takeIf { now - it.time < VALIDATION_WINDOW_MS }
                    ?: Validation(now, scope.async { index.identity(pack) }).also {
                        validations[identity] = it
                        if (validations.size > 128) {
                            validations.entries.removeAll { entry ->
                                entry.key != identity && now - entry.value.time >= VALIDATION_WINDOW_MS
                            }
                        }
                    }
            }
            validation.result.await()
        }
        return epoch.get() == generation && identity == "$generation|$actual" &&
            identity.endsWith("|${index.configurationSignature()}|${pack.changesWithMaterialYouColors}")
    }

    private fun normalized(options: GenerationOptions) = options.withSource { copy(primaryIconPack = "") }
    private fun bytes(thumbnail: PackThumbnail) = thumbnail.preview.width.toLong() * thumbnail.preview.height * 4

    private companion object {
        const val MAX_THUMBNAIL_BYTES = 16L * 1024 * 1024
        const val VALIDATION_WINDOW_MS = 250L
    }
}
