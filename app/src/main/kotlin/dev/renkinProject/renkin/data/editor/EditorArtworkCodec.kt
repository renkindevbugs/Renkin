package dev.renkinProject.renkin.data.editor

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Versioned exact artwork values; live state capture and native binding are separate boundaries. */
/**
 * Version 2 adds optional gradients to brushes. Documents without a gradient are still written as
 * version 1, so ordinary drafts stay byte-identical and readable by older builds; both versions decode.
 */
internal object EditorArtworkCodec {
    const val VERSION = 2
    private const val VERSION_WITHOUT_GRADIENTS = 1
    private const val ARTWORK_MAGIC = 0x524b4541 // RKEA
    private const val VECTOR_STATE_MAGIC = 0x524b4556 // RKEV

    suspend fun encode(value: EditorArtworkSnapshot): ByteArray = write(ARTWORK_MAGIC, value.hasGradient()) { artwork(value) }
    suspend fun decode(bytes: ByteArray): EditorArtworkSnapshot = read(bytes, ARTWORK_MAGIC) { artwork() }

    suspend fun encodeVectorState(value: EditorVectorWorkingState): ByteArray = write(VECTOR_STATE_MAGIC, value.hasGradient()) {
        require(!value.initialized || value.seed != null) { "Initialized editor vector has no seed" }
        boolean(value.initialized); inputs(value.current); boolean(value.seed != null); value.seed?.let { inputs(it) }
    }
    suspend fun decodeVectorState(bytes: ByteArray): EditorVectorWorkingState = read(bytes, VECTOR_STATE_MAGIC) {
        EditorVectorWorkingState(boolean(), inputs(), if (boolean()) inputs() else null).also {
            require(!it.initialized || it.seed != null) { "Initialized editor vector has no seed" }
        }
    }

    private suspend fun write(magic: Int, gradients: Boolean, body: EditorArtworkWriter.() -> Unit): ByteArray = withContext(Dispatchers.Default) {
        val owner = currentCoroutineContext()
        EditorArtworkWriter(gradients) { owner.ensureActive() }.run {
            output.writeInt(magic); output.writeInt(if (gradients) VERSION else VERSION_WITHOUT_GRADIENTS)
            body(); owner.ensureActive(); result()
        }
    }
    private suspend fun <T> read(bytes: ByteArray, magic: Int, body: EditorArtworkReader.() -> T): T = withContext(Dispatchers.Default) {
        val owner = currentCoroutineContext()
        try {
            val version = bytes.formatVersion()
            require(version == VERSION_WITHOUT_GRADIENTS || version == VERSION) { "Unsupported editor artwork format" }
            EditorArtworkReader(bytes, gradients = version == VERSION) { owner.ensureActive() }.run {
                require(input.readInt() == magic && input.readInt() == version) { "Unsupported editor artwork format" }
                body().also { finished(); owner.ensureActive() }
            }
        } catch (error: IOException) { throw IllegalArgumentException("Invalid editor artwork document", error) }
    }

    private fun EditorArtworkWriter.artwork(value: EditorArtworkSnapshot, depth: Int = 0) {
        budget.node(depth)
        when (value) {
            is EditorBitmapArtwork -> {
                validateBitmap(value)
                output.writeByte(1); output.writeInt(value.width); output.writeInt(value.height); output.writeInt(value.rowBytes); output.writeInt(value.density)
                boolean(value.hasAlpha); boolean(value.premultiplied); boolean(value.adaptive); number(value.previewScale); blob(value.pixels)
            }
            is EditorVectorArtwork -> {
                validateVector(value)
                output.writeByte(2); text(value.name); number(value.width); number(value.height); number(value.viewportWidth); number(value.viewportHeight)
                output.writeLong(value.tintBits); text(value.tintBlendMode); boolean(value.autoMirror); boolean(value.scaleStrokesWithBounds)
                vectorNode(value.root, depth + 1)
            }
            is EditorAdaptiveArtwork -> {
                output.writeByte(3); blob(value.foreground); blob(value.background); optionalBlob(value.monochrome)
                boolean(value.controls != null)
                value.controls?.let { blob(EditorArtworkBytes(EditorControlsCodec.encodePack(it))) }
                optionalBlob(value.originalForeground); optionalBlob(value.originalBackground)
            }
            is EditorInsetArtwork -> {
                output.writeByte(4); artwork(value.child, depth + 1)
                listOf(value.left, value.top, value.right, value.bottom).forEach(output::writeInt)
                listOf(value.fractionLeft, value.fractionTop, value.fractionRight, value.fractionBottom).forEach(::number)
            }
            is EditorThemedArtwork -> {
                output.writeByte(5); artwork(value.glyph, depth + 1)
            }
        }
    }

