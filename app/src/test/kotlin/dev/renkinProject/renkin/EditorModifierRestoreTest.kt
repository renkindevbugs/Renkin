package dev.renkinProject.renkin

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.StorageOperationGate
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.icon.creator.*
import dev.renkinProject.renkin.packages.PackageInfoStruct
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorModifierRestoreTest {
    private val vm = EditorSessionViewModel()
    private val holder = ViewModelStore().apply { put("editor", vm) }
    private val owner = ProfileSessionToken(1, 4, "provider")
    private lateinit var verified: VerifiedModifierInput
    private lateinit var target: PackageInfoStruct
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun open(id: String = "editor") = checkNotNull(vm.getOrCreate(id, owner, target, "pack", "font", true))

    @Before fun setup() = runBlocking {
        StorageOperationGate.resetForTests()
        val icon = BitmapIconDrawable(RuntimeEnvironment.getApplication().resources,
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) })
        val settings = GenerationOptions(
            source = IconSourceOptions(primarySource = Source.ICON_PACK),
            modifiers = ModifierStack(EffectModifier(ImageEdit.NONE, ColorizerStyle(firstColor = Color.BLACK))),
            export = IconExportOptions(background = ColorFill(Color.BLACK)), override = true
        )
        val input = checkNotNull(modifierInputForResult(icon, settings, ModifierInputOrigin.UPLOAD))
        verified = checkNotNull(VerifiedModifierInput.verify(RuntimeEnvironment.getApplication().resources, icon, input, icon))
        target = PackageInfoStruct("App", "test.app", "Main", ColorDrawable(0), 0).changeExport(icon, modifierInput = input)
    }

    @After fun cleanup() {
        holder.clear()
        idle()
        StorageOperationGate.resetForTests()
    }

    @Test fun validRestorationPublishesIntoTheRetainedSession() {
        val session = open()
        vm.restoreModifiers("editor") { PreparedModifierReplay(verified, StorageOperationGate.epoch) }
        idle()
        assertSame(verified, session.modifierReplay!!.verified)
        assertFalse(session.restoringModifiers)
        assertFalse(session.modifierRestoreFailed)
        assertSame(session, open())
    }

    @Test fun cancellationRejectsEvenACancellationResistantLateResult() {
        val session = open()
        val result = CompletableDeferred<PreparedModifierReplay?>()
        vm.restoreModifiers("editor") { withContext(NonCancellable) { result.await() } }
        idle()
        assertTrue(session.restoringModifiers)
        vm.cancelModifierRestore("editor")
        result.complete(PreparedModifierReplay(verified, StorageOperationGate.epoch))
        idle()
        assertNull(session.modifierReplay)
        assertFalse(session.restoringModifiers)
        assertFalse(session.modifierRestoreFailed)
        assertTrue(session.draft.canRestore)
    }

    @Test fun replacingEditorRejectsThePreviousOwnersResult() {
        val old = open()
        val result = CompletableDeferred<PreparedModifierReplay?>()
        vm.restoreModifiers("editor") { withContext(NonCancellable) { result.await() } }
        idle()
        val newer = open("new-editor")
        result.complete(PreparedModifierReplay(verified, StorageOperationGate.epoch))
        idle()
        assertNull(old.modifierReplay)
        assertNull(newer.modifierReplay)
        assertFalse(newer.modifierRestoreFailed)
        assertSame(newer, open("new-editor"))
    }

    @Test fun storageReplacementRejectsPreparedResult() = runBlocking {
        val session = open()
        val prepared = PreparedModifierReplay(verified, StorageOperationGate.epoch)
        StorageOperationGate.run { StorageOperationGate.markStorageReplaced() }
        vm.restoreModifiers("editor") { prepared }
        idle()
        assertNull(session.modifierReplay)
        assertTrue(session.modifierRestoreFailed)
        assertFalse(session.restoringModifiers)
    }

    @Test fun sourceChangeWhileLoadingRejectsPreparedResult() {
        val session = open()
        val result = CompletableDeferred<PreparedModifierReplay?>()
        vm.restoreModifiers("editor") { result.await() }
        idle()
        session.activateUpload(verified.input)
        result.complete(PreparedModifierReplay(verified, StorageOperationGate.epoch))
        idle()
        assertNull(session.modifierReplay)
        assertFalse(session.modifierRestoreFailed)
        assertFalse(session.restoringModifiers)
        assertSame(verified.input, session.draft.uploadBase)
    }

    @Test fun unavailableInputPreservesBakedArtworkAndAllowsAnotherAttempt() {
        val session = open()
        val baked = session.draft.iconToConfirm
        vm.restoreModifiers("editor") { null }
        idle()
        assertTrue(session.modifierRestoreFailed)
        assertSame(baked, session.draft.iconToConfirm)
        assertTrue(session.draft.canRestore)
        vm.restoreModifiers("editor") { PreparedModifierReplay(verified, StorageOperationGate.epoch) }
        idle()
        assertFalse(session.modifierRestoreFailed)
        assertNotNull(session.modifierReplay)
    }
}
