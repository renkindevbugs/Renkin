package dev.renkinProject.renkin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelStore
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.ui.IconOrigin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class EditorSessionViewModelTest {
    private val owner = ProfileSessionToken(1, 4, "provider")
    private fun app(name: String = "one") = PackageInfoStruct(
        name, "example.$name", "example.$name.Main", ColorDrawable(0), 0
    )
    private fun EditorSessionViewModel.open(
        id: String = "editor", token: ProfileSessionToken = owner,
        target: PackageInfoStruct = app(), allowCreate: Boolean = true
    ) = getOrCreate(id, token, target, "pack", "font", allowCreate)

    @Test fun reattachmentRetainsSelectionControlsAndCapturedApplication() {
        val vm = EditorSessionViewModel()
        val original = app()
        val session = requireNotNull(vm.open(target = original))
        session.customIconList = listOf(ResourceDrawable(7, ColorDrawable(0)))
        session.iconColor = Color.Red
        session.adjustments.iconScale = 0.8f
        session.selectedTab = 2
        session.textFontPath = "picked-font"
        val attached = vm.getOrCreate("editor", owner, app(), "other-pack", "other-font", false)
        assertSame(session, attached)
        assertSame(original, attached!!.application)
        assertEquals(7, attached.customIconList.single().resourceId)
        assertEquals(Color.Red, attached.iconColor)
        assertEquals(0.8f, attached.adjustments.iconScale)
        assertEquals(2, attached.selectedTab)
        assertEquals("picked-font", attached.textFontPath)
    }

    @Test fun missingRestoredDraftCannotBecomeDefaultEditor() {
        assertNull(EditorSessionViewModel().open(allowCreate = false))
    }

    @Test fun changedOwnerTargetAndIdentifierRejectReattachment() {
        val vm = EditorSessionViewModel()
        val session = requireNotNull(vm.open())
        for (token in listOf(owner.copy(profileId = 2), owner.copy(revision = 5), owner.copy(providerInstanceId = "new"))) {
            assertNull(vm.open(token = token, allowCreate = false))
        }
        assertNull(vm.open(target = app("two"), allowCreate = false))
        assertNull(vm.open(id = "other", allowCreate = false))
        assertSame(session, vm.open(allowCreate = false))
    }

    @Test fun explicitCloseDiscardsDraftAndFreshOpenHasDefaults() {
        val vm = EditorSessionViewModel()
        val first = requireNotNull(vm.open())
        first.customText = "unsaved"
        first.adjustments.shadowEnabled = true
        vm.release("editor")
        assertNull(vm.open(allowCreate = false))
        val next = requireNotNull(vm.open())
        assertNotSame(first, next)
        assertEquals("one", next.customText)
        assertFalse(next.adjustments.shadowEnabled)
    }

    @Test fun closingOlderEditorDoesNotReleaseNewOwner() {
        val vm = EditorSessionViewModel()
        vm.open()
        val newer = requireNotNull(vm.open(id = "other", target = app("two")))
        vm.release("editor")
        assertSame(newer, vm.open(id = "other", target = app("two"), allowCreate = false))
        assertNull(vm.open(allowCreate = false))
    }

    @Test fun activityViewModelStoreClearingReleasesLiveDraft() {
        val vm = EditorSessionViewModel()
        val store = ViewModelStore()
        store.put("editor", vm)
        vm.open()
        store.clear()
        assertNull(vm.open(allowCreate = false))
    }

    @Test fun uploadControlsAndVectorGeometryRetainTheirOwner() {
        val vm = EditorSessionViewModel()
        val session = requireNotNull(vm.open())
        session.upload.selectImported("gallery.png")
        session.upload.asAdaptiveIcon = true
        session.upload.zoomLevel = 1.4f
        session.vectorEditState.viewportOverride = 24f to 24f
        session.vectorEditState.thickness = 1.2f
        val attached = requireNotNull(vm.open(allowCreate = false))
        assertEquals("gallery.png", attached.upload.selectedImagePath)
        assertTrue(attached.upload.asAdaptiveIcon)
        assertEquals(1.4f, attached.upload.zoomLevel)
        assertEquals(24f to 24f, attached.vectorEditState.viewportOverride)
        assertEquals(1.2f, attached.vectorEditState.thickness)
    }

    @Test fun sourceChangeClearsOnlyPreviousSourceSelectionAndAttribution() {
        val session = requireNotNull(EditorSessionViewModel().open())
        session.calendarEnabled = true
        session.calendarPrefix = "calendar_"
        session.customIconList = listOf(ResourceDrawable(7, ColorDrawable(0)))
        session.adjustments.iconScale = 0.8f
        val icon = BitmapIconDrawable(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888), false)
        session.activateUpload(icon)
        session.upload.selectImported("gallery.png")
        session.onlineImageUrl = "https://example.org/icon.svg"
        assertEquals(IconOrigin.UPLOAD, session.draft.origin)
        assertFalse(session.calendarEnabled)
        assertNull(session.calendarPrefix)
        assertTrue(session.customIconList.isEmpty())
        session.activateCreate()
        assertNull(session.upload.selectedImagePath)
        assertFalse(session.upload.asAdaptiveIcon)
        assertEquals(1f, session.upload.zoomLevel)
        assertNull(session.onlineImageUrl)
        assertNull(session.draft.uploadBase)
        assertEquals(0.8f, session.adjustments.iconScale)
    }
}
