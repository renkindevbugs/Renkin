package dev.renkinProject.renkin.ui

import androidx.compose.ui.graphics.Color
import dev.renkinProject.renkin.icon.creator.ColorizerStyle

internal data class CreateSourceColors(
    val foreground: Color,
    val background: Color,
    val foregroundStyle: ColorizerStyle?,
    val backgroundStyle: ColorizerStyle?
)

/**
 * The Material You variant uses a wallpaper scheme's two flat colours, or the Custom controls when
 * [wallpaperScheme] is null; official and generated variants keep the same roles. Other sources
 * use the icon colour and only a non-cropping shape plate as background. The plate's own style is
 * applied with the modifier adjustments, not here.
 */
internal fun createSourceColors(
    materialYouVariant: Boolean,
    wallpaperScheme: Pair<Color, Color>?,
    iconColor: Color,
    customBackground: Color,
    customForegroundStyle: ColorizerStyle,
    customBackgroundStyle: ColorizerStyle,
    shapePlate: Color?
): CreateSourceColors = when {
    materialYouVariant && wallpaperScheme != null ->
        CreateSourceColors(wallpaperScheme.first, wallpaperScheme.second, null, null)
    materialYouVariant ->
        CreateSourceColors(iconColor, customBackground, customForegroundStyle, customBackgroundStyle)
    else -> CreateSourceColors(iconColor, shapePlate ?: Color.Transparent, null, null)
}
