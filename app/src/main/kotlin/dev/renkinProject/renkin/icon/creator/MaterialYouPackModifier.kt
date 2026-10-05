package dev.renkinProject.renkin.icon.creator

data class MaterialYouPackModifier(
    val foreground: Int? = null,
    val background: Int? = null,
    val selectedScheme: Int = -1,
    val customForeground: ColorizerStyle? = null,
    val customBackground: ColorizerStyle? = null,
    val strokeScale: Float = 1f
) {
    val hasRestyling: Boolean
        get() = foreground != null || background != null || strokeScale != 1f
}
