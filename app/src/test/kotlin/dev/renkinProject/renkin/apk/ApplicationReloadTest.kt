package dev.renkinProject.renkin.apk

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.packages.PackageInfoStruct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ApplicationReloadTest {

    private class FakeIcon : IconPackDrawable() {
        @Composable override fun getPainter(): Painter = throw UnsupportedOperationException()
        override fun toBitmap(): Bitmap = throw UnsupportedOperationException()
        override fun toDbString(): String = "fake"
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("deprecated in Drawable")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    private fun app(
        name: String,
        pkg: String,
        activity: String = "$pkg.Main",
        iconId: Int = 0,
        createdIcon: IconPackDrawable? = null,
        calendar: Boolean = false,
        refreshMade: Boolean = false,
        custom: Boolean = false,
        legacy: Boolean = false,
        baseIcon: IconPackDrawable? = createdIcon
    ) = PackageInfoStruct(
        appName = name,
        packageName = pkg,
        activityName = activity,
        icon = ColorDrawable(iconId),
        iconID = iconId,
        createdIcon = createdIcon,
        calendarEnabled = calendar,
        calendarPrefix = if (calendar) "day_" else null,
        calendarPackName = if (calendar) "pack.calendar" else null,
        sourcePackName = createdIcon?.let { "pack.source" },
        originalName = "$name original",
        isFallback = createdIcon != null,
        isRefreshMade = refreshMade,
        isCustom = custom,
        isLegacy = legacy,
        baseIcon = baseIcon
    )

    @Test
    fun editedComponent_keepsSessionStateButUsesFreshSystemMetadata() {
        val icon = FakeIcon()
        val base = FakeIcon()
        val current = app(
            "Old label", "com.app", iconId = 1, createdIcon = icon, calendar = true,
            refreshMade = true, custom = true, legacy = true, baseIcon = base
        )
        val fresh = app("New label", "com.app", iconId = 9)

        val merged = mergeApplicationReload(listOf(current), listOf(fresh), setOf(current.key)).single()

        assertEquals("New label", merged.appName)
        assertEquals(9, merged.iconID)
        assertSame(fresh.icon, merged.icon)
        assertEquals("New label original", merged.originalName)
        assertSame(icon, merged.createdIcon)
        assertEquals("pack.source", merged.sourcePackName)
        assertTrue(merged.calendarEnabled)
        assertEquals("day_", merged.calendarPrefix)
        assertTrue(merged.isFallback)
        assertTrue(merged.isRefreshMade)
        assertTrue(merged.isCustom)
        assertTrue(merged.isLegacy)
        assertSame(base, merged.baseIcon)
    }

    @Test
    fun unchangedComponent_usesPersistedReloadAndNewAppsAreAdded() {
        val current = app("Current", "com.app", createdIcon = FakeIcon())
        val persisted = app("Reloaded", "com.app", createdIcon = FakeIcon())
        val added = app("Added", "com.new")

        val merged = mergeApplicationReload(listOf(current), listOf(persisted, added), emptySet())

        assertSame(persisted, merged[0])
        assertSame(added, merged[1])
    }

    @Test
    fun removedOrChangedComponent_isNotKeptAsAStaleApplication() {
        val old = app("Old", "com.app", activity = "com.app.Old", createdIcon = FakeIcon())
        val replacement = app("New", "com.app", activity = "com.app.New")

        val merged = mergeApplicationReload(listOf(old), listOf(replacement), setOf(old.key))

        assertEquals(listOf(replacement), merged)
    }

    @Test
    fun unsavedKeys_includeEditsRefreshOutputAndExplicitRemovalOnlyForLiveApps() {
        val edited = app("Edited", "com.edited", createdIcon = FakeIcon())
        val refreshed = app("Refreshed", "com.refreshed", createdIcon = FakeIcon(), refreshMade = true)
        val removed = app("Removed", "com.removed")
        val unchanged = app("Unchanged", "com.unchanged", createdIcon = FakeIcon())

        val keys = unsavedApplicationKeys(
            listOf(edited, refreshed, removed, unchanged),
            builtKeys = setOf(removed.key, unchanged.key, "com.uninstalled/com.uninstalled.Main"),
            updatedKeys = setOf(edited.key, "com.uninstalled/com.uninstalled.Main")
        )

        assertEquals(setOf(edited.key, refreshed.key, removed.key), keys)
        assertFalse("com.uninstalled/com.uninstalled.Main" in keys)
    }

    @Test
    fun replacingGlobalRenderNeverReplacesPersistedBase() {
        val base = FakeIcon()
        val firstRender = FakeIcon()
        val secondRender = FakeIcon()
        val original = app("App", "com.app", createdIcon = firstRender, baseIcon = base)

        val rerendered = original.changeRenderedIcon(secondRender)

        assertSame(secondRender, rerendered.createdIcon)
        assertSame(base, rerendered.baseIcon)
    }

    @Test
    fun globalCategoriesDistinguishFreshGeneratedExistingAndCustom() {
        assertTrue(shouldApplyGlobalLayer(false, true, applyGenerated = true, applyExisting = false, applyCustom = false))
        assertFalse(shouldApplyGlobalLayer(false, false, applyGenerated = true, applyExisting = false, applyCustom = false))
        assertTrue(shouldApplyGlobalLayer(false, false, applyGenerated = false, applyExisting = true, applyCustom = false))
        assertTrue(shouldApplyGlobalLayer(true, false, applyGenerated = false, applyExisting = false, applyCustom = true))
        assertFalse(shouldApplyGlobalLayer(true, true, applyGenerated = true, applyExisting = true, applyCustom = false))
    }

    @Test
    fun globalSaveProcessesOnlyEnabledCategories() {
        assertTrue(
            shouldProcessGlobalLayer(
                true, false, true,
                applyGenerated = true, applyExisting = false, applyCustom = false,
                includeEmpty = false
            )
        )
        assertFalse(
            shouldProcessGlobalLayer(
                true, false, false,
                applyGenerated = true, applyExisting = false, applyCustom = false,
                includeEmpty = false
            )
        )
        assertFalse(
            shouldProcessGlobalLayer(
                true, true, false,
                applyGenerated = true, applyExisting = true, applyCustom = false,
                includeEmpty = false
            )
        )
        assertTrue(
            shouldProcessGlobalLayer(
                false, false, false,
                applyGenerated = false, applyExisting = false, applyCustom = false,
                includeEmpty = true
            )
        )
    }

    @Test
    fun lockingSavedRefreshOutputMovesItFromGeneratedToExisting() {
        val generated = app(
            "Generated", "com.generated", createdIcon = FakeIcon(), refreshMade = true
        )

        val existing = generated.locked()

        assertTrue(generated.isRefreshMade)
        assertFalse(existing.isRefreshMade)
        assertFalse(
            shouldApplyGlobalLayer(
                existing.isCustom, existing.isRefreshMade,
                applyGenerated = true, applyExisting = false, applyCustom = false
            )
        )
        assertTrue(
            shouldApplyGlobalLayer(
                existing.isCustom, existing.isRefreshMade,
                applyGenerated = false, applyExisting = true, applyCustom = false
            )
        )
    }
}
