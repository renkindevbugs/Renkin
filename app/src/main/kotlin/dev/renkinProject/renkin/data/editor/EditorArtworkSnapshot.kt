package dev.renkinProject.renkin.data.editor

import androidx.compose.ui.graphics.vector.PathNode

/** Value trees only; decoding/binding never follows resource or filesystem references. */
internal sealed interface EditorArtworkSnapshot

internal class EditorArtworkBytes(bytes: ByteArray) {
    init { require(bytes.size.toLong() <= EditorDraftCodec.MAX_ARTIFACT_BYTES) { "Editor artwork is too large" } }
    private val content = bytes.copyOf()
    val size: Int get() = content.size
    fun copyBytes(): ByteArray = content.copyOf()
}

internal data class EditorBitmapArtwork(
    val width: Int, val height: Int, val rowBytes: Int, val density: Int,
    val hasAlpha: Boolean, val premultiplied: Boolean, val adaptive: Boolean,
    val previewScale: Float, val pixels: EditorArtworkBytes
) : EditorArtworkSnapshot

internal data class EditorVectorArtwork(
    val name: String, val width: Float, val height: Float, val viewportWidth: Float, val viewportHeight: Float,
    val tintBits: Long, val tintBlendMode: String, val autoMirror: Boolean,
    val scaleStrokesWithBounds: Boolean, val root: EditorVectorGroup
) : EditorArtworkSnapshot

internal sealed interface EditorVectorNode
internal data class EditorVectorGroup(
    val name: String, val rotation: Float, val pivotX: Float, val pivotY: Float,
    val scaleX: Float, val scaleY: Float, val translationX: Float, val translationY: Float,
    val clip: List<PathNode>, val children: List<EditorVectorNode>
) : EditorVectorNode

internal data class EditorVectorPath(
    val name: String, val commands: List<PathNode>, val evenOdd: Boolean,
    val fill: EditorVectorBrush?, val fillAlpha: Float, val stroke: EditorVectorBrush?, val strokeAlpha: Float,
    val strokeWidth: Float, val cap: String, val join: String, val miter: Float,
    val trimStart: Float, val trimEnd: Float, val trimOffset: Float
) : EditorVectorNode

internal data class EditorVectorBrush(val colorBits: Long, val reference: String? = null)

internal data class EditorAdaptiveArtwork(
    val foreground: EditorArtworkBytes, val background: EditorArtworkBytes, val monochrome: EditorArtworkBytes?,
    val controls: EditorPackControls?, val originalForeground: EditorArtworkBytes?, val originalBackground: EditorArtworkBytes?
) : EditorArtworkSnapshot

internal data class EditorInsetArtwork(
    val child: EditorArtworkSnapshot, val left: Int, val top: Int, val right: Int, val bottom: Int,
    val fractionLeft: Float, val fractionTop: Float, val fractionRight: Float, val fractionBottom: Float
) : EditorArtworkSnapshot

internal data class EditorVectorEntry(val path: EditorVectorPath, val filled: Boolean, val baseStroke: Float)
internal data class EditorVectorInputs(
    val entries: List<EditorVectorEntry>, val thickness: Float, val autoCenter: Boolean,
    val viewport: EditorViewport?, val sourceUrl: String?, val containsBakedModifierEdits: Boolean
)
internal data class EditorVectorWorkingState(val initialized: Boolean, val current: EditorVectorInputs, val seed: EditorVectorInputs?)

internal class EditorArtworkBudget(private val checkActive: () -> Unit = {}) {
    private var nodes = 0
    private var commands = 0
    private var bytes = 0L
    fun node(depth: Int) {
        checkActive()
        require(depth <= MAX_DEPTH && ++nodes <= MAX_NODES) { "Editor artwork exceeds tree limits" }
    }
    fun commands(count: Int) {
        require(count in 0..MAX_COMMANDS - commands) { "Editor artwork has too many path commands" }
        commands += count
    }
    fun bytes(count: Int) {
        require(count >= 0 && count.toLong() <= EditorDraftCodec.MAX_ARTIFACT_BYTES - bytes) { "Editor artwork is too large" }
        bytes += count
    }
    companion object {
        const val MAX_DEPTH = 64
        const val MAX_NODES = 10_000
        const val MAX_COMMANDS = 100_000
    }
}
