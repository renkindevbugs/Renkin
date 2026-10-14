package dev.renkinProject.renkin.data.editor

import android.app.Application
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.*
import dev.renkinProject.renkin.icon.creator.*
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.ui.*
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class EditorControlsCodecTest {
    private fun session() = EditorSession(ProfileSessionToken(1, 2, "provider"),
        PackageInfoStruct("App", "app.test", "Main", ColorDrawable(0), 0), "pack", "font")

    private fun style(seed: Int) = ColorizerStyle(
        ColorizerMode.GRADIENT, GradientType.RADIAL, seed,
        listOf(seed + 1, seed + 2), listOf(0.1f, 0.4f, 0.9f), seed + 0.5f,
        flat = true, monochrome = true, inverse = true, lighten = true
    )

    private fun fixture(): EditorControlsSnapshot = session().captureControls()
    private fun roundTrip(value: EditorControlsSnapshot) = EditorControlsCodec.decode(EditorControlsCodec.encode(value))
    private fun rejected(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java, block)
    private fun ByteArray.intAt(offset: Int, value: Int) = copyOf().also { ByteBuffer.wrap(it).putInt(offset, value) }
    private fun ByteArray.find(value: ByteArray): Int = (0..size - value.size).single { offset ->
        value.indices.all { this[offset + it] == value[it] }
    }

    // Stored drafts outlive builds: any byte change here needs a codec version bump, not a new hash.
    @Test fun encodedControlsKeepTheirStoredFormat() {
        val bytes = EditorControlsCodec.encode(fixture())
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(STORED_FORMAT_SHA256, hash)
    }

    @Test fun allControlsCaptureAndRoundTripWithDistinctValues() {
        val editor = session().apply {
            selectedTab = 3; source = Source.entries.last(); browsingIconPacks = true
            imageEdit = ImageEdit.entries.last(); textType = TextType.entries.last()
            customText = "žluť 😀"; textCase = TextCase.entries.last(); textFontPath = "font-reference"
            useVector = true; applicationIconVariant = ApplicationIconVariant.entries.last()
            useFullApplicationIcon = true; invertMonochrome = true; materialYouScheme = 42
            iconColor = Color(0.2f, 0.4f, 0.6f, 0.8f, ColorSpaces.DisplayP3)
            customBgColor = Color(0.7f, 0.5f, 0.3f, 0.9f, ColorSpaces.ExtendedSrgb)
            materialYouForegroundStyle = style(11); materialYouBackgroundStyle = style(22)
            iconPack = "selected-pack"; replayMaterialYou = true
            upload.asAdaptiveIcon = true; upload.zoomLevel = 0.73f
            vectorEditState.apply {
                initialized = true; thickness = 1.23f; automaticallyCenter = false
                viewportOverride = 24.5f to 48.5f; sourceUrl = "vector-url"; containsBakedModifierEdits = true
            }
            adjustments.apply {
                colorizerStyle = style(33); shapeStyle = style(44); outlineStyle = style(55); shadowStyle = style(66)
                edgeThreshold = 2.13f; edgeSmoothing = 3.24f; edgeContrast = true
                colorizeLayers = listOf(SegmentLayer(listOf(7, 8), 0.37f, style(77)))
                iconScale = 0.81f; bgRemovalTargets = listOf(9, 10); bgRemovalTolerance = 0.22f
                autoCenter = true; iconOffsetX = -0.13f; iconOffsetY = 0.14f
                iconShape = IconShape.entries.last(); shapeCrop = false; shapeScale = 0.91f
                outlineMode = OutlineMode.entries.last(); outlineWidth = 7.45f
                shadowEnabled = false; shadowBlur = 3.46f; shadowDistance = 8.47f; shadowAngle = 123.48f
                shadowAllDirections = true; shadowOpacity = 0.49f
                eraseStrokes = listOf(BrushStroke(0.12f, listOf(Offset(0.3f, 0.4f)), BrushAction.ERASE))
                backgroundBrushStrokes = listOf(BrushStroke(0.21f, listOf(Offset(0.6f, 0.7f)), BrushAction.RESTORE))
            }
            materialYouPackAdjustments.apply {
                selectedScheme = -1; customForeground = style(88); customBackground = style(99); strokeScale = 1.37f
            }
            calendarEnabled = false; calendarPrefix = "calendar-prefix"; calendarPackName = "calendar-pack"
            onlineImageUrl = "online-url"
        }
        val expected = EditorControlsSnapshot(3, EditorCreateControls(
            Source.entries.last(), true, ImageEdit.entries.last(), TextType.entries.last(), "žluť 😀",
            TextCase.entries.last(), "font-reference", true, ApplicationIconVariant.entries.last(), true, true, 42,
            editor.iconColor.value.toLong(), editor.customBgColor.value.toLong(), style(11), style(22), "selected-pack", true
        ), EditorUploadControls(true, 0.73f), EditorVectorControls(true, 1.23f, false,
            EditorViewport(24.5f, 48.5f), "vector-url", true), EditorAdjustmentControls(
            style(33), style(44), style(55), style(66), 2.13f, 3.24f, true,
            listOf(SegmentLayer(listOf(7, 8), 0.37f, style(77))), 0.81f, listOf(9, 10), 0.22f, true, -0.13f, 0.14f,
            IconShape.entries.last(), false, 0.91f, OutlineMode.entries.last(), 7.45f, false, 3.46f, 8.47f, 123.48f,
            true, 0.49f, listOf(EditorBrushStroke(0.12f, listOf(EditorBrushPoint(0.3f, 0.4f)), BrushAction.ERASE)),
            listOf(EditorBrushStroke(0.21f, listOf(EditorBrushPoint(0.6f, 0.7f)), BrushAction.RESTORE))
        ), EditorPackControls(-1, style(88), style(99), 1.37f), EditorCalendarControls(false, "calendar-prefix", "calendar-pack"), "online-url")
        assertEquals(expected, editor.captureControls())
        assertEquals(expected, roundTrip(editor.captureControls()))
    }

    @Test fun capturedListsAndGeometryRemainIndependentOfLiveLists() {
        val colors = mutableListOf(1, 2)
        val positions = mutableListOf(0.1f, 0.8f)
        val points = mutableListOf(Offset(0.2f, 0.3f))
        val layers = mutableListOf(SegmentLayer(colors, 0.4f, style(2).copy(gradientStops = colors, gradientPositions = positions)))
        val strokes = mutableListOf(BrushStroke(0.1f, points))
        val editor = session().apply {
            materialYouForegroundStyle = style(3).copy(gradientStops = colors, gradientPositions = positions)
            adjustments.colorizerStyle = materialYouForegroundStyle
            adjustments.colorizeLayers = layers; adjustments.bgRemovalTargets = colors
            adjustments.eraseStrokes = strokes; adjustments.backgroundBrushStrokes = strokes
            materialYouPackAdjustments.customBackground = materialYouForegroundStyle
        }
        val captured = editor.captureControls()
        val before = EditorControlsCodec.encode(captured)
        colors.clear(); positions.clear(); points.clear(); layers.clear(); strokes.clear()
        assertArrayEquals(before, EditorControlsCodec.encode(captured))
        assertEquals(listOf(EditorBrushPoint(0.2f, 0.3f)), captured.adjustments.eraseStrokes.single().points)
        assertTrue(editor.adjustments.eraseStrokes.isEmpty())
    }

    @Test fun nullEmptySignedZeroAndInactiveValuesRemainDistinct() {
        val value = fixture().let { it.copy(
            upload = EditorUploadControls(false, -0.0f),
            vector = it.vector.copy(viewport = EditorViewport(-0.0f, Float.MIN_VALUE), sourceUrl = ""),
            adjustments = it.adjustments.copy(shadowEnabled = false, shadowOpacity = 3.5f, offsetX = -0.0f),
            calendar = EditorCalendarControls(false, "", null), onlineImageUrl = ""
        ) }
        val decoded = roundTrip(value)
        assertEquals(value, decoded)
        assertEquals(Int.MIN_VALUE, decoded.upload.zoomLevel.toRawBits())
        assertEquals(Int.MIN_VALUE, decoded.adjustments.offsetX.toRawBits())
        assertNull(decoded.calendar.packName); assertEquals("", decoded.calendar.prefix)
    }

    @Test fun everyEnumSelectionRoundTripsByName() {
        val base = fixture()
        val creates = Source.entries.map { base.create.copy(source = it) } +
            ImageEdit.entries.map { base.create.copy(imageEdit = it) } +
            TextType.entries.map { base.create.copy(textType = it) } +
            TextCase.entries.map { base.create.copy(textCase = it) } +
            ApplicationIconVariant.entries.map { base.create.copy(applicationIconVariant = it) }
        creates.forEach { assertEquals(it, roundTrip(base.copy(create = it)).create) }
        IconShape.entries.forEach { assertEquals(it, roundTrip(base.copy(adjustments = base.adjustments.copy(shape = it))).adjustments.shape) }
        OutlineMode.entries.forEach { assertEquals(it, roundTrip(base.copy(adjustments = base.adjustments.copy(outlineMode = it))).adjustments.outlineMode) }
    }

    @Test fun framingRejectsBadHeaderVersionTabTrailingAndEveryTruncation() {
        val bytes = EditorControlsCodec.encode(fixture())
        assertEquals(0x524B4544, ByteBuffer.wrap(bytes).int)
        rejected { EditorControlsCodec.decode(bytes.intAt(0, 0)) }
        rejected { EditorControlsCodec.decode(bytes.intAt(4, 2)) }
        rejected { EditorControlsCodec.decode(bytes.intAt(8, 4)) }
        rejected { EditorControlsCodec.decode(bytes + 0) }
        bytes.indices.forEach { size -> rejected { EditorControlsCodec.decode(bytes.copyOf(size)) } }
        rejected { EditorControlsCodec.decode(ByteArray(EditorDraftCodec.MAX_CONTROLS_BYTES.toInt() + 1)) }
    }

    @Test fun unknownEnumsInvalidBooleansAndTextLengthsFail() {
        val bytes = EditorControlsCodec.encode(fixture())
        val sourceLength = ByteBuffer.wrap(bytes).getInt(12)
        rejected { EditorControlsCodec.decode(bytes.copyOf().apply { this[16] = '!'.code.toByte() }) }
        rejected { EditorControlsCodec.decode(bytes.copyOf().apply { this[16 + sourceLength] = 2 }) }
        for (length in listOf(-1, Int.MAX_VALUE, 129)) rejected { EditorControlsCodec.decode(bytes.intAt(12, length)) }
    }

    @Test fun textIsStrictUtf8AndLimitedByBytes() {
        val base = fixture()
        val text = "unique-text"
        val bytes = EditorControlsCodec.encode(base.copy(create = base.create.copy(customText = text)))
        val offset = bytes.find(text.toByteArray())
        rejected { EditorControlsCodec.decode(bytes.copyOf().apply { this[offset] = 0xc0.toByte() }) }
        for (length in listOf(-1, Int.MAX_VALUE, EditorControlsCodec.MAX_TEXT_BYTES + 1))
            rejected { EditorControlsCodec.decode(bytes.intAt(offset - 4, length)) }
        rejected { EditorControlsCodec.encode(base.copy(create = base.create.copy(customText = "\uD800"))) }
        rejected { EditorControlsCodec.encode(base.copy(create = base.create.copy(customText = "é".repeat(32769)))) }
        val atLimit = base.copy(create = base.create.copy(customText = "é".repeat(32768)))
        assertEquals(atLimit, roundTrip(atLimit))
    }

    @Test fun nonFiniteValuesFailOnWriteAndRead() {
        val base = fixture()
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            rejected { EditorControlsCodec.encode(base.copy(upload = base.upload.copy(zoomLevel = invalid))) }
            rejected { EditorControlsCodec.encode(base.copy(adjustments = base.adjustments.copy(eraseStrokes =
                listOf(EditorBrushStroke(0.1f, listOf(EditorBrushPoint(invalid, 0f)), BrushAction.ERASE))))) }
        }
        val marker = 1234.567f
        val bytes = EditorControlsCodec.encode(base.copy(upload = base.upload.copy(zoomLevel = marker)))
        val offset = bytes.find(ByteBuffer.allocate(4).putFloat(marker).array())
        rejected { EditorControlsCodec.decode(bytes.intAt(offset, Float.NaN.toRawBits())) }
    }

    @Test fun listBudgetsAndCorruptCountsFailBeforeAllocation() {
        val base = fixture()
        rejected { EditorControlsCodec.encode(base.copy(adjustments = base.adjustments.copy(backgroundTargets = List(257) { it }))) }
        rejected { EditorControlsCodec.encode(base.copy(adjustments = base.adjustments.copy(colorizeLayers =
            List(129) { SegmentLayer(emptyList(), 0f, style(1)) }))) }
        rejected { EditorControlsCodec.encode(base.copy(create = base.create.copy(materialYouForegroundStyle =
            style(1).copy(gradientPositions = List(257) { 0f })))) }
        val marker = 0x13579abc
        val bytes = EditorControlsCodec.encode(base.copy(adjustments = base.adjustments.copy(backgroundTargets = listOf(marker))))
        val offset = bytes.find(ByteBuffer.allocate(4).putInt(marker).array())
        for (count in listOf(-1, Int.MAX_VALUE, 257)) rejected { EditorControlsCodec.decode(bytes.intAt(offset - 4, count)) }
    }

    @Test fun brushPointBudgetIsCumulativeAcrossBothLists() {
        val base = fixture()
        fun stroke(count: Int) = EditorBrushStroke(0.1f, List(count) { EditorBrushPoint(0.3f, 0.4f) }, BrushAction.RESTORE)
        val value = base.copy(adjustments = base.adjustments.copy(
            eraseStrokes = listOf(stroke(50_000)), backgroundBrushStrokes = listOf(stroke(50_000))))
        assertEquals(value, roundTrip(value))
        rejected { EditorControlsCodec.encode(value.copy(adjustments = value.adjustments.copy(backgroundBrushStrokes = listOf(stroke(50_001))))) }
        val bytes = EditorControlsCodec.encode(value)
        val marker = ByteBuffer.allocate(4).putInt(50_000).array()
        val offsets = (0..bytes.size - 4).filter { offset -> marker.indices.all { bytes[offset + it] == marker[it] } }
        assertEquals(2, offsets.size)
        rejected { EditorControlsCodec.decode(bytes.intAt(offsets.last(), 50_001) + ByteArray(8)) }
    }

    @Test fun strokeBudgetAppliesPerListAndCaptureChecksLimits() {
        val base = fixture()
        val stroke = EditorBrushStroke(0.1f, emptyList(), BrushAction.ERASE)
        val max = base.copy(adjustments = base.adjustments.copy(eraseStrokes = List(2048) { stroke }, backgroundBrushStrokes = List(2048) { stroke }))
        assertEquals(max, roundTrip(max))
        rejected { EditorControlsCodec.encode(max.copy(adjustments = max.adjustments.copy(eraseStrokes = List(2049) { stroke }))) }
        val editor = session()
        editor.adjustments.eraseStrokes = List(2049) { BrushStroke(0.1f, emptyList()) }
        rejected { editor.captureControls() }
        editor.adjustments.eraseStrokes = listOf(BrushStroke(0.1f, List(50_000) { Offset.Zero }))
        editor.adjustments.backgroundBrushStrokes = listOf(BrushStroke(0.1f, List(50_001) { Offset.Zero }))
        rejected { editor.captureControls() }
    }
}

private const val STORED_FORMAT_SHA256 = "acd2e102b213dacaf2e1c9f279d9f5019c222d29a434dcef5eb8d0ab1cdba7ea"
