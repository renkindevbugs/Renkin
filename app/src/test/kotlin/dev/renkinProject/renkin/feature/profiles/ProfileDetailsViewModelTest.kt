package dev.renkinProject.renkin.feature.profiles

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.ProfilePackIconCodec
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.packages.InstalledIconLoader
import dev.renkinProject.renkin.ui.EditorPurpose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileDetailsViewModelTest {
    private val main = StandardTestDispatcher()
    private val context get() = RuntimeEnvironment.getApplication()
    private val owner = ProfileSessionToken(2, 1, "provider")
    private val automatic = "dev.renkinProject.renkinpack.p2"
    private val holder = ViewModelStore()
    private lateinit var model: ProfileDetailsViewModel

    @Before fun setup() {
        Dispatchers.setMain(main)
        model = ProfileDetailsViewModel(context, InstalledIconLoader(context))
        holder.put("details", model)
    }

    @After fun cleanup() {
        holder.clear()
        main.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun start(key: String = "form", packIcon: String = "", packageName: String = "") {
        model.start(key, "Personal", "Description", "My icons", packageName, packIcon, automatic)
    }

    private fun icon() = BitmapIconDrawable(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
        eraseColor(android.graphics.Color.RED)
    }, false)

    private fun awaitWork() = runBlocking {
        withTimeout(10_000) {
            do {
                main.scheduler.runCurrent()
                delay(5)
            } while (model.busy)
        }
    }

    @Test fun defaultIsEditableButUnchangedOrResetValueKeepsAutomaticAllocation() {
        start()
        assertEquals(automatic, model.customPackageName)
        assertEquals("", model.submission().customPackageName)
        model.editPackageName("com.example.myicons")
        assertTrue(model.hasCustomPackageName)
        assertEquals("com.example.myicons", model.submission().customPackageName)
        model.resetPackageName()
        assertEquals(automatic, model.customPackageName)
        assertFalse(model.hasCustomPackageName)
        assertEquals("", model.submission().customPackageName)
    }

    @Test fun reattachingSameFormRetainsInputsAndTheSeparateProfileEditor() {
        start(packageName = "com.example.icons")
        model.editName("Unsaved name")
        model.toggleAdvanced()
        model.openEditor(owner, "pack")
        val editor = requireNotNull(model.editor)
        editor.customText = "Unsaved artwork"
        start()
        assertEquals("Unsaved name", model.name)
        assertEquals("com.example.icons", model.customPackageName)
        assertTrue(model.advanced)
        assertSame(editor, model.editor)
        assertEquals(EditorPurpose.PROFILE_ICON, editor.purpose)
        assertEquals("Unsaved artwork", editor.customText)
    }

    @Test fun applyStagesDetachedArtworkAndClosingDuringSaveCannotDiscardIt() = runBlocking {
        start()
        model.openEditor(owner, "pack")
        val editor = requireNotNull(model.editor)
        val source = icon()
        model.confirmEditor(source, "source.pack", null, null, null)
        assertTrue(model.busy)
        model.closeEditor()
        assertSame(editor, model.editor)
        awaitWork()
        assertNull(model.editor)
        assertTrue(model.hasCustomIcon)
        val staged = requireNotNull(ProfilePackIconCodec(context).decode(model.submission().packIcon))
        assertEquals("source.pack", staged.sourcePackName)
        assertNotSame(source, model.preview)
        assertTrue(source.toBitmap().sameAs(staged.icon.toBitmap()))
        model.discard()
        start("reopened")
        assertFalse(model.hasCustomIcon)
        assertNull(model.preview)
    }

    @Test fun failedApplyKeepsEditorAndPreviousArtworkForRetry() = runBlocking {
        val saved = ProfilePackIconCodec(context).encode(icon())
        start(packIcon = saved)
        awaitWork()
        model.openEditor(owner, "pack")
        val editor = requireNotNull(model.editor)
        model.confirmEditor(icon(), null, "x".repeat(4097), null, null)
        awaitWork()
        assertSame(editor, model.editor)
        assertEquals(R.string.profileIconSaveFailed, model.errorRes)
        assertEquals(saved, model.submission().packIcon)
        model.confirmEditor(icon(), "new.source", null, null, null)
        awaitWork()
        assertNull(model.editor)
        assertNull(model.errorRes)
        assertEquals("new.source", ProfilePackIconCodec.sourcePackName(model.submission().packIcon))
    }

    @Test fun unreadableSavedIconSurvivesUnrelatedEditsUntilExplicitReset() {
        start(packIcon = "broken document")
        awaitWork()
        assertEquals(R.string.profileIconLoadFailed, model.errorRes)
        model.editDescription("Changed description")
        assertEquals("broken document", model.submission().packIcon)
        model.resetIcon()
        assertEquals("", model.submission().packIcon)
        assertNull(model.errorRes)
    }

    @Test fun cancellingIconEditorDoesNotClearTheProfileDraft() = runBlocking {
        val saved = ProfilePackIconCodec(context).encode(icon())
        start(packIcon = saved)
        awaitWork()
        model.openEditor(owner, "pack")
        model.closeEditor()
        assertNull(model.editor)
        assertTrue(model.hasCustomIcon)
        assertEquals(saved, model.submission().packIcon)
    }
}
