package dev.renkinProject.renkin.icon.creator

import android.graphics.Color
import dev.renkinProject.renkin.data.ImageEdit
import org.junit.Assert.*
import org.junit.Test

class ModifierRecipeCodecTest {
    private val gradient = ColorizerStyle(
        mode = ColorizerMode.GRADIENT,
        gradientType = GradientType.RADIAL,
        firstColor = 0x80123456.toInt(),
        gradientStops = listOf(Color.GREEN, Color.BLUE),
        gradientPositions = listOf(0f, 0.35f, 1f),
        gradientAngle = 247f,
        flat = true, lighten = true, monochrome = true, inverse = true
    )
    private fun recipe() = ModifierStack(
        effect = EffectModifier(
            ImageEdit.COLORIZE_SEGMENTS, gradient,
            EdgeModifier(1.25f, 9f, 3.5f, true),
            BackgroundRemovalModifier(listOf(Color.RED, Color.BLUE), 0.3f),
            listOf(SegmentLayer(listOf(Color.RED), 0.2f, gradient),
                SegmentLayer(listOf(Color.BLUE), 0.4f, gradient.copy(inverse = false)))
        ),
        transform = TransformModifier(0.75f, -0.2f, 0.15f),
        shape = ShapeModifier(IconShape.COOKIE, false, 1.2f, ColorFill(Color.MAGENTA, gradient)),
        outline = OutlineModifier(OutlineMode.RECOLOR, 9f, ColorFill(Color.CYAN, gradient)),
        shadow = ShadowModifier(12f, 8f, 70f, true, gradient, 0.4f, false),
        materialYouPack = MaterialYouPackModifier(Color.RED, Color.BLUE, 3, gradient, gradient.copy(flat = false), 1.4f)
    )

    @Test fun validRecipeRoundTripsEveryGroupAndUsesNamedEnums() {
        val original = recipe()
        val encoded = ModifierRecipeCodec.encode(original)
        assertEquals(original, ModifierRecipeCodec.decode(encoded))
        assertEquals(original, ModifierRecipeCodec.decodeSavedState(encoded))
        assertTrue(encoded.contains("effect.edit=COLORIZE_SEGMENTS\n"))
        assertTrue(encoded.contains("effect.style.mode=GRADIENT\n"))
        assertTrue(encoded.contains("effect.style.gradientType=RADIAL\n"))
    }

    @Test fun missingGroupsRemainDistinctFromConfiguredDisabledGroups() {
        val absent = recipe().copy(shape = null, outline = null, shadow = null)
        val disabled = recipe().copy(
            shape = recipe().shape!!.copy(shape = IconShape.NONE),
            outline = recipe().outline!!.copy(mode = OutlineMode.NONE)
        )
        for (original in listOf(absent, disabled)) {
            assertEquals(original, ModifierRecipeCodec.decode(ModifierRecipeCodec.encode(original)))
        }
        assertNotEquals(absent, disabled)
    }

    @Test fun inheritedFlatAndStyledFillsRemainDistinct() {
        for (fill in listOf(null, ColorFill(Color.RED), ColorFill(Color.RED, gradient))) {
            val original = recipe().copy(shape = recipe().shape!!.copy(fill = fill),
                outline = recipe().outline!!.copy(fill = fill ?: ColorFill(Color.TRANSPARENT)))
            assertEquals(original, ModifierRecipeCodec.decode(ModifierRecipeCodec.encode(original)))
        }
    }

    @Test fun savedStateRetainsValuesOutsideImportLimitsAndEmptyInactiveStops() {
        val style = gradient.copy(gradientStops = emptyList(), gradientPositions = listOf(-1f, 2f), gradientAngle = -45f)
        val original = recipe().copy(
            effect = recipe().effect.copy(colorizer = style,
                backgroundRemoval = BackgroundRemovalModifier((0..299).toList(), 0.8f),
                segments = List(129) { SegmentLayer(listOf(it), 1.5f, style) }),
            transform = TransformModifier(2f, -1f, 1f),
            shadow = recipe().shadow!!.copy(blur = 80f)
        )
        val encoded = ModifierRecipeCodec.encode(original)
        assertEquals(original, ModifierRecipeCodec.decodeSavedState(encoded))
        assertNull(ModifierRecipeCodec.decode(encoded))
    }

    @Test fun importedNumbersAreFiniteAndClampedToSupportedRanges() {
        val encoded = """
            recipe.version=1
            effect=1
            effect.edgeThreshold=99
            effect.edgeHighThreshold=-4
            effect.edgeSmoothing=Infinity
            effect.bgTolerance=-1
            transform=1
            transform.scale=-2
            transform.offsetX=-99
            transform.offsetY=99
            shape=1
            shape.scale=9
            outline=1
            outline.width=NaN
            shadow=1
            shadow.blur=99
            shadow.distance=-3
            shadow.angle=999
            shadow.opacity=99
            pack.strokeScale=-2
        """.trimIndent()
        val decoded = requireNotNull(ModifierRecipeCodec.decode(encoded))
        assertEquals(EdgeModifier(5f, 5f, 2f, false), decoded.effect.edges)
        assertEquals(0f, decoded.effect.backgroundRemoval.tolerance)
        assertEquals(TransformModifier(0.5f, -0.5f, 0.5f), decoded.transform)
        assertEquals(1.5f, decoded.shape!!.scale)
        assertEquals(6f, decoded.outline!!.width)
        assertEquals(32f, decoded.shadow!!.blur)
        assertEquals(0f, decoded.shadow.distance)
        assertEquals(360f, decoded.shadow.angle)
        assertEquals(1f, decoded.shadow.opacity)
        assertEquals(0.5f, decoded.materialYouPack.strokeScale)
    }

