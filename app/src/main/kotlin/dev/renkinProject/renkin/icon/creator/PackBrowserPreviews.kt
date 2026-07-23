package dev.renkinProject.renkin.icon.creator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.RawItem
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.extension.contentBounds
import dev.renkinProject.renkin.extension.normalizeIconSearchQuery
import dev.renkinProject.renkin.packages.PackBrowserDataSource
import dev.renkinProject.renkin.packages.PackageVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

// Roughly 30 preview bitmaps per row at ~96px ≈ 1 MB; 24 rows caps the browser cache near 25 MB.
private const val PACK_ROW_PREVIEW_CACHE_MAX = 24

// Previews only ever render at ~56-64dp; a 96px bitmap covers that on the highest densities while
// keeping the full-pack grid (up to PACK_DETAIL_LIMIT items) within a sane memory budget — a full
// 256px raster each would be ~100 MB for 400 icons.
private const val PREVIEW_PX = 96

private fun Bitmap.scaledPreview(max: Int = PREVIEW_PX): Bitmap {
    val biggest = maxOf(width, height)
    if (biggest <= max) return this
    val scale = max.toFloat() / biggest
    return Bitmap.createScaledBitmap(
        this,
        (width * scale).toInt().coerceAtLeast(1),
        (height * scale).toInt().coerceAtLeast(1),
        true
    )
}

/**
 * The icon-pack browser's heavy work: enumerating a pack's drawables, filtering/sorting them and
 * rasterising their previews. Pulled out of [dev.renkinProject.renkin.MainViewModel] so the view
 * model stays a thin façade and the pure ordering/cache-key logic is unit-testable.
 *
 * [buildPackIcons] builds + rasterises a pack's icons for a set of drawables (the view model wires
 * it to ApplicationProvider.getIconPackIcons); passing it as a dependency avoids a dependency on the
 * apk layer here and lets tests fake it.
 */
