package dev.renkinProject.renkin.icon.creator

import dev.renkinProject.renkin.data.FallbackSource
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType

// Primary image editing belongs to ModifierStack; the secondary edit belongs to its fallback source.
data class IconSourceOptions(
    val primarySource: Source,
    val primaryTextType: TextType = TextType.FULL_NAME,
    val primaryIconPack: String = "",
    val secondarySource: Source = Source.NONE,
    val secondaryImageEdit: ImageEdit = ImageEdit.NONE,
    val secondaryTextType: TextType = TextType.FULL_NAME,
    val secondaryIconPack: String = "",
    val textCustom: String = "",
    val textCase: TextCase = TextCase.AS_IS,
    val textFontPath: String = "",
    val fallbackSource: FallbackSource = FallbackSource.NONE,
    val applicationIconVariant: ApplicationIconVariant = ApplicationIconVariant.DEFAULT,
    val invertMonochrome: Boolean = false,
    val useFullApplicationIcon: Boolean = false
)

/** Letter-case transform for text icons (per-app option; not persisted globally). */
enum class TextCase { AS_IS, UPPER, LOWER }

/** How an app's own launcher icon is represented in the per-app editor. */
enum class ApplicationIconVariant { DEFAULT, MATERIAL_YOU, MONOCHROME }
