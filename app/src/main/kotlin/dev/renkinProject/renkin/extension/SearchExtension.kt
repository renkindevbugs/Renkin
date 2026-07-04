package dev.renkinProject.renkin.extension

/**
 * Normalises an icon-search query to the snake_case form pack drawables use, so it can be
 * matched against drawable names with a plain substring check.
 *
 * Every run of whitespace becomes a single underscore and the ends are trimmed. Crucially this
 * is Unicode-aware ([Char.isWhitespace] covers the non-breaking space U+00A0 and friends), so a
 * query seeded from an app label that uses an unusual space — e.g. "HBO<nbsp>Max" — still matches
 * the drawable "hbo_max". The old `replace(' ', '_')` only handled the ASCII space and left such
 * names unmatched until the user retyped them with a normal space.
 */
fun String.normalizeIconSearchQuery(): String =
    lowercase()
        .map { if (it.isWhitespace()) '_' else it }
        .joinToString("")
        .replace(Regex("_+"), "_")
        .trim('_')

/**
 * Human-readable form of a drawable name, the way icon-pack companion apps display their
 * icons: underscores become spaces and each word is capitalised ("google_action_blocks" →
 * "Google Action Blocks"). Trailing separators (calendar prefixes like "bee_calendar_") drop.
 */
fun String.prettyDrawableName(): String = trim('_')
    .split('_')
    .filter { it.isNotEmpty() }
    .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
