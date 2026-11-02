package dev.renkinProject.renkin.feature.language

import dev.renkinProject.renkin.BuildConfig
import java.util.Locale

/**
 * A UI language the app ships strings for. [tag] is a BCP 47 tag matching a `values-*`
 * resource folder (`zh-CN` ↔ `values-zh-rCN`); English is the unqualified `values` folder.
 * [flag] is an emoji so no flag artwork has to be bundled.
 */
data class AppLanguage(val tag: String, val flag: String) {
    /** The language's own name ("Español"), which stays readable whatever the UI language is. */
    val nativeName: String
        get() {
            val locale = Locale.forLanguageTag(tag)
            return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
        }

    /** Share of the English strings this translation covers; null for English itself. */
    val translatedPercent: Int?
        get() = translationCoverage[resourceQualifier(tag)]
}

private val translationCoverage: Map<String, Int> = BuildConfig.TRANSLATION_COVERAGE
    .split(',').filter { it.isNotEmpty() }
    .associate { it.substringBefore('=') to it.substringAfter('=').toInt() }

// `zh-CN` lives in `values-zh-rCN`.
private fun resourceQualifier(tag: String): String {
    val parts = tag.split('-')
    return if (parts.size == 2) "${parts[0]}-r${parts[1]}" else tag
}

/**
 * Every language with a `values-*` folder. Add an entry together with the translation;
 * `AppLanguagesTest` fails when this list and the resource folders disagree.
 */
val SupportedAppLanguages: List<AppLanguage> = listOf(
    AppLanguage("en", "🇺🇸"),
    AppLanguage("es", "🇪🇸"),
    AppLanguage("zh-CN", "🇨🇳"),
)

/**
 * The entry for a stored tag, falling back to the same language: Android 13's system settings
 * can store a regional tag such as `es-MX` that resolves to the plain `values-es` strings.
 */
fun supportedAppLanguage(tag: String): AppLanguage? {
    if (tag.isEmpty()) return null
    val primaryTag = tag.substringBefore(',')
    return SupportedAppLanguages.firstOrNull { it.tag.equals(primaryTag, ignoreCase = true) }
        ?: SupportedAppLanguages.firstOrNull {
            Locale.forLanguageTag(it.tag).language == Locale.forLanguageTag(primaryTag).language
        }
}