internal class PackBrowserPreviews(
    private val appMan: PackBrowserDataSource,
    private val buildPackIcons: suspend (String, GenerationOptions, List<ResourceDrawable>) -> Map<ResourceDrawable, IconPackDrawable?>
) {
    // Generating a pack row's preview bitmaps is expensive. Without a cache each row regenerated
    // them every time it scrolled back into view (a LazyColumn discards off-screen items, taking
    // their remembered state with them) — that's the loading flicker seen while scrolling the
    // multi-pack browser. The cache survives reopening the dialog and rotation (the view model
    // owns this object). Keyed by pack + sort + query + options; an LRU bound keeps memory sane.
    // Touched only from the main thread.
    private val cache = object : LinkedHashMap<String, PackRowPreviews>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PackRowPreviews>?) =
            size > PACK_ROW_PREVIEW_CACHE_MAX
    }

    /** Installed pack metadata/resources changed; no previous row preview remains authoritative. */
    fun clear() = cache.clear()

    /**
     * The collapsed row previews for [iconPack] (first [PACK_ROW_LIMIT] matches plus the
     * "+N more" count). Returns the cached result instantly when present, otherwise generates
     * and caches it. A malformed pack yields an empty row instead of crashing the browser.
     */
    suspend fun rowPreviews(
        iconPack: IconPack,
        sortOrder: IconSortOrder,
        query: String,
        options: GenerationOptions,
        // When set, the icons the pack's appfilter maps to this app component are prepended to
        // the results regardless of the name query — packs identify apps by component, so the
        // designated icon is found even when its drawable name has nothing to do with the app name.
        component: InstalledApplication? = null
    ): PackRowPreviews {
        val packageName = iconPack.packageName
        val key = cacheKey(iconPack, sortOrder, query, options, component)
        cache[key]?.let { return it }

        val result = try {
            withContext(Dispatchers.Default) {
                val sortedNames = filteredSortedNames(packageName, query, sortOrder, component)
                val more = (sortedNames.size - PACK_ROW_LIMIT).coerceAtLeast(0)
                val pairs = loadPackIconPairs(packageName, options, sortedNames.take(PACK_ROW_LIMIT))
                PackRowPreviews(pairs, more)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Keep the UI alive, but do not poison the cache: a later reload/retry may succeed.
            return PackRowPreviews(emptyList(), 0)
        }
        cache[key] = result
        return result
    }

    /**
     * Generates the full-pack grid previews (up to [PACK_DETAIL_LIMIT]) for [iconPack],
     * streaming them to [onChunk] a chunk at a time so the grid fills progressively instead of
     * blocking. [onChunk] is invoked on the calling (main) thread. Not cached — the detail grid
     * stays in composition while open, so it only loads once anyway.
     */
    suspend fun detailPreviews(
        iconPack: IconPack,
        sortOrder: IconSortOrder,
        query: String,
        options: GenerationOptions,
        // Same component-mapped prepending as rowPreviews.
        component: InstalledApplication? = null,
        onChunk: (List<PackIconPreview>) -> Unit
    ) {
        val packageName = iconPack.packageName
        val sortedNames = withContext(Dispatchers.Default) {
            try {
                filteredSortedNames(packageName, query, sortOrder, component)
            } catch (_: Exception) {
                emptyList()
            }
        }
        for (chunk in sortedNames.take(PACK_DETAIL_LIMIT).chunked(40)) {
            coroutineContext.ensureActive()
            val pairs = withContext(Dispatchers.Default) {
                try {
                    loadPackIconPairs(packageName, options, chunk)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A malformed icon pack must not crash the browser
                    emptyList()
                }
            }
            onChunk(pairs)
        }
    }

    /**
     * Drawable names of [packageName] matching [query], sorted by [sortOrder]. Icons the pack's
     * appfilter maps to [component] come first, independent of the query (see rowPreviews).
     */
    private fun filteredSortedNames(
        packageName: String,
        query: String,
        sortOrder: IconSortOrder,
        component: InstalledApplication?
    ): List<String> {
        val allNames = appMan.getIconPackDrawableNames(packageName)
        val componentNames = component?.let { componentDrawableNames(packageName, it) }.orEmpty()
        return orderDrawableNames(allNames, componentNames, query, sortOrder)
    }

    /** The drawable names [packageName]'s appfilter assigns to [component] (usually 0 or 1). */
    private fun componentDrawableNames(
        packageName: String,
        component: InstalledApplication
    ): List<String> {
        val componentInfo = component.toComponentInfo()
        return appMan.getAppFilterRawElements(packageName, listOf(component))
            .filterIsInstance<RawItem>()
            .filter { it.component == componentInfo }
            .mapNotNull { it.drawableLink }
            .distinct()
    }

    /** Builds + rasterises the preview icons for the given drawable [names] of a pack. */
    private suspend fun loadPackIconPairs(
        packageName: String,
        options: GenerationOptions,
        names: List<String>
    ): List<PackIconPreview> {
        val entries = appMan.getIconPackDrawableEntries(packageName, names)
        val idToName = entries.associate { it.resource.resourceId to it.name }
        val drawables = entries.map { it.resource }
        val exportDrawables = buildPackIcons(packageName, options, drawables)
        return exportDrawables.entries
            .filter { it.value != null }
            .distinctBy { it.key.resourceId }
            .mapNotNull {
                // Generation already isolates each icon; keep preview rasterisation isolated too.
                runCatching {
                    PackIconPreview(
                        it.key,
                        it.value!!,
                        previewBitmap(it.key, it.value!!),
                        idToName[it.key.resourceId] ?: ""
                    )
                }.getOrNull()
            }
    }

    /**
     * The tile bitmap for one preview. When the generation pipeline rasterises to nothing —
     * some packs' drawable structures (Lawnicons' tinted inset-adaptive chain, for one)
     * defeat the custom vector renderer — fall back to the PLATFORM's own rendering of the
     * raw drawable, so the browser at least shows what the launcher would. The pick still
     * hands over the generated icon, which the comparison preview renders correctly.
     */
    private fun previewBitmap(resource: ResourceDrawable, generated: IconPackDrawable): ImageBitmap {
        val rendered = generated.toBrowserPreviewBitmap()
        val bitmap = if (rendered.contentBounds() != null) rendered
            else platformPreview(resource.drawable) ?: rendered
        return bitmap.scaledPreview().asImageBitmap()
    }

    /**
     * Platform rendering at full preview quality. An adaptive icon draws only its scaled
     * FOREGROUND: the glyph then fills the tile like every other preview (the full adaptive
     * square looked shrunken — the safe zone is 72/108 of it — and its opaque background
     * poked out of the tile frame). Falls back to the whole drawable when the foreground
     * alone has nothing visible.
     */
    private fun platformPreview(drawable: Drawable, size: Int = 256): Bitmap? {
        if (PackageVersion.is26OrMore() && drawable is AdaptiveIconDrawable) {
            val foreground = drawable.foreground
            if (foreground != null) {
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                // Upscale by the adaptive safe-zone ratio (108/72) around the centre.
                val overdraw = (size * (108f / 72f - 1f) / 2f).toInt()
                foreground.setBounds(-overdraw, -overdraw, size + overdraw, size + overdraw)
                foreground.draw(Canvas(bitmap))
                if (bitmap.contentBounds() != null) return bitmap
            }
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return bitmap.takeIf { it.contentBounds() != null }
    }

    companion object {
        /**
         * The name ordering behind [filteredSortedNames], as a pure function so it can be tested
         * without an ApplicationManager: filter [allNames] by [query], sort, then prepend the
         * [componentNames] (deduplicated) — the component-mapped icons always lead.
         */
        @VisibleForTesting
        internal fun orderDrawableNames(
            allNames: List<String>,
            componentNames: List<String>,
            query: String,
            sortOrder: IconSortOrder
        ): List<String> {
            val formattedQuery = query.normalizeIconSearchQuery()
            val matching = if (formattedQuery.isEmpty()) {
                allNames
            } else {
                allNames.filter { it.contains(formattedQuery) }
            }
            val sorted = when (sortOrder) {
                IconSortOrder.NAME_ASC -> matching.sortedBy { it }
                IconSortOrder.NAME_DESC -> matching.sortedByDescending { it }
            }
            return componentNames + (sorted - componentNames.toSet())
        }

        /** LRU cache key: distinct per pack + sort + query + option set + target component. */
        @VisibleForTesting
        internal fun cacheKey(
            iconPack: IconPack,
            sortOrder: IconSortOrder,
            query: String,
            options: GenerationOptions,
            component: InstalledApplication?
        ) = "${iconPack.packageName}|${iconPack.versionCode}|${iconPack.changesWithMaterialYouColors}|$sortOrder|$query|${options.hashCode()}|${component?.toComponentInfo() ?: ""}"
    }
}
