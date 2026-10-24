package dev.renkinProject.renkin.apk

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.icon.creator.IconModifierInput
import dev.renkinProject.renkin.icon.creator.ModifierInputOrigin
import dev.renkinProject.renkin.icon.creator.modifierInputTestOptions
import dev.renkinProject.renkin.packages.PackageInfoStruct
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How a saved row reaches the app list. A new [RenkinPackStore.SavedEntry] field fails here until
 * it is declared below and, when it describes the icon, carried by [withSavedEntry].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class SavedEntryProjectionTest {

    private enum class Rule {
        /** Copied onto the app by [withSavedEntry]. */
        PROJECTED,
        /** Each caller resolves the icon itself (restore, load and the locked refresh differ). */
        RESOLUTION,
        /** Kept with the entry for held-back rows; never projected. */
        HELD
    }

    private val contract = mapOf(
        "icon" to Rule.RESOLUTION,
        "baseIcon" to Rule.RESOLUTION,
        "calendarEnabled" to Rule.PROJECTED,
        "calendarPrefix" to Rule.PROJECTED,
        "calendarPackName" to Rule.PROJECTED,
        "sourcePackName" to Rule.PROJECTED,
        "isCustom" to Rule.PROJECTED,
        "isLegacy" to Rule.PROJECTED,
        "sourceUrl" to Rule.PROJECTED,
        "isFallback" to Rule.PROJECTED,
        "row" to Rule.HELD,
        "decodeFailed" to Rule.HELD,
        "modifierRecipe" to Rule.PROJECTED,
        "modifierInput" to Rule.PROJECTED
    )

    private fun icon() = BitmapIconDrawable(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))

    private fun row(drawable: String = "payload", sourcePack: String = "pack.a") =
        DbApplication("com.app", "com.app.Main", false, false, drawable, sourcePackName = sourcePack)

    @Test fun everySavedEntryFieldIsDeclared() {
        val fields = RenkinPackStore.SavedEntry::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .map { it.name }
            .toSet()
        assertEquals(contract.keys, fields)
    }

    @Test fun everyProjectedFieldReachesTheApp() {
        val input = IconModifierInput(modifierInputTestOptions(), false, ModifierInputOrigin.UPLOAD)
        val entry = RenkinPackStore.SavedEntry(
            icon = icon(),
            baseIcon = icon(),
            calendarEnabled = true,
            calendarPrefix = "cal_",
            calendarPackName = "pack.cal",
            sourcePackName = "pack.a",
            isCustom = true,
            isLegacy = true,
            sourceUrl = "https://example.org/icon.svg",
            isFallback = true,
            row = row(),
            modifierRecipe = "recipe",
            modifierInput = input
        )
        val resolved = icon()
        val base = icon()
        val app = PackageInfoStruct("App", "com.app", "com.app.Main", ColorDrawable(), 0)

        val projected = app.withSavedEntry(entry, resolved, base)

        assertSame(resolved, projected.createdIcon)
        assertSame(base, projected.baseIcon)
        assertTrue(projected.calendarEnabled)
        assertEquals("cal_", projected.calendarPrefix)
        assertEquals("pack.cal", projected.calendarPackName)
        assertEquals("pack.a", projected.sourcePackName)
        assertTrue(projected.isCustom)
        assertTrue(projected.isLegacy)
        assertEquals("https://example.org/icon.svg", projected.sourceUrl)
        assertTrue(projected.isFallback)
        assertEquals("recipe", projected.modifierRecipe)
        assertSame(input, projected.modifierInput)
    }

    @Test fun refreshMarkerIsKeptUnlessTheCallerResetsIt() {
        val entry = RenkinPackStore.SavedEntry(
            icon = icon(), baseIcon = null, calendarEnabled = false, calendarPrefix = null,
            calendarPackName = null, sourcePackName = null, isCustom = false, isLegacy = false,
            sourceUrl = null, isFallback = false, row = row()
        )
        val refreshed = PackageInfoStruct("App", "com.app", "com.app.Main", ColorDrawable(), 0, isRefreshMade = true)

        assertTrue(refreshed.withSavedEntry(entry, icon(), null).isRefreshMade)
        assertFalse(refreshed.withSavedEntry(entry, icon(), null, isRefreshMade = false).isRefreshMade)
    }

    @Test fun onlyARowWithoutArtworkButWithASourcePackIsAReference() {
        fun entry(icon: Boolean, drawable: String, sourcePack: String?) = RenkinPackStore.SavedEntry(
            icon = if (icon) icon() else null, baseIcon = null, calendarEnabled = false, calendarPrefix = null,
            calendarPackName = null, sourcePackName = sourcePack, isCustom = false, isLegacy = false,
            sourceUrl = null, isFallback = false, row = row(drawable, sourcePack.orEmpty())
        )
        assertTrue(entry(icon = false, drawable = "", sourcePack = "pack.a").isReference)
        assertFalse(entry(icon = true, drawable = "", sourcePack = "pack.a").isReference)
        assertFalse(entry(icon = false, drawable = "payload", sourcePack = "pack.a").isReference)
        assertFalse(entry(icon = false, drawable = "", sourcePack = null).isReference)
    }
}
