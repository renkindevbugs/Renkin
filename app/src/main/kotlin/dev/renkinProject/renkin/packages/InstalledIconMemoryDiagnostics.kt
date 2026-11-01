package dev.renkinProject.renkin.packages

import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.VectorDrawable
import android.os.Build
import android.os.SystemClock
import dev.renkinProject.renkin.BuildConfig
import dev.renkinProject.renkin.util.Log
import java.util.Collections
import java.util.IdentityHashMap
import org.json.JSONObject

internal data class InstalledIconMemorySnapshot(
    val roots: Int,
    val drawables: Int,
    val bitmapDrawables: Int,
    val bitmaps: Int,
    val bitmapBytes: Long,
    val largestBitmapBytes: Long,
    val hardwareBitmaps: Int,
    val hardwareBytes: Long,
    val recycledBitmaps: Int,
    val adaptiveDrawables: Int,
    val vectors: Int,
    val opaqueContainers: Int,
    val unknownLeaves: Int,
    val truncated: Int,
    val readFailures: Int
)

/** Counts exposed Bitmap objects, not all native allocations or hidden Drawable caches. */
internal fun inspectInstalledIconMemory(icons: List<Drawable>): InstalledIconMemorySnapshot {
    val seenDrawables = Collections.newSetFromMap(IdentityHashMap<Drawable, Boolean>())
    val seenBitmaps = Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())
    var bitmapDrawables = 0
    var bitmapBytes = 0L
    var largestBitmapBytes = 0L
    var hardwareBitmaps = 0
    var hardwareBytes = 0L
    var recycledBitmaps = 0
    var adaptiveDrawables = 0
    var vectors = 0
    var opaqueContainers = 0
    var unknownLeaves = 0
    var truncated = 0
    var readFailures = 0

    fun visit(drawable: Drawable?, depth: Int) {
        if (drawable == null || drawable in seenDrawables) return
        if (depth > 32 || seenDrawables.size >= 4096) { truncated++; return }
        seenDrawables += drawable
        try {
            when {
                drawable is BitmapDrawable -> {
                    bitmapDrawables++
                    val bitmap = drawable.bitmap ?: return
                    if (!seenBitmaps.add(bitmap)) return
                    if (bitmap.isRecycled) { recycledBitmaps++; return }
                    val bytes = bitmap.allocationByteCount.toLong()
                    bitmapBytes += bytes
                    largestBitmapBytes = maxOf(largestBitmapBytes, bytes)
                    if (Build.VERSION.SDK_INT >= 26 && bitmap.config == Bitmap.Config.HARDWARE) {
                        hardwareBitmaps++
                        hardwareBytes += bytes
                    }
                }
                Build.VERSION.SDK_INT >= 26 && drawable is AdaptiveIconDrawable -> {
                    adaptiveDrawables++
                    visit(drawable.background, depth + 1)
                    visit(drawable.foreground, depth + 1)
                    if (Build.VERSION.SDK_INT >= 33) visit(drawable.monochrome, depth + 1)
                }
                drawable is LayerDrawable -> {
                    for (index in 0 until drawable.numberOfLayers) visit(drawable.getDrawable(index), depth + 1)
                }
                drawable is DrawableWrapper -> visit(drawable.drawable, depth + 1)
                // Do not initialize inactive states/frames or inspect private framework caches.
                drawable is DrawableContainer -> opaqueContainers++
                drawable is VectorDrawable -> vectors++
                drawable is ColorDrawable -> Unit
                else -> unknownLeaves++
            }
        } catch (_: Exception) {
            readFailures++
        }
    }

    icons.forEach { visit(it, 0) }
    return InstalledIconMemorySnapshot(icons.size, seenDrawables.size, bitmapDrawables,
        seenBitmaps.size, bitmapBytes, largestBitmapBytes, hardwareBitmaps, hardwareBytes,
        recycledBitmaps, adaptiveDrawables, vectors, opaqueContainers, unknownLeaves, truncated, readFailures)
}

internal fun reportInstalledIconMemory(icons: List<Drawable>, loadMs: Double) {
    if (!BuildConfig.DEBUG) return
    try {
        val started = SystemClock.elapsedRealtimeNanos()
        val value = inspectInstalledIconMemory(icons)
        val inspectedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        val record = JSONObject()
            .put("loadMs", loadMs).put("inspectMs", inspectedMs)
            .put("roots", value.roots).put("drawables", value.drawables)
            .put("bitmapDrawables", value.bitmapDrawables).put("bitmaps", value.bitmaps)
            .put("bitmapBytes", value.bitmapBytes).put("largestBitmapBytes", value.largestBitmapBytes)
            .put("hardwareBitmaps", value.hardwareBitmaps).put("hardwareBytes", value.hardwareBytes)
            .put("recycledBitmaps", value.recycledBitmaps).put("adaptiveDrawables", value.adaptiveDrawables)
            .put("vectors", value.vectors).put("opaqueContainers", value.opaqueContainers)
            .put("unknownLeaves", value.unknownLeaves).put("truncated", value.truncated)
            .put("readFailures", value.readFailures)
        Log.debug("InstalledIconMemory", record.toString())
    } catch (_: OutOfMemoryError) {
        Log.debug("InstalledIconMemory", "Inventory unavailable: allocation failed")
    } catch (_: Exception) {
        Log.debug("InstalledIconMemory", "Inventory unavailable: inspection failed")
    }
}
