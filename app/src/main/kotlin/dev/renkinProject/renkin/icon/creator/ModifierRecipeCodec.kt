package dev.renkinProject.renkin.icon.creator

import android.graphics.Color
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.OUTLINE_WIDTH_MAX
import dev.renkinProject.renkin.data.OUTLINE_WIDTH_MIN

/** Group encoding shared by full icon recipes and the artwork-independent subset in presets. */
internal object ModifierRecipeCodec {
    private const val VERSION = 1
    // Extend this range only for wire-compatible versions; incompatible changes need an adapter.
    private const val FIRST_SUPPORTED_VERSION = 1
    private const val MAX_RECIPE_CHARS = 1024 * 1024
    private const val MAX_RECIPE_FIELDS = 8192
    private const val MAX_SEGMENT_LAYERS = 128
    private const val MAX_TARGET_COLORS = 256

    private enum class ReadMode { PRESET, SAVED_STATE, IMPORTED_RECIPE }

    // Session masks are deliberately omitted. Persistence callers must keep them separately
    // or call requireEncodable when their contract requires a complete, mask-free recipe.
    fun encode(stack: ModifierStack): String {
        return Writer(presetDocument = false).apply {
            put("recipe.version", VERSION)
            effect(stack.effect)
            put("transform", 1)
            put("transform.scale", stack.transform.scale)
            put("transform.offsetX", stack.transform.offsetX)
            put("transform.offsetY", stack.transform.offsetY)
            stack.shape?.let { shape(it) }
            stack.outline?.let { outline(it) }
            stack.shadow?.let { shadow(it) }
            pack(stack.materialYouPack)
        }.encode()
    }

    fun requireEncodable(stack: ModifierStack) {
        require(stack.outline?.eraseMask == null && stack.effect.backgroundRemoval.brushOperations.isEmpty()) {
            "Session masks cannot be encoded in a modifier recipe"
        }
    }

    /** The default boundary for recipes coming from a database, backup or shared document. */
    fun decode(encoded: String): ModifierStack? = decode(encoded, ReadMode.IMPORTED_RECIPE)

    /** Restore our own Bundle without truncating editor values or inactive configuration. */
    fun decodeSavedState(encoded: String): ModifierStack? = decode(encoded, ReadMode.SAVED_STATE)

    private fun decode(encoded: String, mode: ReadMode): ModifierStack? {
        val imported = mode == ReadMode.IMPORTED_RECIPE
        if (imported && encoded.length > MAX_RECIPE_CHARS) return null
        val values = parse(encoded, rejectDuplicates = true,
            maxFields = if (imported) MAX_RECIPE_FIELDS else Int.MAX_VALUE) ?: return null
        val version = values["recipe.version"]?.toIntOrNull() ?: return null
        if (version !in FIRST_SUPPORTED_VERSION..VERSION ||
            values["effect"] == null || values["transform"] == null) return null
        val segmentCount = values["effect.segments"]?.let { it.toIntOrNull() ?: return null } ?: 0
        // Each encoded segment contributes fields. A forged count cannot allocate more layers
        // than the document can actually describe, without limiting real editor sessions.
        if (segmentCount !in 0..values.size) return null
        if (imported && segmentCount > MAX_SEGMENT_LAYERS) return null
        return Reader(values, mode).run {
            ModifierStack(
                effect = effect(),
                transform = TransformModifier(
                    number("transform.scale", 1f, 0.5f..1.5f),
                    number("transform.offsetX", 0f, -0.5f..0.5f),
                    number("transform.offsetY", 0f, -0.5f..0.5f)
                ),
                shape = if (has("shape")) shape() else null,
                outline = if (has("outline")) outline() else null,
                shadow = if (has("shadow")) shadow() else null,
                materialYouPack = pack()
            )
        }
    }

