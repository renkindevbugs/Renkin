package dev.renkinProject.renkin.icon.creator

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.data.AppIconFullIconKey
import dev.renkinProject.renkin.data.AppIconInvertMonochromeKey
import dev.renkinProject.renkin.data.AppIconMaterialYouBackgroundStyleKeys
import dev.renkinProject.renkin.data.AppIconMaterialYouForegroundStyleKeys
import dev.renkinProject.renkin.data.AppIconMaterialYouSchemeKey
import dev.renkinProject.renkin.data.AppIconVariantKey
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.data.getEnumValue
import dev.renkinProject.renkin.data.getIntValue

/**
 * The profile-wide look for apps drawn from their own launcher icon (issue #12), as the refresh
 * applies it: the editor's per-app variant choice, made once for every app.
 */
data class ApplicationIconStyle(
    val variant: ApplicationIconVariant = ApplicationIconVariant.DEFAULT,
    val fullIcon: Boolean = false,
    val invertMonochrome: Boolean = false,
    // Selected wallpaper scheme index; an index past the last scheme means the custom colours.
    val materialYouScheme: Int = 0
)

fun Preferences.applicationIconStyle(): ApplicationIconStyle = ApplicationIconStyle(
    variant = getEnumValue(AppIconVariantKey, ApplicationIconVariant.DEFAULT),
    fullIcon = getBooleanValue(AppIconFullIconKey),
    invertMonochrome = getBooleanValue(AppIconInvertMonochromeKey),
    materialYouScheme = getIntValue(AppIconMaterialYouSchemeKey, 0)
)

/** Foreground and background styles of the Material You variant, as the generator paints them. */
data class MaterialYouColors(val foreground: ColorizerStyle, val background: ColorizerStyle) {
    val foregroundColor: Int get() = foreground.firstColor
    val backgroundColor: Int get() = background.firstColor
}

/**
 * Resolves the selected scheme against the wallpaper at the time of the call, so a refresh after
 * a wallpaper change picks up its colours. The custom colours default to the first scheme.
 */
fun Preferences.applicationIconMaterialYouColors(context: Context, schemeIndex: Int): MaterialYouColors {
    val schemes = materialYouSchemes(context)
    schemes.getOrNull(schemeIndex)?.let { (foreground, background) ->
        return MaterialYouColors(
            ColorizerStyle(firstColor = foreground.toArgb()),
            ColorizerStyle(firstColor = background.toArgb())
        )
    }
    val (defaultForeground, defaultBackground) = schemes.first()
    return MaterialYouColors(
        colorStyle(AppIconMaterialYouForegroundStyleKeys, defaultForeground),
        colorStyle(AppIconMaterialYouBackgroundStyleKeys, defaultBackground)
    )
}
