package dev.renkinProject.renkin.apk

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.ModifierRecipeCodec
import dev.renkinProject.renkin.icon.creator.globalModifierOptions
import dev.renkinProject.renkin.icon.creator.hasVisibleModifierEffect
import dev.renkinProject.renkin.icon.creator.withEffect
import dev.renkinProject.renkin.icon.creator.withModifiers
import dev.renkinProject.renkin.icon.creator.withSource
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Renders a calendar app's 31 day drawables with the same modifiers as its static icon, so a
 * rotating calendar keeps the user's shape, scale and colours instead of the raw pack artwork.
 *
 * Icons only store the confirmed artwork, so the local stage is reconstructed from candidates
 * (retained modifier input, stored recipe, bulk-refresh options, plain pack artwork) and accepted
 * only when one source day re-renders to the stored base icon. The Global style layer is added
 * only when it reproduces the static icon from that base. Anything unverifiable keeps the raw
 * days, which is the previous export.
 */
internal class CalendarDayStyler(
    private val context: Context,
    private val iconGenService: IconGenerationService
) {
    private class LocalStage(val options: GenerationOptions, val isPlainArtwork: Boolean)

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
            val stages = localStages(app, preferences)
            if (stages.all { it.isPlainArtwork } && !hasGlobalLayer) return null

            for (stage in stages) {
                val local = renderDays(app, pack, days, stage.options) ?: continue
                val matched = local.values.firstOrNull { it.toBitmap().looksAlike(baseBitmap) } ?: continue
                if (!hasGlobalLayer) return if (stage.isPlainArtwork) null else local
                global ?: return null
                if (!iconGenService.applyModifier(matched, global).toBitmap().looksAlike(renderedBitmap)) return null
                return local.mapValues { (_, day) -> iconGenService.applyModifier(day, global) }
            }
            null
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

    private fun localStages(app: PackageInfoStruct, preferences: Preferences): List<LocalStage> {
        val defaults = GenerationOptions.fromPreferences(preferences, context)
        val retained = app.modifierInput?.options
        val recipe = app.modifierRecipe?.let(ModifierRecipeCodec::decode)?.let { defaults.copy(modifiers = it) }
        val refresh = defaults.takeIf { !app.isCustom && it.hasVisibleModifierEffect() }
        val plain = defaults.withModifiers { withEffect { copy(imageEdit = ImageEdit.NONE) } }
        return listOfNotNull(retained, recipe, refresh).map { LocalStage(it, isPlainArtwork = false) } +
            LocalStage(plain, isPlainArtwork = true)
    }

    private suspend fun renderDays(
        app: PackageInfoStruct,
        pack: String,
        days: Map<Int, ResourceDrawable>,
        options: GenerationOptions
    ): Map<Int, IconPackDrawable>? {
        val packOptions = options.withSource { copy(primarySource = Source.ICON_PACK, primaryIconPack = pack) }
        val rendered = linkedMapOf<Int, IconPackDrawable>()
        for ((day, drawable) in days) {
            rendered[day] = iconGenService.getIcon(app, packOptions, drawable) ?: return null
        }
        return rendered
    }
}
