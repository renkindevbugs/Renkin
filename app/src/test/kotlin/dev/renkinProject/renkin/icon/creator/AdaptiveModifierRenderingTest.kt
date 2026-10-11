package dev.renkinProject.renkin.icon.creator

import android.app.Application
import android.content.ComponentName
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.MaterialYouPackEditState
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.packages.CHANGES_WITH_MATERIAL_YOU_COLORS
import dev.renkinProject.renkin.packages.ICON_PACK_ACTION
import dev.renkinProject.renkin.packages.IconPackCatalog
import dev.renkinProject.renkin.packages.PackageInfoStruct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * Adaptive pack/application icons must keep the optical size the editor shows without a
 * modifier, and Material You pack restyling must reach the foreground pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveModifierRenderingTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun glyph(color: Int = GLYPH): Drawable {
        val bitmap = Bitmap.createBitmap(216, 216, Bitmap.Config.ARGB_8888).apply {
            density = Bitmap.DENSITY_NONE
            Canvas(this).drawCircle(108f, 108f, 72f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun adaptiveFixture(): AdaptiveIconDrawable = AdaptiveIconDrawable(
        ColorDrawable(PLATE),
        InsetDrawable(glyph(), 0.25f),
        InsetDrawable(glyph(Color.BLACK), 0.25f)
    )

    private fun app(icon: Drawable = ColorDrawable(Color.RED)) = PackageInfoStruct(
        appName = "Fixture",
        packageName = "dev.test.fixture",
        activityName = "dev.test.fixture.Main",
        icon = icon,
        iconID = 0
    )

    private fun options(
        source: Source = Source.ICON_PACK,
        imageEdit: ImageEdit = ImageEdit.NONE,
        transform: TransformModifier = TransformModifier(),
        shape: ShapeModifier? = null,
        outline: OutlineModifier? = null,
        shadow: ShadowModifier? = null,
        pack: MaterialYouPackModifier = MaterialYouPackModifier(),
        packName: String = PACK
    ) = GenerationOptions(
        IconSourceOptions(primarySource = source, primaryIconPack = packName),
        ModifierStack(
            effect = EffectModifier(imageEdit, ColorizerStyle(firstColor = COLORIZE)),
            transform = transform,
            shape = shape,
            outline = outline,
            shadow = shadow,
            materialYouPack = pack
        ),
        IconExportOptions(background = ColorFill(Color.WHITE)),
        override = true
    )

    private fun registerMaterialYouPack(packageName: String) {
        val component = ComponentName(packageName, "$packageName.Theme")
        shadowOf(context.packageManager).apply {
            addActivityIfNotPresent(component)
            addIntentFilterForActivity(component, IntentFilter(ICON_PACK_ACTION).apply {
                addCategory(CHANGES_WITH_MATERIAL_YOU_COLORS)
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
            })
        }
    }

    private fun renderFromPack(options: GenerationOptions, packName: String = PACK): IconPackDrawable {
        val application = app()
        val resource = ResourceDrawable(0, adaptiveFixture())
        val pack = IconPackContainer(packName, mapOf(
            InstalledApplication(application.packageName, application.activityName, 0) to resource
        ))
        val generator = IconGenerator(context, options, pack, IconPackContainer("", emptyMap()),
            iconPackCatalog = IconPackCatalog(context))
        return requireNotNull(generator.generatePreview(application, resource).icon)
    }

    private fun renderFromApplication(options: GenerationOptions): IconPackDrawable {
        val generator = IconGenerator(context, options, IconPackContainer("", emptyMap()),
            IconPackContainer("", emptyMap()), iconPackCatalog = IconPackCatalog(context))
        return requireNotNull(generator.generatePreview(app(adaptiveFixture()), null).icon)
    }

    /** What the editor's comparison header and lists show for an icon. */
    private fun IconPackDrawable.shown(): Bitmap = previewBitmap(PREVIEW)

    // Relative to the frame: some sources change their canvas size when a modifier rasterises them.
    private fun Bitmap.glyphWidth(color: Int): Float {
        var left = Int.MAX_VALUE
        var right = -1
        for (y in 0 until height) for (x in 0 until width) {
            val pixel = getPixel(x, y)
            if (Color.alpha(pixel) > 200 && close(pixel, color)) {
                left = minOf(left, x)
                right = maxOf(right, x)
            }
        }
        return if (right < 0) 0f else (right - left + 1).toFloat() / width
    }

    private fun close(a: Int, b: Int) =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b)) < 60

    private fun save(name: String, bitmap: Bitmap) {
        val dir = File("build/outputs/adaptive-investigation").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val modifiers = listOf(
        Triple("outline", options(outline = OutlineModifier(OutlineMode.ADD, 6f, ColorFill(Color.RED))), GLYPH),
        Triple("shape", options(shape = ShapeModifier(IconShape.SQUIRCLE, crop = false, scale = 1f, fill = ColorFill(Color.DKGRAY))), GLYPH),
        Triple("scale", options(transform = TransformModifier(scale = 0.8f)), GLYPH),
        Triple("shadow", options(shadow = ShadowModifier(4f, 6f, 135f, false, ColorizerStyle(firstColor = Color.BLACK), 0.6f)), GLYPH),
        Triple("colorize", options(imageEdit = ImageEdit.COLORIZE), COLORIZE),
        Triple("removeBackground", options(imageEdit = ImageEdit.REMOVE_BACKGROUND), GLYPH)
    )

    private fun assertModifiersKeepGlyphSize(prefix: String, render: (GenerationOptions) -> IconPackDrawable) {
        val plain = render(options()).shown()
        save("$prefix-none", plain)
        val expected = plain.glyphWidth(GLYPH)
        assertTrue("$prefix: no glyph without a modifier", expected > 0f)
        val failures = mutableListOf<String>()
        for ((name, options, color) in modifiers) {
            val shown = render(options).shown()
            save("$prefix-$name", shown)
            val target = expected * options.modifiers.transform.scale
            val ratio = shown.glyphWidth(color) / target
            println("$prefix $name glyph ${shown.glyphWidth(color)} of frame, expected $target, ratio $ratio")
            if (abs(ratio - 1f) > 0.05f) failures += "$name ratio $ratio"
        }
        assertTrue("$prefix modifiers changed the glyph size: $failures", failures.isEmpty())
    }

    @Test
    fun packAdaptiveIconKeepsItsSizeUnderEveryModifier() =
        assertModifiersKeepGlyphSize("pack") { renderFromPack(it) }

    private fun Bitmap.count(predicate: (Int) -> Boolean): Int {
        var count = 0
        for (y in 0 until height) for (x in 0 until width) if (predicate(getPixel(x, y))) count++
        return count
    }

    @Test
    fun packAdaptiveSilhouetteEffectsStayVisible() {
        val plain = renderFromPack(options()).toModifierBitmap(PREVIEW)
        val outlined = renderFromPack(modifiers[0].second).shown()
        val shadowed = renderFromPack(modifiers[3].second).shown()
        val opaque = { pixel: Int -> Color.alpha(pixel) > 16 }
        assertTrue("Outline is invisible", outlined.count { Color.alpha(it) > 200 && close(it, Color.RED) } > 0)
        assertTrue("Shadow is invisible", shadowed.count(opaque) > plain.count(opaque))
    }

    @Test
    fun applicationAdaptiveIconKeepsItsSizeUnderEveryModifier() =
        assertModifiersKeepGlyphSize("app") { renderFromApplication(it.copy(source = it.source.copy(primarySource = Source.APPLICATION_ICON))) }

    @Test
    fun fullApplicationAdaptiveIconKeepsItsSizeUnderEveryModifier() =
        assertModifiersKeepGlyphSize("app-full") {
            renderFromApplication(it.copy(source = it.source.copy(
                primarySource = Source.APPLICATION_ICON, useFullApplicationIcon = true)))
        }

    private fun restyle(foreground: Int, background: Int) = MaterialYouPackModifier(
        foreground = foreground,
        background = background,
        selectedScheme = CUSTOM_SCHEME,
        customForeground = ColorizerStyle(firstColor = foreground),
        customBackground = ColorizerStyle(firstColor = background)
    )

    @Test
    fun materialYouPackForegroundColourReachesTheGlyph() {
        registerMaterialYouPack(PACK)
        val failures = mutableListOf<String>()
        for ((name, base) in listOf("plain" to options(), "outline" to modifiers[0].second, "shape" to modifiers[1].second)) {
            for ((foreground, background) in listOf(Color.RED to Color.BLACK, Color.GREEN to Color.BLACK, Color.GREEN to Color.BLUE)) {
                val icon = renderFromPack(base.copy(modifiers = base.modifiers.copy(materialYouPack = restyle(foreground, background))))
                val shown = icon.shown()
                save("restyle-$name-${Integer.toHexString(foreground)}-${Integer.toHexString(background)}", shown)
                val center = shown.getPixel(PREVIEW / 2, PREVIEW / 2)
                val plate = shown.getPixel(PREVIEW / 2, PREVIEW / 20)
                if (!close(center, foreground)) failures += "$name fg ${Integer.toHexString(foreground)}: centre ${Integer.toHexString(center)}"
                if (!close(plate, background)) failures += "$name bg ${Integer.toHexString(background)}: plate ${Integer.toHexString(plate)}"
            }
        }
        assertTrue("Restyled colours missing: $failures", failures.isEmpty())
    }

    @Test
    fun restyledPackIconRecordsItsEditState() {
        registerMaterialYouPack(PACK)
        val icon = renderFromPack(options(pack = restyle(Color.GREEN, Color.BLUE)))
        val state = (icon as? dev.renkinProject.renkin.drawable.AdaptiveIconPackDrawable)?.materialYouEditState
        assertNotNull(state)
        assertEquals(MaterialYouPackEditState(CUSTOM_SCHEME, ColorizerStyle(firstColor = Color.GREEN),
            ColorizerStyle(firstColor = Color.BLUE), 1f), state)
    }

    private companion object {
        const val PACK = "dev.test.adaptivepack"
        const val PREVIEW = 500
        const val CUSTOM_SCHEME = 5
        val PLATE = Color.rgb(30, 58, 138)
        const val GLYPH = Color.WHITE
        val COLORIZE = Color.rgb(250, 200, 0)
    }
}
