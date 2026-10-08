package dev.renkinProject.renkin.data.editor

import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextType
import dev.renkinProject.renkin.icon.creator.ApplicationIconVariant
import dev.renkinProject.renkin.icon.creator.BrushAction
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.GradientType
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.OutlineMode
import dev.renkinProject.renkin.icon.creator.SegmentLayer
import dev.renkinProject.renkin.icon.creator.TextCase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Fixed versioned fields preserve inactive controls and exact finite float/packed-color bits. */
internal object EditorControlsCodec {
    const val VERSION = 1
    private const val MAGIC = 0x524B4544 // RKED
    const val MAX_TEXT_BYTES = 64 * 1024
    const val MAX_STYLE_VALUES = 256
    const val MAX_SEGMENTS = 128
    const val MAX_STROKES = 2048
    const val MAX_POINTS = 100_000

    fun encode(snapshot: EditorControlsSnapshot): ByteArray {
        require(snapshot.selectedTab in 0..3 && snapshot.create.materialYouScheme >= 0) { "Invalid editor selection" }
        val bytes = BoundedOutput()
        val writer = Writer(DataOutputStream(bytes))
        with(writer) {
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeInt(snapshot.selectedTab)
            create(snapshot.create)
            boolean(snapshot.upload.asAdaptiveIcon)
            number(snapshot.upload.zoomLevel)
            vector(snapshot.vector)
            adjustments(snapshot.adjustments)
            output.writeInt(snapshot.materialYouPack.selectedScheme)
            style(snapshot.materialYouPack.customForeground)
            style(snapshot.materialYouPack.customBackground)
            number(snapshot.materialYouPack.strokeScale)
            boolean(snapshot.calendar.enabled)
            optionalText(snapshot.calendar.prefix)
            optionalText(snapshot.calendar.packName)
            optionalText(snapshot.onlineImageUrl)
        }
        return bytes.toByteArray()
    }

    fun decode(encoded: ByteArray): EditorControlsSnapshot {
        require(encoded.size.toLong() in 1..EditorDraftCodec.MAX_CONTROLS_BYTES) { "Invalid editor controls size" }
        try {
            return DataInputStream(ByteArrayInputStream(encoded)).use { input ->
                require(input.readInt() == MAGIC && input.readInt() == VERSION) { "Unsupported editor controls format" }
                val reader = Reader(input)
                val tab = input.readInt().also { require(it in 0..3) { "Invalid editor tab" } }
                val snapshot = with(reader) {
                    EditorControlsSnapshot(
                        selectedTab = tab,
                        create = create(),
                        upload = EditorUploadControls(boolean(), number()),
                        vector = vector(),
                        adjustments = adjustments(),
                        materialYouPack = EditorPackControls(input.readInt(), style(), style(), number()),
                        calendar = EditorCalendarControls(boolean(), optionalText(), optionalText()),
                        onlineImageUrl = optionalText()
                    )
                }
                require(input.read() == -1) { "Trailing editor controls content" }
                snapshot
            }
        } catch (error: IOException) {
            throw IllegalArgumentException("Invalid editor controls document", error)
        }
    }

    private class Writer(val output: DataOutputStream) {
        private var pointCount = 0

        fun create(value: EditorCreateControls) = with(value) {
            enumeration(source); boolean(browsingIconPacks); enumeration(imageEdit); enumeration(textType)
            text(customText); enumeration(textCase); text(fontReference); boolean(useVector)
            enumeration(applicationIconVariant); boolean(useFullApplicationIcon); boolean(invertMonochrome)
            output.writeInt(materialYouScheme); output.writeLong(iconColorBits); output.writeLong(backgroundColorBits)
            style(materialYouForegroundStyle); style(materialYouBackgroundStyle); text(iconPack); boolean(replayMaterialYou)
        }

