package dev.renkinProject.renkin.icon.creator

// PATH monochrome export and selection of an application's own icon variant are independent.
data class IconExportOptions(
    val vector: Boolean = false,
    // Selects the monochrome layer for PATH, independently of source.applicationIconVariant.
    // Only legacy preferences and the editor explicitly map between these two choices.
    val materialYou: Boolean = false,
    val themed: Boolean = false,
    val background: ColorFill = ColorFill(android.graphics.Color.TRANSPARENT),
    val foregroundStyle: ColorizerStyle? = null
)