    private fun EditorArtworkReader.artwork(depth: Int = 0): EditorArtworkSnapshot {
        budget.node(depth)
        return when (input.readUnsignedByte()) {
            1 -> EditorBitmapArtwork(input.readInt(), input.readInt(), input.readInt(), input.readInt(),
                boolean(), boolean(), boolean(), number(), blob()).also(::validateBitmap)
            2 -> {
                val name = text(); val dimensions = List(4) { number() }; val tint = input.readLong(); val blend = text()
                val mirror = boolean(); val strokes = boolean()
                val root = vectorNode(depth + 1) as? EditorVectorGroup ?: throw IllegalArgumentException("Invalid vector root")
                EditorVectorArtwork(name, dimensions[0], dimensions[1], dimensions[2], dimensions[3], tint, blend, mirror, strokes, root).also(::validateVector)
            }
            3 -> EditorAdaptiveArtwork(blob(), blob(), optionalBlob(),
                if (boolean()) EditorControlsCodec.decodePack(blob().copyBytes()) else null, optionalBlob(), optionalBlob())
            4 -> EditorInsetArtwork(artwork(depth + 1), input.readInt(), input.readInt(), input.readInt(), input.readInt(), number(), number(), number(), number())
            5 -> EditorThemedArtwork(artwork(depth + 1))
            else -> throw IllegalArgumentException("Unknown editor artwork kind")
        }
    }

    private fun EditorArtworkWriter.inputs(value: EditorVectorInputs) {
        require(value.entries.size <= EditorArtworkBudget.MAX_NODES)
        output.writeInt(value.entries.size)
        value.entries.forEach { vectorNode(it.path); boolean(it.filled); number(it.baseStroke) }
        number(value.thickness); boolean(value.autoCenter); boolean(value.viewport != null)
        value.viewport?.let { number(it.width); number(it.height) }
        optionalText(value.sourceUrl); boolean(value.containsBakedModifierEdits)
    }
    private fun EditorArtworkReader.inputs(): EditorVectorInputs {
        val entries = List(count(EditorArtworkBudget.MAX_NODES)) {
            val path = vectorNode() as? EditorVectorPath ?: throw IllegalArgumentException("Invalid editable vector entry")
            EditorVectorEntry(path, boolean(), number())
        }
        return EditorVectorInputs(entries, number(), boolean(), if (boolean()) EditorViewport(number(), number()) else null, optionalText(), boolean())
    }

    private fun ByteArray.formatVersion(): Int =
        if (size < 8) -1 else (this[4].toInt() and 0xff shl 24) or (this[5].toInt() and 0xff shl 16) or
            (this[6].toInt() and 0xff shl 8) or (this[7].toInt() and 0xff)

    private fun EditorArtworkSnapshot.hasGradient(): Boolean = when (this) {
        is EditorVectorArtwork -> root.hasGradient()
        is EditorInsetArtwork -> child.hasGradient()
        is EditorBitmapArtwork, is EditorAdaptiveArtwork, is EditorThemedArtwork -> false
    }

    private fun EditorVectorWorkingState.hasGradient(): Boolean =
        (listOf(current) + listOfNotNull(seed)).any { inputs -> inputs.entries.any { it.path.hasGradient() } }

    internal fun validateBitmap(value: EditorBitmapArtwork) {
        require(value.width in 1..4096 && value.height in 1..4096 && value.density >= 0 &&
            value.rowBytes >= value.width * 4 && value.rowBytes.toLong() * value.height == value.pixels.size.toLong() &&
            value.previewScale.isFinite() && value.previewScale in 0.1f..10f) { "Invalid editor bitmap layout" }
    }
    private fun validateVector(value: EditorVectorArtwork) {
        require(listOf(value.width, value.height, value.viewportWidth, value.viewportHeight).all { it.isFinite() && it > 0f }) {
            "Invalid editor vector dimensions"
        }
    }
}