        fun vector(value: EditorVectorControls) = with(value) {
            boolean(initialized); number(thickness); boolean(automaticallyCenter); boolean(viewport != null)
            viewport?.let { number(it.width); number(it.height) }
            optionalText(sourceUrl); boolean(containsBakedModifierEdits)
        }

        fun adjustments(value: EditorAdjustmentControls) = with(value) {
            style(colorizerStyle); style(shapeStyle); style(outlineStyle); style(shadowStyle)
            number(edgeThreshold); number(edgeSmoothing); boolean(edgeContrast)
            list(colorizeLayers, MAX_SEGMENTS) { layer ->
                list(layer.targets, MAX_STYLE_VALUES, output::writeInt); number(layer.tolerance); style(layer.style)
            }
            number(iconScale); list(backgroundTargets, MAX_STYLE_VALUES, output::writeInt); number(backgroundTolerance)
            boolean(autoCenter); number(offsetX); number(offsetY); enumeration(shape); boolean(shapeCrop); number(shapeScale)
            enumeration(outlineMode); number(outlineWidth); boolean(shadowEnabled); number(shadowBlur); number(shadowDistance)
            number(shadowAngle); boolean(shadowAllDirections); number(shadowOpacity)
            strokes(eraseStrokes); strokes(backgroundBrushStrokes)
        }

        fun style(value: ColorizerStyle) = with(value) {
            enumeration(mode); enumeration(gradientType); output.writeInt(firstColor)
            list(gradientStops, MAX_STYLE_VALUES, output::writeInt)
            list(gradientPositions, MAX_STYLE_VALUES, ::number)
            number(gradientAngle); boolean(flat); boolean(monochrome); boolean(inverse); boolean(lighten)
        }

        private fun strokes(values: List<EditorBrushStroke>) = list(values, MAX_STROKES) { stroke ->
            number(stroke.brush); enumeration(stroke.action)
            require(stroke.points.size <= MAX_POINTS - pointCount) { "Too many editor brush points" }
            pointCount += stroke.points.size
            list(stroke.points, MAX_POINTS) { point -> number(point.x); number(point.y) }
        }

        fun boolean(value: Boolean) = output.writeByte(if (value) 1 else 0)

        fun number(value: Float) {
            require(value.isFinite()) { "Non-finite editor control" }
            output.writeFloat(value)
        }

        private fun enumeration(value: Enum<*>) = text(value.name, 128)

        private fun text(value: String, limit: Int = MAX_TEXT_BYTES) {
            require(value.length <= limit && Charsets.UTF_8.newEncoder().canEncode(value)) { "Invalid editor control text" }
            val encoded = value.toByteArray(Charsets.UTF_8)
            require(encoded.size <= limit) { "Editor control text is too large" }
            output.writeInt(encoded.size)
            output.write(encoded)
        }

        fun optionalText(value: String?) { boolean(value != null); value?.let { text(it) } }

        private inline fun <T> list(values: List<T>, limit: Int, write: (T) -> Unit) {
            require(values.size <= limit) { "Too many editor control values" }
            output.writeInt(values.size)
            values.forEach(write)
        }
    }

    private class Reader(private val input: DataInputStream) {
        private var pointCount = 0

        fun create(): EditorCreateControls = EditorCreateControls(
            source = enumeration<Source>(), browsingIconPacks = boolean(), imageEdit = enumeration<ImageEdit>(),
            textType = enumeration<TextType>(), customText = text(), textCase = enumeration<TextCase>(),
            fontReference = text(), useVector = boolean(), applicationIconVariant = enumeration<ApplicationIconVariant>(),
            useFullApplicationIcon = boolean(), invertMonochrome = boolean(),
            materialYouScheme = input.readInt().also { require(it >= 0) { "Invalid Material You scheme" } },
            iconColorBits = input.readLong(), backgroundColorBits = input.readLong(),
            materialYouForegroundStyle = style(), materialYouBackgroundStyle = style(),
            iconPack = text(), replayMaterialYou = boolean()
        )

