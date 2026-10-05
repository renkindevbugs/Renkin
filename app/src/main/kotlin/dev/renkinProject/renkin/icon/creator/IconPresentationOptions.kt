package dev.renkinProject.renkin.icon.creator

internal data class IconPresentationOptions(
    val themed: Boolean,
    val background: ColorFill,
    val materialYouPack: MaterialYouPackPresentation
)

internal data class MaterialYouPackPresentation(
    val foreground: Int?,
    val background: Int?,
    val selectedScheme: Int,
    val customForeground: ColorizerStyle?,
    val customBackground: ColorizerStyle?,
    val strokeScale: Float
)

internal val GenerationOptions.iconPresentation: IconPresentationOptions
    get() = IconPresentationOptions(
        themed = themed,
        background = ColorFill(bgColor, backgroundStyle),
        materialYouPack = MaterialYouPackPresentation(
            foreground = materialYouPackForeground,
            background = materialYouPackBackground,
            selectedScheme = materialYouPackSelectedScheme,
            customForeground = materialYouPackCustomForeground,
            customBackground = materialYouPackCustomBackground,
            strokeScale = materialYouPackStrokeScale
        )
    )
