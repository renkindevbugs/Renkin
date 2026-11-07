package dev.renkinProject.renkin.data.online

import android.util.JsonReader
import android.util.JsonToken
import java.io.Reader
import java.io.StringReader
import kotlin.math.ceil
import kotlin.math.floor

internal sealed interface IconLookup {
    data class Markup(val svg: String) : IconLookup
    data object Missing : IconLookup
    /** Transformed icons and aliases with overrides are left to the API's own SVG. */
    data object NeedsDirectFetch : IconLookup
}

/**
 * One IconifyJSON document: an API batch response, a bundled asset or a downloaded `icons.json`.
 * Bodies stay UTF-8 bytes until an icon is looked up, which halves the memory of a whole set.
 */
internal class IconifySet(
    val prefix: String,
    private val icons: Map<String, IconifyIcon>,
    /** Alias name to parent, or null when the alias changes the parent (rotation, flip, size). */
    private val aliases: Map<String, String?>,
    private val defaults: IconifyIcon
) {
    val visibleNames: List<String> by lazy {
        icons.filterValues { !it.hidden }.keys.sorted()
    }

    fun lookup(name: String): IconLookup {
        icons[name]?.let { return markup(it) }
        if (name !in aliases) return IconLookup.Missing
        val parent = aliases[name] ?: return IconLookup.NeedsDirectFetch
        icons[parent]?.let { return markup(it) }
        return if (parent in aliases) IconLookup.NeedsDirectFetch else IconLookup.Missing
    }

    private fun markup(icon: IconifyIcon): IconLookup {
        if (icon.transformed) return IconLookup.NeedsDirectFetch
        fun dimension(value: Double, root: Double, default: Double) =
            if (!value.isNaN()) value else if (!root.isNaN()) root else default
        return IconLookup.Markup(
            iconifySvg(
                body = String(icon.body, Charsets.UTF_8),
                left = dimension(icon.left, defaults.left, 0.0),
                top = dimension(icon.top, defaults.top, 0.0),
                width = dimension(icon.width, defaults.width, DEFAULT_SIZE),
                height = dimension(icon.height, defaults.height, DEFAULT_SIZE)
            )
        )
    }

    companion object {
        private const val DEFAULT_SIZE = 16.0
    }
}

internal class IconifyIcon(
    val body: ByteArray = ByteArray(0),
    val left: Double = Double.NaN,
    val top: Double = Double.NaN,
    val width: Double = Double.NaN,
    val height: Double = Double.NaN,
    val transformed: Boolean = false,
    val hidden: Boolean = false
)

internal object IconifyJson {
    fun parse(json: String): IconifySet? = parse(StringReader(json))

    /** Streams the document so a multi-megabyte set never exists as one String or JSON tree. */
    fun parse(source: Reader): IconifySet? = runCatching {
        JsonReader(source).use { reader ->
            var prefix: String? = null
            var icons: Map<String, IconifyIcon>? = null
            var aliases: Map<String, String?> = emptyMap()
            var defaults = IconifyIcon()
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "prefix" -> prefix = reader.nextString()
                    "icons" -> icons = readIcons(reader)
                    "aliases" -> aliases = readAliases(reader)
                    "left" -> defaults = defaults.copy(left = reader.nextDouble())
                    "top" -> defaults = defaults.copy(top = reader.nextDouble())
                    "width" -> defaults = defaults.copy(width = reader.nextDouble())
                    "height" -> defaults = defaults.copy(height = reader.nextDouble())
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            IconifySet(prefix ?: return null, icons ?: return null, aliases, defaults)
        }
    }.getOrNull()

    private fun IconifyIcon.copy(
        left: Double = this.left,
        top: Double = this.top,
        width: Double = this.width,
        height: Double = this.height
    ) = IconifyIcon(body, left, top, width, height, transformed, hidden)

    private fun readIcons(reader: JsonReader): Map<String, IconifyIcon> {
        val icons = HashMap<String, IconifyIcon>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            var body: String? = null
            var left = Double.NaN
            var top = Double.NaN
            var width = Double.NaN
            var height = Double.NaN
            var transformed = false
            var hidden = false
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "body" -> body = reader.nextString()
                    "left" -> left = reader.nextDouble()
                    "top" -> top = reader.nextDouble()
                    "width" -> width = reader.nextDouble()
                    "height" -> height = reader.nextDouble()
                    "rotate" -> transformed = reader.nextInt() % 4 != 0 || transformed
                    "hFlip", "vFlip" -> transformed = reader.nextBoolean() || transformed
                    "hidden" -> hidden = reader.nextBoolean()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (!body.isNullOrEmpty()) {
                icons[name] = IconifyIcon(body.toByteArray(Charsets.UTF_8), left, top, width, height, transformed, hidden)
            }
        }
        reader.endObject()
        return icons
    }

    private fun readAliases(reader: JsonReader): Map<String, String?> {
        val aliases = HashMap<String, String?>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            var parent: String? = null
            var plain = true
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "parent" && reader.peek() == JsonToken.STRING) {
                    parent = reader.nextString()
                } else {
                    plain = false
                    reader.skipValue()
                }
            }
            reader.endObject()
            if (parent != null) aliases[name] = parent.takeIf { plain }
        }
        reader.endObject()
        return aliases
    }
}

/** Matches the API's own SVG: height 1em and a width rounded up to 1/100 em from the aspect ratio. */
internal fun iconifySvg(body: String, left: Double, top: Double, width: Double, height: Double): String {
    val widthEm = if (width == height) "1em" else "${svgNumber(ceil(width / height * 100) / 100)}em"
    val viewBox = listOf(left, top, width, height).joinToString(" ") { svgNumber(it) }
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$widthEm\" height=\"1em\" " +
        "viewBox=\"$viewBox\">$body</svg>"
}

private fun svgNumber(value: Double): String =
    if (value == floor(value)) value.toLong().toString() else value.toString()
