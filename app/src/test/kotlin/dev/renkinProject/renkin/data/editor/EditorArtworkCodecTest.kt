package dev.renkinProject.renkin.data.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.drawable.*
import dev.renkinProject.renkin.extension.getBytes
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.ui.*
import dev.renkinProject.renkin.vector.brush.ReferenceBrush
import dev.renkinProject.renkin.vector.brush.SolidColorShader
import java.nio.ByteBuffer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorArtworkCodecTest {
    private val commands = listOf(
        PathNode.Close, PathNode.MoveTo(-0f, 2f), PathNode.RelativeMoveTo(3f, -4f),
        PathNode.LineTo(5f, 6f), PathNode.RelativeLineTo(-7f, 8f),
        PathNode.HorizontalTo(9f), PathNode.RelativeHorizontalTo(-10f),
        PathNode.VerticalTo(11f), PathNode.RelativeVerticalTo(-12f),
        PathNode.CurveTo(13f, 14f, 15f, 16f, 17f, 18f),
        PathNode.RelativeCurveTo(-19f, 20f, -21f, 22f, -23f, 24f),
        PathNode.ReflectiveCurveTo(25f, 26f, 27f, 28f),
        PathNode.RelativeReflectiveCurveTo(-29f, 30f, -31f, 32f),
        PathNode.QuadTo(33f, 34f, 35f, 36f), PathNode.RelativeQuadTo(-37f, 38f, -39f, 40f),
        PathNode.ReflectiveQuadTo(41f, 42f), PathNode.RelativeReflectiveQuadTo(-43f, 44f),
        PathNode.ArcTo(45f, 46f, 47f, true, false, 48f, 49f),
        PathNode.RelativeArcTo(50f, 51f, 52f, false, true, -53f, 54f)
    )
    private val packedColor = Color(0.21f, 0.43f, 0.65f, 0.87f, ColorSpaces.DisplayP3).value.toLong()
    private fun path() = EditorVectorPath("path-ž😀", commands, true,
        EditorVectorBrush(packedColor), 0.31f, EditorVectorBrush(Color.Blue.value.toLong(), "@color/source"),
        0.73f, 2.4f, "Square", "Bevel", 7.5f, 0.13f, 0.79f, -0.25f)
    private fun group(children: List<EditorVectorNode> = listOf(path())) = EditorVectorGroup(
        "root", 17f, 2f, 3f, 0.8f, 1.2f, -4f, 5f,
        listOf(PathNode.MoveTo(1f, 2f), PathNode.LineTo(3f, 4f), PathNode.Close), children)
    private fun vector(root: EditorVectorGroup = group()) = EditorVectorArtwork(
        "vector-ž😀", 35f, 47f, 61f, 73f, packedColor, "SrcAtop", true, true, root)
    private fun inputs(entries: List<EditorVectorEntry> = listOf(EditorVectorEntry(path(), false, 2.7f))) =
        EditorVectorInputs(entries, -0f, false, EditorViewport(31f, 43f), "https://example.test/icon.svg", true)
    private fun style(color: Int) = ColorizerStyle(mode = ColorizerMode.GRADIENT, firstColor = color,
        gradientStops = listOf(color + 1, color + 2), gradientPositions = listOf(0.17f, 0.83f),
        flat = true, monochrome = true, inverse = true, lighten = true)
    private fun bitmap() = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).apply {
        density = 213
        copyPixelsFromBuffer(ByteBuffer.wrap(ByteArray(byteCount) { (it % 8 * 13).toByte() }))
    }
    private fun png(size: Int = 750, color: Int = android.graphics.Color.RED): ByteArray =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).let {
            try { it.eraseColor(color); it.getBytes(Bitmap.CompressFormat.PNG, 100) } finally { it.recycle() }
        }
    private fun rejected(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java, block)
    private fun ByteArray.intAt(offset: Int, value: Int) = copyOf().also { ByteBuffer.wrap(it).putInt(offset, value) }
    private suspend fun assertArtwork(value: EditorArtworkSnapshot) {
        val encoded = EditorArtworkCodec.encode(value)
        assertArrayEquals(encoded, EditorArtworkCodec.encode(EditorArtworkCodec.decode(encoded)))
        assertArrayEquals(encoded, EditorArtworkCodec.encode(captureEditorArtwork(restoreEditorArtwork(value))))
    }

    // Stored drafts outlive builds: any byte change here needs a codec version bump, not a new hash.
    @Test fun encodedArtworkKeepsItsStoredFormat() = runBlocking<Unit> {
        val value = vector(group(listOf(group().copy(name = "nested", rotation = -21f), path())))
        val bytes = EditorArtworkCodec.encode(value)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(STORED_FORMAT_SHA256, hash)
    }

    @Test fun everyPathCommandAndVectorPropertySurvivesCodecAndNativeBinding() = runBlocking<Unit> {
        assertEquals(19, commands.map { it.javaClass }.distinct().size)
        val value = vector(group(listOf(group().copy(name = "nested", rotation = -21f), path())))
        assertEquals(value, EditorArtworkCodec.decode(EditorArtworkCodec.encode(value)))
        assertArtwork(value)
        val nativePath = path().toDraftVectorPath()
        assertEquals(commands, nativePath.pathData)
        assertEquals(PathFillType.EvenOdd, nativePath.pathFillType)
        assertEquals(7.5f, nativePath.strokeLineMiter, 0f)
        assertEquals(0.13f, nativePath.trimPathStart, 0f)
        assertEquals(0.79f, nativePath.trimPathEnd, 0f)
        assertEquals(-0.25f, nativePath.trimPathOffset, 0f)
        assertEquals(packedColor.toULong(), (nativePath.fill as SolidColor).value.value)
        val reference = nativePath.stroke as ReferenceBrush
        assertEquals("@color/source", reference.reference)
        assertEquals(Color.Blue, (reference.shaderBrush as SolidColorShader).color)
    }

    @Test fun namedStrokeAndTintModesAndNullableBrushesRemainDistinct() = runBlocking<Unit> {
        val modes = listOf(BlendMode.Clear, BlendMode.Src, BlendMode.Dst, BlendMode.SrcOver, BlendMode.DstOver,
            BlendMode.SrcIn, BlendMode.DstIn, BlendMode.SrcOut, BlendMode.DstOut, BlendMode.SrcAtop, BlendMode.DstAtop,
            BlendMode.Xor, BlendMode.Plus, BlendMode.Modulate, BlendMode.Screen, BlendMode.Overlay, BlendMode.Darken,
            BlendMode.Lighten, BlendMode.ColorDodge, BlendMode.ColorBurn, BlendMode.Hardlight, BlendMode.Softlight,
            BlendMode.Difference, BlendMode.Exclusion, BlendMode.Multiply, BlendMode.Hue, BlendMode.Saturation,
            BlendMode.Color, BlendMode.Luminosity)
        for ((index, mode) in modes.withIndex()) {
            val entry = path().copy(fill = null, stroke = EditorVectorBrush(Color.Transparent.value.toLong(), ""),
                evenOdd = false, cap = listOf("Butt", "Round", "Square")[index % 3],
                join = listOf("Miter", "Round", "Bevel")[index % 3])
            assertArtwork(vector(group(listOf(entry))).copy(tintBlendMode = mode.toString(), tintBits = Color.Unspecified.value.toLong()))
        }
    }

    @Test fun capturePreservesDirectRootPropertiesAndDetachesMutableTrees() = runBlocking<Unit> {
        val source = ImageVectorDrawable(ImageVector.Builder("original", 24.dp, 24.dp, 24f, 24f)
            .addPath(listOf(PathNode.MoveTo(1f, 2f)), fill = SolidColor(Color.Red)).build(), true)
        source.root.apply { name = "direct-root"; rotation = 23f; clipPathData.addAll(commands) }
        val child = source.root.children.single() as MutableVectorPath
        child.apply { trimPathStart = 0.25f; trimPathEnd = 0.75f; strokeLineMiter = 9f }
        val captured = captureEditorArtwork(source) as EditorVectorArtwork
        val bytes = EditorArtworkCodec.encode(captured)
        assertEquals("direct-root", captured.root.name)
        assertEquals(23f, captured.root.rotation, 0f)
        assertEquals(commands, captured.root.clip)
        assertEquals(9f, (captured.root.children.single() as EditorVectorPath).miter, 0f)
        source.name = "changed"; source.root.clipPathData.clear(); child.pathData.clear(); source.root.children.clear()
        assertArrayEquals(bytes, EditorArtworkCodec.encode(captured))
    }

    @Test fun vectorWorkingStateKeepsOriginalSeedAndLaterEditsIndependent() = runBlocking<Unit> {
        val initialPath = path().toDraftVectorPath()
        val entries = mutableListOf(PathEntry(initialPath, false, 1.3f))
        val state = VectorEditState().apply {
            this.entries = entries; thickness = 0.8f; viewportOverride = 11f to 13f
            sourceUrl = "seed"; containsBakedModifierEdits = true; captureSeed(); initialized = true
            this.entries = listOf(PathEntry(initialPath, true, 2.9f)); thickness = 1.7f
            automaticallyCenter = false; viewportOverride = 17f to 19f; sourceUrl = "edited"
        }
        val captured = state.captureArtworkState()
        val encoded = EditorArtworkCodec.encodeVectorState(captured)
        assertEquals(captured, EditorArtworkCodec.decodeVectorState(encoded))
        assertTrue(captured.initialized)
        assertEquals("seed", captured.seed!!.sourceUrl)
        assertEquals("edited", captured.current.sourceUrl)
        assertFalse(captured.seed.entries.single().filled)
        assertTrue(captured.current.entries.single().filled)
        assertEquals(1.3f, captured.seed.entries.single().baseStroke, 0f)
        assertEquals(2.9f, captured.current.entries.single().baseStroke, 0f)
        assertTrue(captured.current.containsBakedModifierEdits)
        assertTrue(captured.seed.containsBakedModifierEdits)
        entries.clear(); state.reset()
        assertArrayEquals(encoded, EditorArtworkCodec.encodeVectorState(captured))
    }

    @Test fun initializedVectorRequiresSeedOnCaptureWriteAndRead() = runBlocking<Unit> {
        rejected { VectorEditState().apply { initialized = true }.captureArtworkState() }
        rejected { runBlocking { EditorArtworkCodec.encodeVectorState(EditorVectorWorkingState(true, inputs(), null)) } }
        val empty = EditorVectorWorkingState(false, inputs(), null)
        val encoded = EditorArtworkCodec.encodeVectorState(empty)
        assertEquals(empty, EditorArtworkCodec.decodeVectorState(encoded))
        rejected { runBlocking { EditorArtworkCodec.decodeVectorState(encoded.copyOf().apply { this[8] = 1 }) } }
    }

    @Test fun vectorStatePreservesSignedZeroNullAndEmptyAttribution() = runBlocking<Unit> {
        for (url in listOf(null, "")) {
            val value = EditorVectorWorkingState(true, inputs().copy(sourceUrl = url, viewport = null), inputs())
            val decoded = EditorArtworkCodec.decodeVectorState(EditorArtworkCodec.encodeVectorState(value))
            assertEquals(value, decoded)
            assertEquals((-0f).toRawBits(), decoded.current.thickness.toRawBits())
        }
    }

    @Test fun rawBitmapPixelsFlagsDensityAndPreviewMetadataRoundTripWithoutAliasing() = runBlocking<Unit> {
        for (premultiplied in listOf(false, true)) {
            val bitmap = bitmap().apply { setPremultiplied(premultiplied) }
            val source = BitmapIconDrawable(bitmap, true, 1.5f)
            val captured = captureEditorArtwork(source) as EditorBitmapArtwork
            val expectedPixels = ByteBuffer.allocate(bitmap.byteCount).also(bitmap::copyPixelsToBuffer).array()
            assertArrayEquals(expectedPixels, captured.pixels.copyBytes())
            assertEquals(premultiplied, captured.premultiplied)
            assertEquals(213, captured.density)
            assertTrue(captured.adaptive)
            assertEquals(1.5f, captured.previewScale, 0f)
            captured.pixels.copyBytes().fill(0)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            assertArrayEquals(expectedPixels, captured.pixels.copyBytes())
            assertArtwork(captured)
        }
        val opaque = captureEditorArtwork(BitmapIconDrawable(bitmap().apply { setHasAlpha(false) }))
        assertArtwork(opaque)
    }

    @Test fun bitmapLayoutAndBoundsAreRejectedRatherThanReinterpreted() = runBlocking<Unit> {
        val valid = captureEditorArtwork(BitmapIconDrawable(bitmap())) as EditorBitmapArtwork
        for (value in listOf(valid.copy(width = 0), valid.copy(height = 4097), valid.copy(rowBytes = 1),
            valid.copy(density = -1), valid.copy(previewScale = Float.NaN), valid.copy(previewScale = 11f),
            valid.copy(pixels = EditorArtworkBytes(byteArrayOf(1))))) {
            rejected { runBlocking { EditorArtworkCodec.encode(value) } }
        }
        val incompatible = valid.copy(rowBytes = valid.rowBytes + 4,
            pixels = EditorArtworkBytes(ByteArray((valid.rowBytes + 4) * valid.height)))
        rejected { runBlocking { restoreEditorArtwork(incompatible) } }
    }

    @Test fun unsupportedBitmapColorSpaceConfigurationDimensionsAndPixelBudgetFailCapture() {
        val values = listOf(
            Bitmap.createBitmap(2, 2, Bitmap.Config.RGB_565),
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888, true, ColorSpace.get(ColorSpace.Named.DISPLAY_P3)),
            Bitmap.createBitmap(4097, 1, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(3000, 3000, Bitmap.Config.ARGB_8888)
        )
        try { values.forEach { rejected { captureEditorArtwork(BitmapIconDrawable(it)) } } }
        finally { values.forEach(Bitmap::recycle) }
    }

    @Test fun unsupportedGradientAndInsetChildFailCapture() {
        val source = ImageVectorDrawable(ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f).addPath(emptyList(), fill = Brush.linearGradient(listOf(Color.Red, Color.Blue))).build())
        rejected { captureEditorArtwork(source) }
        rejected { captureEditorArtwork(InsetIconDrawable(ColorDrawable(0), Rect(), RectF())) }
    }

    @Test fun adaptiveLayersOriginalsAndPackControlsAreExactAndDetached() = runBlocking<Unit> {
        val foreground = png(); val background = png(color = android.graphics.Color.BLUE)
        val original = png(color = android.graphics.Color.GREEN)
        val stops = mutableListOf(11, 22)
        val edit = MaterialYouPackEditState(-1, style(77).copy(gradientStops = stops), style(88), 1.3f)
        val source = AdaptiveIconPackDrawable(foreground, background, original, edit, original, foreground)
        val captured = captureEditorArtwork(source) as EditorAdaptiveArtwork
        val encoded = EditorArtworkCodec.encode(captured)
        assertEquals(-1, captured.controls!!.selectedScheme)
        assertEquals(edit.customBackground, captured.controls.customBackground)
        assertArrayEquals(original, captured.originalForeground!!.copyBytes())
        assertArrayEquals(foreground, captured.originalBackground!!.copyBytes())
        stops.clear(); foreground.fill(0); background.fill(0); original.fill(0)
        assertArrayEquals(encoded, EditorArtworkCodec.encode(captured))
        assertArtwork(captured)
        val restored = restoreEditorArtwork(captured) as AdaptiveIconPackDrawable
        assertEquals(android.graphics.Color.RED, restored.foreground.getPixel(12, 12))
        assertEquals(android.graphics.Color.GREEN, restored.monochrome!!.getPixel(12, 12))
    }

    @Test fun adaptiveBindingRejectsInvalidPngDimensionsPixelsAndControls() = runBlocking<Unit> {
        val valid = EditorArtworkBytes(png())
        val base = EditorAdaptiveArtwork(valid, valid, null, null, null, null)
        assertArtwork(base)
        for (bad in listOf(EditorArtworkBytes(byteArrayOf(1, 2)), EditorArtworkBytes(png(749)),
            EditorArtworkBytes(valid.copyBytes().copyOf(40)))) {
            rejected { runBlocking { restoreEditorArtwork(base.copy(foreground = bad)) } }
        }
        rejected { runBlocking { restoreEditorArtwork(base.copy(controls = EditorPackControls(-2, style(1), style(2), 1f))) } }
        rejected { runBlocking { restoreEditorArtwork(base.copy(controls = EditorPackControls(999, style(1), style(2), 3f))) } }
        assertArtwork(base.copy(controls = EditorPackControls(999, style(1), style(2), 1f)))
    }

    @Test fun nestedInsetPreservesAbsoluteFractionalAndChildMetadata() = runBlocking<Unit> {
        val source = InsetIconDrawable(InsetIconDrawable(BitmapIconDrawable(bitmap(), true, 1.5f),
            Rect(1, 2, 3, 4), RectF(-1f, 0.2f, -1f, 0.4f)), Rect(5, 6, 7, 8), RectF(-1f, -1f, -1f, -1f))
        val captured = captureEditorArtwork(source) as EditorInsetArtwork
        assertEquals(5, captured.left); assertEquals(8, captured.bottom)
        assertEquals(0.2f, (captured.child as EditorInsetArtwork).fractionTop, 0f)
        assertArtwork(captured)
        source.dimensions.left = 99; source.fractions.top = 0.99f
        assertEquals(5, captured.left); assertEquals(-1f, captured.fractionTop, 0f)
    }

    @Test fun everyTruncatedPrefixAndTrailingBytesAreRejected() = runBlocking<Unit> {
        val artwork = EditorArtworkCodec.encode(vector())
        val state = EditorArtworkCodec.encodeVectorState(EditorVectorWorkingState(true, inputs(), inputs()))
        for (size in artwork.indices) rejected { runBlocking { EditorArtworkCodec.decode(artwork.copyOf(size)) } }
        for (size in state.indices) rejected { runBlocking { EditorArtworkCodec.decodeVectorState(state.copyOf(size)) } }
        rejected { runBlocking { EditorArtworkCodec.decode(artwork + 0) } }
        rejected { runBlocking { EditorArtworkCodec.decodeVectorState(state + 0) } }
        rejected { runBlocking { EditorControlsCodec.decodePack(EditorControlsCodec.encodePack(EditorPackControls(-1, style(1), style(2), 1f)) + 0) } }
    }

    @Test fun malformedFramingBooleansUtf8AndCountsFailBeforeLargeAllocation() = runBlocking<Unit> {
        val artwork = EditorArtworkCodec.encode(vector())
        for (bytes in listOf(artwork.intAt(0, 0), artwork.intAt(4, 99), artwork.copyOf().apply { this[8] = 99 },
            artwork.intAt(9, Int.MAX_VALUE), artwork.intAt(9, -1), artwork.copyOf().apply { this[13] = 0xff.toByte() })) {
            rejected { runBlocking { EditorArtworkCodec.decode(bytes) } }
        }
        val bitmap = EditorArtworkCodec.encode(captureEditorArtwork(BitmapIconDrawable(bitmap())))
        for (bytes in listOf(bitmap.copyOf().apply { this[25] = 2 }, bitmap.intAt(32, Int.MAX_VALUE),
            bitmap.intAt(32, -1), bitmap.intAt(28, Float.POSITIVE_INFINITY.toRawBits()))) {
            rejected { runBlocking { EditorArtworkCodec.decode(bytes) } }
        }
        rejected { runBlocking { EditorArtworkCodec.encode(vector().copy(name = "\uD800")) } }
        rejected { runBlocking { EditorArtworkCodec.encode(vector().copy(width = Float.NaN)) } }
        rejected { runBlocking { EditorArtworkCodec.encode(vector(group(listOf(path().copy(trimEnd = Float.NEGATIVE_INFINITY))))) } }
        rejected { EditorArtworkReader(byteArrayOf(99)).vectorNode() }
        rejected { EditorArtworkReader(byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0, 1, '!'.code.toByte())).vectorNode() }
    }

    @Test fun unknownNativeStrokeAndTintModesFailBeforePublication() = runBlocking<Unit> {
        for (value in listOf(vector().copy(tintBlendMode = "future-mode"),
            vector(group(listOf(path().copy(cap = "future-cap")))), vector(group(listOf(path().copy(join = "future-join")))))) {
            rejected { runBlocking { restoreEditorArtwork(value) } }
        }
    }

    @Test fun aggregateLimitsCoverWholeTreeAndBothCurrentAndSeed() = runBlocking<Unit> {
        var root = group(emptyList())
        repeat(EditorArtworkBudget.MAX_DEPTH + 1) { root = group(listOf(root)) }
        rejected { runBlocking { EditorArtworkCodec.encode(vector(root)) } }
        rejected { runBlocking { EditorArtworkCodec.encode(vector(group(List(EditorArtworkBudget.MAX_NODES - 1) { path().copy(commands = emptyList()) }))) } }
        val half = path().copy(commands = List(EditorArtworkBudget.MAX_COMMANDS / 2 + 1) { PathNode.Close })
        val input = inputs(listOf(EditorVectorEntry(half, true, 1f)))
        rejected { runBlocking { EditorArtworkCodec.encodeVectorState(EditorVectorWorkingState(true, input, input)) } }
        val budget = EditorArtworkBudget()
        budget.bytes(EditorDraftCodec.MAX_ARTIFACT_BYTES.toInt())
        rejected { budget.bytes(1) }
        rejected { EditorArtworkBudget().bytes(-1) }
        rejected { runBlocking { EditorArtworkCodec.encode(vector().copy(name = "x".repeat(EditorControlsCodec.MAX_TEXT_BYTES + 1))) } }
    }

    @Test fun closeOnlyCommandsRemainCancellableDuringWritingAndReading() {
        val entry = path().copy(commands = List(100) { PathNode.Close })
        var checks = 0
        assertThrows(CancellationException::class.java) {
            EditorArtworkWriter { if (++checks == 10) throw CancellationException("cancel-write") }.vectorNode(entry)
        }
        val writer = EditorArtworkWriter().apply { vectorNode(entry) }
        checks = 0
        assertThrows(CancellationException::class.java) {
            EditorArtworkReader(writer.result()) { if (++checks == 10) throw CancellationException("cancel-read") }.vectorNode()
        }
    }

    @Test fun cancelledOwnerCannotEncodeDecodeOrBindArtwork() = runBlocking<Unit> {
        val value = vector(); val encoded = EditorArtworkCodec.encode(value)
        for (operation in listOf<suspend () -> Unit>(
            { EditorArtworkCodec.encode(value) }, { EditorArtworkCodec.decode(encoded) }, { restoreEditorArtwork(value) })) {
            assertThrows(CancellationException::class.java) {
                runBlocking { withContext(Job().apply { cancel() }) { operation() } }
            }
        }
    }
}

private const val STORED_FORMAT_SHA256 = "f1911e5ff89e50ae96afa865ab5678d9ca508b3bf99fe53c6fcd73e09f8ed9a0"