        fun vector(): EditorVectorControls = EditorVectorControls(
            initialized = boolean(), thickness = number(), automaticallyCenter = boolean(),
            viewport = if (boolean()) EditorViewport(number(), number()) else null,
            sourceUrl = optionalText(), containsBakedModifierEdits = boolean()
        )

        fun adjustments(): EditorAdjustmentControls = EditorAdjustmentControls(
            colorizerStyle = style(), shapeStyle = style(), outlineStyle = style(), shadowStyle = style(),
            edgeThreshold = number(), edgeSmoothing = number(), edgeContrast = boolean(),
            colorizeLayers = list(MAX_SEGMENTS) { SegmentLayer(
                targets = list(MAX_STYLE_VALUES, 4, input::readInt), tolerance = number(), style = style()
            ) },
            iconScale = number(), backgroundTargets = list(MAX_STYLE_VALUES, 4, input::readInt),
            backgroundTolerance = number(), autoCenter = boolean(), offsetX = number(), offsetY = number(),
            shape = enumeration<IconShape>(), shapeCrop = boolean(), shapeScale = number(),
            outlineMode = enumeration<OutlineMode>(), outlineWidth = number(), shadowEnabled = boolean(),
            shadowBlur = number(), shadowDistance = number(), shadowAngle = number(), shadowAllDirections = boolean(),
            shadowOpacity = number(), eraseStrokes = strokes(), backgroundBrushStrokes = strokes()
        )

        fun style(): ColorizerStyle = ColorizerStyle(
            mode = enumeration<ColorizerMode>(), gradientType = enumeration<GradientType>(), firstColor = input.readInt(),
            gradientStops = list(MAX_STYLE_VALUES, 4, input::readInt), gradientPositions = list(MAX_STYLE_VALUES, 4, ::number),
            gradientAngle = number(), flat = boolean(), monochrome = boolean(), inverse = boolean(), lighten = boolean()
        )

        private fun strokes(): List<EditorBrushStroke> = list(MAX_STROKES) {
            val brush = number()
            val action = enumeration<BrushAction>()
            val points = list(MAX_POINTS - pointCount, 8) { EditorBrushPoint(number(), number()) }
            pointCount += points.size
            EditorBrushStroke(brush, points, action)
        }

        fun boolean(): Boolean = when (input.readUnsignedByte()) {
            0 -> false
            1 -> true
            else -> throw IllegalArgumentException("Invalid editor control boolean")
        }

        fun number(): Float = input.readFloat().also { require(it.isFinite()) { "Non-finite editor control" } }

        private inline fun <reified T : Enum<T>> enumeration(): T {
            val name = text(128)
            return requireNotNull(enumValues<T>().firstOrNull { it.name == name }) { "Unknown editor control enum" }
        }

        private fun text(limit: Int = MAX_TEXT_BYTES): String {
            val count = input.readInt()
            require(count in 0..limit && count <= input.available()) { "Invalid editor control text size" }
            val encoded = ByteArray(count)
            input.readFully(encoded)
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(encoded)).toString()
        }

        fun optionalText(): String? = if (boolean()) text() else null

        private inline fun <T> list(limit: Int, minimumBytes: Int = 1, read: () -> T): List<T> {
            val count = input.readInt()
            require(count in 0..limit && count <= input.available() / minimumBytes) { "Invalid editor control list size" }
            return List(count) { read() }
        }
    }

    private class BoundedOutput : OutputStream() {
        private val bytes = ByteArrayOutputStream()

        override fun write(value: Int) {
            require(bytes.size().toLong() < EditorDraftCodec.MAX_CONTROLS_BYTES) { "Editor controls are too large" }
            bytes.write(value)
        }

        override fun write(value: ByteArray, offset: Int, length: Int) {
            require(bytes.size().toLong() + length <= EditorDraftCodec.MAX_CONTROLS_BYTES) { "Editor controls are too large" }
            bytes.write(value, offset, length)
        }

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }
}
