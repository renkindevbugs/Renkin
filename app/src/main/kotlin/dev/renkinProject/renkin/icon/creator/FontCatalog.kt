package dev.renkinProject.renkin.icon.creator

import android.graphics.Typeface
import java.io.File

/**
 * The fonts text icons can render with: the bundled Arcticons Sans (represented by an empty
 * path) plus whatever TTF/OTF files the device ships in /system/fonts — Roboto variants and
 * any OEM families. Text is drawn via glyph→path extraction, so every font stays a vector.
 */
object FontCatalog {

    data class FontChoice(val label: String, val path: String)

    /** Empty path = the bundled default; the UI names it explicitly. */
    val DEFAULT = FontChoice("Arcticons Sans", "")

    fun systemFonts(): List<FontChoice> =
        File("/system/fonts").listFiles { file -> file.extension.lowercase() in FONT_EXTENSIONS }
            .orEmpty()
            // Emoji and CJK megafonts make useless monograms and enormous lists.
            .filterNot { file -> EXCLUDED.any { file.name.contains(it, ignoreCase = true) } }
            .map { FontChoice(prettyName(it.name), it.absolutePath) }
            .sortedBy { it.label.lowercase() }

    fun typeface(path: String): Typeface? =
        runCatching { Typeface.createFromFile(path) }.getOrNull()

    /** Display name for a stored [path] without rescanning the directory. */
    fun prettyLabelFor(path: String): String = prettyName(File(path).name)

    /** "RobotoCondensed-BoldItalic.ttf" → "Roboto Condensed Bold Italic". */
    private fun prettyName(fileName: String): String = fileName
        .substringBeforeLast('.')
        .replace('-', ' ')
        .replace('_', ' ')
        .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
        .replace(Regex(" +"), " ")
        .trim()

    private val FONT_EXTENSIONS = setOf("ttf", "otf")
    private val EXCLUDED = listOf("Emoji", "NotoSansCJK", "NotoSerifCJK", "Clock")
}
