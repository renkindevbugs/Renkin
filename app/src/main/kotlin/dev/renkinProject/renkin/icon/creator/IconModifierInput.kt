package dev.renkinProject.renkin.icon.creator

import dev.renkinProject.renkin.drawable.IconPackDrawable

enum class ModifierInputOrigin { EXISTING_ICON, UPLOAD, VECTOR }

// The input to one confirmed local modifier stage, not the baked base or a pack reference.
// Treat input as immutable. Older modifier stages can already be baked into this baseline.
class IconModifierInput internal constructor(
    val options: GenerationOptions,
    val containsBakedModifierEdits: Boolean,
    val origin: ModifierInputOrigin,
    liveInput: IconPackDrawable? = null,
    storedInput: StoredModifierArtwork? = null
) {
    internal var liveInput = liveInput
        private set
    internal var storedInput = storedInput
        private set

    internal fun cacheStoredArtwork(artwork: StoredModifierArtwork) = synchronized(this) {
        storedInput = artwork
        liveInput = null
    }
}

internal data class StoredModifierArtwork(val hash: String, val isXml: Boolean, val isAdaptive: Boolean)

internal fun modifierInputForResult(
    input: IconPackDrawable,
    options: GenerationOptions,
    origin: ModifierInputOrigin
): IconModifierInput? {
    val recipe = StoredIconModifierRecipe.fromRenderedOptions(options) ?: return null
    val modifiers = ModifierRecipeCodec.decode(recipe) ?: return null
    val resolved = options.copy(modifiers = modifiers)
    if (!StoredIconModifierInput.supportsOptions(resolved)) return null
    return IconModifierInput(
        resolved, containsBakedModifierEdits = origin == ModifierInputOrigin.EXISTING_ICON,
        origin = origin, liveInput = input
    )
}
