package dev.renkinProject.renkin.apk

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.ModifierRecipeCodec
import dev.renkinProject.renkin.icon.creator.globalModifierOptions
import dev.renkinProject.renkin.icon.creator.hasVisibleModifierEffect
import dev.renkinProject.renkin.icon.creator.withEffect
import dev.renkinProject.renkin.icon.creator.withModifiers
import dev.renkinProject.renkin.packages.PackageInfoStruct

/** One way an applied icon may have been rendered from its pack source. */
internal class StoredStageCandidate(
    val options: GenerationOptions,
    val isPlainArtwork: Boolean,
    val isRetained: Boolean = false
)

/**
 * Icons store only their confirmed artwork, so the stage that produced them is reconstructed
 * from candidates, most specific first: the retained modifier input, the stored recipe, the
 * bulk-refresh options and finally the plain pack artwork. Callers verify a candidate against
 * the stored icon before trusting it.
 */
internal fun storedStageCandidates(
    app: PackageInfoStruct,
    preferences: Preferences,
    context: Context
): List<StoredStageCandidate> {
    val defaults = GenerationOptions.fromPreferences(preferences, context)
    val retained = app.modifierInput?.options
    val recipe = app.modifierRecipe?.let(ModifierRecipeCodec::decode)?.let { defaults.copy(modifiers = it) }
    val refresh = defaults.takeIf { !app.isCustom && it.hasVisibleModifierEffect() }
    val plain = defaults.withModifiers { withEffect { copy(imageEdit = ImageEdit.NONE) } }
    return listOfNotNull(
        retained?.let { StoredStageCandidate(it, isPlainArtwork = false, isRetained = true) },
        recipe?.let { StoredStageCandidate(it, isPlainArtwork = false) },
        refresh?.let { StoredStageCandidate(it, isPlainArtwork = false) }
    ) + StoredStageCandidate(plain, isPlainArtwork = true)
}

/** Each candidate stage alone and followed by the visible Global style, in that order. */
internal fun storedStageChains(
    app: PackageInfoStruct,
    preferences: Preferences,
    context: Context
): List<List<GenerationOptions>> {
    val global = globalModifierOptions(preferences).takeIf { it.hasVisibleModifierEffect() }
    return storedStageCandidates(app, preferences, context).flatMap { stage ->
        listOfNotNull(listOf(stage.options), global?.let { listOf(stage.options, it) })
    }
}