    // Preset v1 keeps its exact wire format, including inline legacy colour-style strings.
    fun encodePreset(payload: ModifierPresetPayload): String = Writer(presetDocument = true).apply {
        put("v", payload.schemaVersion)
        payload.effect?.let {
            effect(EffectModifier(
                imageEdit = it.imageEdit,
                colorizer = it.colorizerStyle,
                edges = EdgeModifier(it.edgeThreshold, it.edgeThreshold * 3f, it.edgeSmoothing, it.edgeContrast),
                backgroundRemoval = BackgroundRemovalModifier(tolerance = it.backgroundTolerance)
            ))
        }
        payload.iconScale?.let { put("iconScale", it) }
        payload.shape?.let {
            shape(ShapeModifier(it.shape, it.crop, it.scale, ColorFill(it.style.firstColor, it.style)))
        }
        payload.outline?.let {
            outline(OutlineModifier(it.mode, it.width, ColorFill(it.style.firstColor, it.style)))
        }
        payload.shadow?.let {
            shadow(ShadowModifier(it.blur, it.distance, it.angle, it.allDirections, it.style, it.opacity, it.enabled))
        }
    }.encode()

    fun decodePreset(encoded: String): ModifierPresetPayload? {
        val values = parse(encoded, rejectDuplicates = false) ?: return null
        return Reader(values, ReadMode.PRESET).run {
            ModifierPresetPayload(
                schemaVersion = values["v"]?.toIntOrNull() ?: MODIFIER_PRESET_SCHEMA_VERSION,
                effect = if (has("effect")) effect().let {
                    ModifierPresetEffect(
                        imageEdit = it.imageEdit,
                        edgeThreshold = it.edges.lowThreshold,
                        edgeSmoothing = it.edges.smoothing,
                        edgeContrast = it.edges.normalizeContrast,
                        backgroundTolerance = it.backgroundRemoval.tolerance,
                        colorizerStyle = it.colorizer
                    )
                } else null,
                iconScale = values["iconScale"]?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0.5f, 1.5f),
                shape = if (has("shape")) shape().let {
                    ModifierPresetShape(it.shape, it.crop, it.scale, requireNotNull(it.fill?.style))
                } else null,
                outline = if (has("outline")) outline().let {
                    ModifierPresetOutline(it.mode, it.width, requireNotNull(it.fill.style))
                } else null,
                shadow = if (has("shadow")) shadow().let {
                    ModifierPresetShadow(it.enabled, it.blur, it.distance, it.angle, it.allDirections, it.style, it.opacity)
                } else null
            ).takeIf { !it.isEmpty }
        }
    }

    private fun parse(encoded: String, rejectDuplicates: Boolean, maxFields: Int = Int.MAX_VALUE): Map<String, String>? {
        val values = linkedMapOf<String, String>()
        for (line in encoded.lineSequence()) {
            val separator = line.indexOf('=')
            if (separator <= 0) {
                if (rejectDuplicates && line.isNotBlank()) return null
                continue
            }
            val key = line.substring(0, separator).trim()
            if (rejectDuplicates && key in values) return null
            if (key !in values && values.size >= maxFields) return null
            values[key] = line.substring(separator + 1)
        }
        return values.takeIf { it.isNotEmpty() }
    }

    private class Writer(private val presetDocument: Boolean) {
        private val values = linkedMapOf<String, String>()

        fun put(key: String, value: Any) { values[key] = value.toString() }
        fun encode(): String = values.entries.joinToString("\n") { "${it.key}=${it.value}" }

        fun effect(effect: EffectModifier) {
            put("effect", 1)
            put("effect.edit", effect.imageEdit.name)
            put("effect.edgeThreshold", effect.edges.lowThreshold)
            put("effect.edgeSmoothing", effect.edges.smoothing)
            put("effect.edgeContrast", effect.edges.normalizeContrast)
            put("effect.bgTolerance", effect.backgroundRemoval.tolerance)
            style("effect.style", effect.colorizer)
            if (!presetDocument) {
                put("effect.edgeHighThreshold", effect.edges.highThreshold)
                put("effect.bgTargets", effect.backgroundRemoval.targets.joinToString(","))
                put("effect.segments", effect.segments.size)
                effect.segments.forEachIndexed { index, layer ->
                    val key = "effect.segment.$index"
                    put("$key.targets", layer.targets.joinToString(","))
                    put("$key.tolerance", layer.tolerance)
                    style("$key.style", layer.style)
                }
            }
        }

        fun shape(shape: ShapeModifier) {
            put("shape", 1)
            put("shape.name", shape.shape.name)
            put("shape.crop", shape.crop)
            put("shape.scale", shape.scale)
            if (presetDocument) {
                style("shape.style", requireNotNull(shape.fill?.style))
            } else shape.fill?.let { fill("shape", it) }
        }

        fun outline(outline: OutlineModifier) {
            put("outline", 1)
            put("outline.mode", outline.mode.name)
            put("outline.width", outline.width)
            if (presetDocument) {
                style("outline.style", requireNotNull(outline.fill.style))
            } else fill("outline", outline.fill)
        }

        fun shadow(shadow: ShadowModifier) {
            put("shadow", 1)
            put("shadow.enabled", shadow.enabled)
            put("shadow.blur", shadow.blur)
            put("shadow.distance", shadow.distance)
            put("shadow.angle", shadow.angle)
            put("shadow.allDirections", shadow.allDirections)
            style("shadow.style", shadow.style)
            put("shadow.opacity", shadow.opacity)
        }

        private fun fill(key: String, fill: ColorFill) {
            put("$key.fill", 1)
            put("$key.color", fill.color)
            fill.style?.let { style("$key.style", it) }
        }

        fun pack(pack: MaterialYouPackModifier) {
            put("pack", 1)
            pack.foreground?.let { put("pack.foreground", it) }
            pack.background?.let { put("pack.background", it) }
            put("pack.selectedScheme", pack.selectedScheme)
            pack.customForeground?.let { style("pack.customForeground", it) }
            pack.customBackground?.let { style("pack.customBackground", it) }
            put("pack.strokeScale", pack.strokeScale)
        }

        private fun style(key: String, style: ColorizerStyle) {
            if (presetDocument) {
                put(key, encodeColorizerStyle(style))
                return
            }
            put("$key.mode", style.mode.name)
            put("$key.gradientType", style.gradientType.name)
            put("$key.firstColor", style.firstColor)
            put("$key.gradientStops", style.gradientStops.joinToString(","))
            put("$key.gradientPositions", style.gradientPositions.joinToString(","))
            put("$key.gradientAngle", style.gradientAngle)
            put("$key.flat", style.flat)
            put("$key.monochrome", style.monochrome)
            put("$key.inverse", style.inverse)
            put("$key.lighten", style.lighten)
        }
    }

    private class Reader(private val values: Map<String, String>, private val mode: ReadMode) {
        private val presetDocument = mode == ReadMode.PRESET
        private val importedRecipe = mode == ReadMode.IMPORTED_RECIPE

        fun has(key: String): Boolean = key in values
        fun number(key: String, fallback: Float, range: ClosedFloatingPointRange<Float>? = null): Float {
            val value = values[key]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: return fallback
            return if (mode != ReadMode.SAVED_STATE && range != null) value.coerceIn(range.start, range.endInclusive) else value
        }
        private fun boolean(key: String, fallback: Boolean = false): Boolean =
            values[key]?.toBooleanStrictOrNull() ?: fallback
        private fun integer(key: String, fallback: Int): Int = values[key]?.toIntOrNull() ?: fallback
        private inline fun <reified T : Enum<T>> enumeration(key: String, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == values[key] } ?: fallback
        private fun integers(key: String, limit: Int = MAX_TARGET_COLORS): List<Int> =
            values[key]?.splitToSequence(',')?.mapNotNull { it.toIntOrNull() }
                ?.take(if (importedRecipe) limit else Int.MAX_VALUE)?.toList().orEmpty()

        private fun positions(key: String): List<Float> =
            values[key]?.splitToSequence(',')?.mapNotNull { it.toFloatOrNull()?.takeIf { value -> value.isFinite() } }
                ?.take(if (importedRecipe) MAX_GRADIENT_STOPS else Int.MAX_VALUE)?.toList().orEmpty()

        fun effect(): EffectModifier {
            val low = number("effect.edgeThreshold", 2.5f, 0.5f..5f)
            return EffectModifier(
                imageEdit = enumeration("effect.edit", ImageEdit.NONE),
                colorizer = style("effect.style", Color.WHITE),
                edges = EdgeModifier(
                    lowThreshold = low,
                    highThreshold = if (presetDocument) low * 3f else number("effect.edgeHighThreshold", low * 3f, low..15f),
                    smoothing = number("effect.edgeSmoothing", 2f, 0.5f..4f),
                    normalizeContrast = boolean("effect.edgeContrast")
                ),
                backgroundRemoval = BackgroundRemovalModifier(
                    targets = if (presetDocument) emptyList() else integers("effect.bgTargets"),
                    tolerance = number("effect.bgTolerance", 0.1f, 0f..0.5f)
                ),
                segments = if (presetDocument) emptyList() else List(integer("effect.segments", 0)) { index ->
                    val key = "effect.segment.$index"
                    SegmentLayer(
                        targets = integers("$key.targets"),
                        tolerance = number("$key.tolerance", SEGMENT_TOLERANCE_DEFAULT, 0f..1f),
                        style = style("$key.style", Color.WHITE)
                    )
                }
            )
        }

        fun shape(): ShapeModifier = ShapeModifier(
            shape = enumeration("shape.name", IconShape.NONE),
            crop = boolean("shape.crop", true),
            scale = number("shape.scale", 1f, 0.5f..1.5f),
            fill = if (presetDocument) style("shape.style", Color.WHITE).let { ColorFill(it.firstColor, it) }
                else if (has("shape.fill")) fill("shape", Color.WHITE) else null
        )

        fun outline(): OutlineModifier = OutlineModifier(
            mode = enumeration("outline.mode", OutlineMode.NONE),
            width = number("outline.width", 6f, OUTLINE_WIDTH_MIN.toFloat()..OUTLINE_WIDTH_MAX.toFloat()),
            fill = if (presetDocument) style("outline.style", Color.WHITE).let { ColorFill(it.firstColor, it) }
                else fill("outline", Color.BLACK)
        )

        fun shadow(): ShadowModifier = ShadowModifier(
            blur = number("shadow.blur", SHADOW_BLUR_DEFAULT, SHADOW_BLUR_MIN..SHADOW_BLUR_MAX),
            distance = number("shadow.distance", SHADOW_DISTANCE_DEFAULT, SHADOW_DISTANCE_MIN..SHADOW_DISTANCE_MAX),
            angle = number("shadow.angle", SHADOW_ANGLE_DEFAULT, 0f..360f),
            allDirections = boolean("shadow.allDirections"),
            style = style("shadow.style", Color.BLACK),
            opacity = number("shadow.opacity", SHADOW_OPACITY_DEFAULT, SHADOW_OPACITY_MIN..SHADOW_OPACITY_MAX),
            enabled = boolean("shadow.enabled")
        )

        private fun fill(key: String, fallbackColor: Int): ColorFill = ColorFill(
            color = integer("$key.color", fallbackColor),
            style = if (has("$key.style.mode")) style("$key.style", fallbackColor) else null
        )

        fun pack(): MaterialYouPackModifier = MaterialYouPackModifier(
            foreground = values["pack.foreground"]?.toIntOrNull(),
            background = values["pack.background"]?.toIntOrNull(),
            selectedScheme = integer("pack.selectedScheme", -1),
            customForeground = if (has("pack.customForeground.mode")) style("pack.customForeground", Color.BLACK) else null,
            customBackground = if (has("pack.customBackground.mode")) style("pack.customBackground", Color.WHITE) else null,
            strokeScale = number("pack.strokeScale", 1f, 0.5f..2f)
        )

        private fun style(key: String, fallbackColor: Int): ColorizerStyle {
            if (presetDocument) return values[key]?.let(::decodeColorizerStyle) ?: ColorizerStyle(firstColor = fallbackColor)
            val stops = if (has("$key.gradientStops")) integers("$key.gradientStops", MAX_GRADIENT_STOPS - 1)
                else listOf(Color.BLACK)
            val safeStops = if (importedRecipe) stops.ifEmpty { listOf(Color.BLACK) } else stops
            val positions = positions("$key.gradientPositions")
            val safePositions = if (!importedRecipe) positions else if (positions.size == safeStops.size + 1) {
                clampedGradientPositions(positions)
            } else emptyList()
            return ColorizerStyle(
                mode = enumeration("$key.mode", ColorizerMode.SINGLE_COLOR),
                gradientType = enumeration("$key.gradientType", GradientType.LINEAR),
                firstColor = integer("$key.firstColor", fallbackColor),
                gradientStops = safeStops,
                gradientPositions = safePositions,
                gradientAngle = number("$key.gradientAngle", 0f, 0f..360f),
                flat = boolean("$key.flat"),
                monochrome = boolean("$key.monochrome"),
                inverse = boolean("$key.inverse"),
                lighten = boolean("$key.lighten")
            )
        }
    }
}
