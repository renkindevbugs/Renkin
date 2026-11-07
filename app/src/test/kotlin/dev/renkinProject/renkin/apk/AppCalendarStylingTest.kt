package dev.renkinProject.renkin.apk

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.GlobalIconScaleKey
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.icon.creator.*
import dev.renkinProject.renkin.packages.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppCalendarStylingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun day(color: Int) = ResourceDrawable(0, BitmapDrawable(context.resources,
        Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }))

    @Test fun nativeDaysUseTheAppIconPipelineAndUnverifiedRotationExportsNoRawDays() = runBlocking {
        val source = CalendarSource.App(AppCalendarSource(context.packageName))
        val days = mapOf(1 to day(Color.RED), 2 to day(Color.BLUE))
        val repo = IconPackRepository(context, IconPackCatalog(context))
        val generator = IconGenerationService(context, repo, IconPackCatalog(context))
        val original = PackageInfoStruct("Calendar", context.packageName, "Main", days.getValue(1).drawable, 0)
        val options = GenerationOptions(IconSourceOptions(Source.APPLICATION_ICON),
            ModifierStack(EffectModifier(ImageEdit.NONE, ColorizerStyle(firstColor = Color.WHITE))), IconExportOptions(), true)
        val base = checkNotNull(generator.getIcon(original.withLoadedApplicationIcon(
            LoadedApplicationIcon(days.getValue(1).drawable, 0)), options))
        val input = checkNotNull(modifierInputForResult(base, options, ModifierInputOrigin.FRESH_SOURCE))
        val saved = original.changeExport(base, modifierInput = input)
        val raw = buildCalendarData(listOf(CalendarSelection(saved.toInstalledApplication(), source))) { days }
        val styler = CalendarDayStyler(context, generator)
        val rendered = styler.style(raw, listOf(saved), emptyPreferences())
        assertEquals(31, rendered.drawables.size)
        val prefix = rendered.mappings.getValue(saved.toInstalledApplication())
        val first = rendered.drawables.getValue(prefix + 1) as dev.renkinProject.renkin.drawable.IconPackDrawable
        val second = rendered.drawables.getValue(prefix + 2) as dev.renkinProject.renkin.drawable.IconPackDrawable
        assertTrue(first.toBitmap().looksAlike(base.toBitmap()))
        assertFalse(second.toBitmap().looksAlike(base.toBitmap()))

        val preferences = preferencesOf(GlobalIconScaleKey to 80)
        val globalRender = generator.applyModifier(base, globalModifierOptions(preferences))
        val globallyStyled = styler.style(raw, listOf(saved.changeRenderedIcon(globalRender)), preferences)
        val globalPrefix = globallyStyled.mappings.getValue(saved.toInstalledApplication())
        val globalDay = globallyStyled.drawables.getValue(globalPrefix + 1) as dev.renkinProject.renkin.drawable.IconPackDrawable
        assertTrue(globalDay.toBitmap().looksAlike(globalRender.toBitmap()))
        assertFalse(globalDay.toBitmap().looksAlike(base.toBitmap()))

        val unmatched = original.changeExport(checkNotNull(generator.getIcon(
            original.withLoadedApplicationIcon(LoadedApplicationIcon(day(Color.GREEN).drawable, 0)), options)))
        val dropped = styler.style(raw, listOf(unmatched), emptyPreferences())
        assertTrue(dropped.mappings.isEmpty())
        assertTrue(dropped.drawables.isEmpty())
        assertTrue(dropped.sources.isEmpty())
    }
}
