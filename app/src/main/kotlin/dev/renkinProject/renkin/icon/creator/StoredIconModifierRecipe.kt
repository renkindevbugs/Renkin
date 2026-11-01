package dev.renkinProject.renkin.icon.creator

/** Metadata for the last local modifier stage, never an instruction to re-render baked artwork. */
internal object StoredIconModifierRecipe {
    fun fromRenderedOptions(options: GenerationOptions): String? {
        val modifiers = options.modifiers
        if (modifiers.outline?.eraseMask != null ||
            modifiers.effect.backgroundRemoval.brushOperations.isNotEmpty()) return null
        // The editor's plate inherits export.background. Capture that resolved fill so the
        // recipe does not depend on a presentation setting that is outside ModifierStack.
        val resolved = modifiers.copy(shape = modifiers.shape?.let {
            it.copy(fill = it.fill ?: options.export.background)
        })
        val encoded = ModifierRecipeCodec.encode(resolved)
        // Do not publish metadata that our bounded persistence reader cannot retain exactly.
        return encoded.takeIf { ModifierRecipeCodec.decode(it) == resolved }
    }

    fun normalize(encoded: String?): String? {
        if (encoded.isNullOrEmpty()) return null
        return ModifierRecipeCodec.decode(encoded)?.let(ModifierRecipeCodec::encode)
    }
}