    @Test fun importedCollectionsAreBoundedAndSegmentToleranceIsNormalized() {
        val original = recipe().copy(effect = recipe().effect.copy(
            backgroundRemoval = BackgroundRemovalModifier((0..299).toList(), 0.3f),
            segments = listOf(SegmentLayer((0..299).toList(), 2f,
                gradient.copy(gradientStops = (0..19).toList(), gradientPositions = List(21) { it / 20f })))
        ))
        val decoded = requireNotNull(ModifierRecipeCodec.decode(ModifierRecipeCodec.encode(original)))
        assertEquals((0..255).toList(), decoded.effect.backgroundRemoval.targets)
        val layer = decoded.effect.segments.single()
        assertEquals((0..255).toList(), layer.targets)
        assertEquals(1f, layer.tolerance)
        assertEquals((0..8).toList(), layer.style.gradientStops)
        assertEquals(10, layer.style.gradientPositions.size)
    }

    @Test fun damagedGradientGetsFallbackAndPositionsCannotBreakShaders() {
        val original = recipe().copy(effect = recipe().effect.copy(
            colorizer = gradient.copy(gradientStops = emptyList(), gradientPositions = listOf(0f, 0.5f, 1f)),
            segments = listOf(SegmentLayer(listOf(Color.RED), style = gradient.copy(gradientPositions = listOf(-1f, 2f, 0.5f))))
        ))
        val decoded = requireNotNull(ModifierRecipeCodec.decode(ModifierRecipeCodec.encode(original)))
        assertEquals(listOf(Color.BLACK), decoded.effect.colorizer.gradientStops)
        assertTrue(decoded.effect.colorizer.gradientPositions.isEmpty())
        assertEquals(listOf(0f, 1f, 1f), decoded.effect.segments.single().style.gradientPositions)
    }

    @Test fun malformedDocumentsVersionsAndDuplicatesAreRejected() {
        val valid = ModifierRecipeCodec.encode(recipe())
        for (encoded in listOf("", "recipe.version=1", valid + "\ntransform.scale=1",
            valid + "\nbroken line", valid.replace("effect.segments=2", "effect.segments=-1"),
            valid.replace("effect.segments=2", "effect.segments=2147483647"),
            valid.replace("effect.segments=2", "effect.segments=bad"))) {
            assertNull(ModifierRecipeCodec.decode(encoded))
            assertNull(ModifierRecipeCodec.decodeSavedState(encoded))
        }
        for (version in listOf("0", "-1", "2", "unknown")) {
            assertNull(ModifierRecipeCodec.decode(valid.replace("recipe.version=1", "recipe.version=$version")))
        }
        assertEquals(recipe(), ModifierRecipeCodec.decode(valid + "\nfuture.field=unused"))
    }

    @Test fun oversizedDocumentsAreRejectedBeforeParsingLargeCollections() {
        val valid = ModifierRecipeCodec.encode(recipe())
        assertNull(ModifierRecipeCodec.decode(valid + "\nfuture=" + "x".repeat(1024 * 1024)))
        assertNull(ModifierRecipeCodec.decode(valid + (0..8192).joinToString("") { "\nunknown.$it=x" }))
    }

    @Test fun presetV1MatchesAnIndependentLegacyDocument() {
        val style = ColorizerStyle(firstColor = -1)
        val payload = ModifierPresetPayload(
            effect = ModifierPresetEffect(ImageEdit.COLORIZE, 2.5f, 2f, false, 0.1f, style),
            iconScale = 0.75f,
            shape = ModifierPresetShape(IconShape.NONE, false, 1.2f, style),
            outline = ModifierPresetOutline(OutlineMode.ADD, 6f, style),
            shadow = ModifierPresetShadow(false, 12f, 7f, 135f, true, style, 0.35f)
        )
        val legacy = """
            v=1
            effect=1
            effect.edit=COLORIZE
            effect.edgeThreshold=2.5
            effect.edgeSmoothing=2.0
            effect.edgeContrast=false
            effect.bgTolerance=0.1
            effect.style=0;0;-1;-16777216;0.0;false;false;false;;false
            iconScale=0.75
            shape=1
            shape.name=NONE
            shape.crop=false
            shape.scale=1.2
            shape.style=0;0;-1;-16777216;0.0;false;false;false;;false
            outline=1
            outline.mode=ADD
            outline.width=6.0
            outline.style=0;0;-1;-16777216;0.0;false;false;false;;false
            shadow=1
            shadow.enabled=false
            shadow.blur=12.0
            shadow.distance=7.0
            shadow.angle=135.0
            shadow.allDirections=true
            shadow.style=0;0;-1;-16777216;0.0;false;false;false;;false
            shadow.opacity=0.35
        """.trimIndent()
        assertEquals(legacy, encodeModifierPreset(payload))
        assertEquals(payload, decodeModifierPreset(legacy))
        assertEquals(payload.copy(iconScale = 1f), decodeModifierPreset(legacy + "\niconScale=1.0\nfuture=x"))
    }
}
