package dev.renkinProject.renkin.apk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.globalModifierOptions
import dev.renkinProject.renkin.icon.creator.hasVisibleModifierEffect
import dev.renkinProject.renkin.icon.creator.withSource
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The stage [options] that reproduce an app's stored icon from its source calendar [draftDay];
 * [isRetained] when they are the editor's retained modifier input, which a modifier replay edits.
 */
class StoredCalendarStage(
    val options: GenerationOptions,
    val isRetained: Boolean,
    val isPlainArtwork: Boolean,
    val draftDay: Int,
    val days: Map<Int, IconPackDrawable>
)

/**
 * Renders a calendar app's 31 day drawables with the same modifiers as its static icon, so a
 * rotating calendar keeps the user's shape, scale and colours instead of the raw pack artwork.
 *
 * The local stage comes from [storedStageCandidates] and is accepted only when one source day
 * re-renders to the stored base icon. The Global style layer is added only when it reproduces
 * the static icon from that base. Anything unverifiable keeps the raw days, the previous export.
 */
internal class CalendarDayStyler(
    private val context: Context,
    private val iconGenService: IconGenerationService
) {

    suspend fun style(
        raw: CalendarBuildData<ResourceDrawable>,
        apps: List<PackageInfoStruct>,
        preferences: Preferences
    ): CalendarBuildData<Drawable> {
        val appsByComponent = apps.associateBy { it.toInstalledApplication().toComponentInfo() }
        val global = globalModifierOptions(preferences).takeIf { it.hasVisibleModifierEffect() }
        val styled = linkedMapOf<InstalledApplication, Map<Int, Drawable>>()
        for ((target, prefix) in raw.mappings) {
            currentCoroutineContext().ensureActive()
            val app = appsByComponent[target.toComponentInfo()] ?: continue
            val pack = raw.sourcePacks[prefix] ?: continue
            val days = (1..31).mapNotNull { day -> raw.drawables[prefix + day]?.let { day to it } }.toMap()
            styledDays(app, pack, days, preferences, global)?.let { styled[target] = it }
        }
        return raw.mapDrawables { it.drawable }.withStyledDays(styled)
    }

    private suspend fun styledDays(
        app: PackageInfoStruct,
        pack: String,
        days: Map<Int, ResourceDrawable>,
        preferences: Preferences,
        global: GenerationOptions?
    ): Map<Int, IconPackDrawable>? {
        val base = app.baseIcon ?: return null
        val rendered = app.createdIcon ?: return null
        if (days.isEmpty()) return null
        return try {
            val baseBitmap = base.toBitmap()
            val renderedBitmap = rendered.toBitmap()
            val hasGlobalLayer = !baseBitmap.looksAlike(renderedBitmap)
            val stages = storedStageCandidates(app, preferences, context)
            if (stages.all { it.isPlainArtwork } && !hasGlobalLayer) return null

            val match = matchStages(app, pack, days, stages, baseBitmap) ?: return null
            if (!hasGlobalLayer) return if (match.isPlainArtwork) null else match.days
            global ?: return null
            val matched = match.days.getValue(match.draftDay)
            if (!iconGenService.applyModifier(matched, global).toBitmap().looksAlike(renderedBitmap)) return null
            match.days.mapValues { (_, day) -> iconGenService.applyModifier(day, global) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("CalendarDayStyler", "Calendar days for ${app.packageName} stay unstyled", error)
            null
        } catch (error: OutOfMemoryError) {
            Log.error("CalendarDayStyler", "Styling calendar days for ${app.packageName} exceeded memory", error)
            null
        }
    }

    /**
     * The local stage that re-renders [app]'s stored base icon from one of [days], with every day
     * rendered through it, or null when no candidate reproduces the base.
     */
    suspend fun storedStage(
        app: PackageInfoStruct,
        pack: String,
        days: Map<Int, ResourceDrawable>,
        preferences: Preferences
    ): StoredCalendarStage? {
        val base = app.baseIcon ?: return null
        if (days.isEmpty()) return null
        return try {
            matchStages(app, pack, days, storedStageCandidates(app, preferences, context), base.toBitmap())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("CalendarDayStyler", "Stored calendar stage of ${app.packageName} not found", error)
            null
        } catch (error: OutOfMemoryError) {
            null
        }
    }

    private suspend fun matchStages(
        app: PackageInfoStruct,
        pack: String,
        days: Map<Int, ResourceDrawable>,
        stages: List<StoredStageCandidate>,
        baseBitmap: Bitmap
    ): StoredCalendarStage? {
        for (stage in stages) {
            val local = renderDays(app, pack, days, stage.options) ?: continue
            val closest = local.entries.minByOrNull { it.value.toBitmap().averageDifference(baseBitmap, DETAIL_MATCH_SIZE) }
                ?: continue
            if (!closest.value.toBitmap().looksAlike(baseBitmap)) continue
            return StoredCalendarStage(stage.options, stage.isRetained, stage.isPlainArtwork, closest.key, local)
        }
        return null
    }

    private suspend fun renderDays(
        app: PackageInfoStruct,
        pack: String,
        days: Map<Int, ResourceDrawable>,
        options: GenerationOptions
    ): Map<Int, IconPackDrawable>? {
        // Every styled app already has a created icon, so without override the generator skips it
        // and only the retained candidate could ever verify.
        val packOptions = options.withSource { copy(primarySource = Source.ICON_PACK, primaryIconPack = pack) }
            .copy(override = true)
        val rendered = linkedMapOf<Int, IconPackDrawable>()
        for ((day, drawable) in days) {
            rendered[day] = iconGenService.getIcon(app, packOptions, drawable) ?: return null
        }
        return rendered
    }
}
