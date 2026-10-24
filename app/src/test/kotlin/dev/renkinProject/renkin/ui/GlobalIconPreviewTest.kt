package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ImageVectorDrawable
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.EffectModifier
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconExportOptions
import dev.renkinProject.renkin.icon.creator.IconSourceOptions
import dev.renkinProject.renkin.icon.creator.ModifierStack
import dev.renkinProject.renkin.icon.creator.TransformModifier
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlobalIconPreviewTest {
    @get:Rule val compose = createComposeRule()

    private val initial = GenerationOptions(
        source = IconSourceOptions(Source.APPLICATION_ICON),
        modifiers = ModifierStack(EffectModifier(ImageEdit.NONE, ColorizerStyle(firstColor = -1))),
        export = IconExportOptions(vector = true),
        override = true
    )
    private var source by mutableStateOf<IconPackDrawable?>(null)
    private var options by mutableStateOf(initial)
    private var owner by mutableStateOf(ProfileSessionToken(1, 1, "preview-test"))
    private var active by mutableStateOf(true)
    @Volatile private var observed = GlobalIconPreviewState(null, false, false)
    private val calls = AtomicInteger()

    private fun icon() = ImageVectorDrawable(ImageVector.Builder("source", 24.dp, 24.dp, 24f, 24f).build())
    private fun scaled(scale: Float) = initial.copy(
        modifiers = initial.modifiers.copy(transform = TransformModifier(scale = scale))
    )

    private fun mount(render: suspend (IconPackDrawable, GenerationOptions) -> IconPackDrawable) {
        compose.setContent {
            val state = rememberGlobalIconPreview(source, options, owner, active) { input, settings ->
                calls.incrementAndGet()
                render(input, settings)
            }
            SideEffect { observed = state }
            Text("${state.generating} / ${state.canApply}")
        }
        compose.waitForIdle()
    }

    private fun await(condition: () -> Boolean) {
        compose.waitUntil(5_000) {
            compose.waitForIdle()
            condition()
        }
    }

    @Test fun lateUntouchedVectorAppearsWithoutRenderingOrEnablingApply() {
        val vector = icon()
        mount { _, _ -> error("Untouched vector must not be rendered") }
        assertFalse(observed.canApply)
        compose.runOnIdle { source = vector }
        compose.runOnIdle {
            assertSame(vector, observed.icon)
            assertFalse(observed.generating)
            assertFalse(observed.canApply)
            assertEquals(0, calls.get())
        }
    }

    @Test fun firstEditAfterLateSourceRendersImmediately() {
        val rendered = icon()
        mount { _, _ -> rendered }
        compose.runOnIdle { source = icon() }
        compose.runOnIdle { options = scaled(0.8f) }
        await { observed.canApply }
        assertSame(rendered, observed.icon)
        assertEquals(1, calls.get())
    }

    @Test fun editBeforeSourceArrivalIsRenderedWhenItArrives() {
        val rendered = icon()
        mount { _, settings -> assertEquals(scaled(0.8f), settings); rendered }
        compose.runOnIdle { options = scaled(0.8f) }
        compose.runOnIdle { assertEquals(0, calls.get()); assertFalse(observed.canApply) }
        compose.runOnIdle { source = icon() }
        await { observed.canApply }
        assertSame(rendered, observed.icon)
    }

    @Test fun revertingOptionsRestoresOriginalVectorAndDisablesApply() {
        val vector = icon()
        source = vector
        mount { _, _ -> icon() }
        compose.runOnIdle { options = scaled(0.8f) }
        await { observed.canApply }
        compose.runOnIdle { options = initial }
        compose.runOnIdle {
            assertSame(vector, observed.icon)
            assertFalse(observed.canApply)
            assertFalse(observed.generating)
            assertEquals(1, calls.get())
        }
    }

    @Test fun changedOptionsCannotApplyPreviousCompletedPreview() {
        source = icon()
        val next = CompletableDeferred<IconPackDrawable>()
        mount { _, settings -> if (settings == scaled(0.8f)) icon() else next.await() }
        compose.runOnIdle { options = scaled(0.8f) }
        await { observed.canApply }
        compose.runOnIdle { options = scaled(0.6f) }
        compose.runOnIdle { assertFalse(observed.canApply); assertTrue(observed.generating) }
        val latest = icon()
        next.complete(latest)
        await { observed.canApply }
        assertSame(latest, observed.icon)
    }

    @Test fun obsoleteNonCancellableRenderCannotReplaceNewSource() {
        val oldSource = icon()
        val newSource = icon()
        val obsolete = CompletableDeferred<IconPackDrawable>()
        val oldReturned = CompletableDeferred<Unit>()
        val latest = icon()
        source = oldSource
        mount { input, _ ->
            if (input === oldSource) withContext(NonCancellable) {
                obsolete.await().also { oldReturned.complete(Unit) }
            } else latest
        }
        compose.runOnIdle { options = scaled(0.8f) }
        await { calls.get() == 1 }
        compose.runOnIdle { source = newSource }
        await { observed.canApply }
        assertSame(latest, observed.icon)
        obsolete.complete(icon())
        await { oldReturned.isCompleted }
        compose.runOnIdle { assertSame(latest, observed.icon); assertTrue(observed.canApply) }
    }

    @Test fun inactiveOrChangedSessionDisablesApplyAndResetsOpeningOptions() {
        source = icon()
        mount { _, _ -> icon() }
        compose.runOnIdle { options = scaled(0.8f) }
        await { observed.canApply }
        compose.runOnIdle { active = false }
        compose.runOnIdle { assertFalse(observed.canApply); assertSame(source, observed.icon) }
        compose.runOnIdle { owner = owner.copy(revision = 2); active = true }
        compose.runOnIdle { assertFalse(observed.canApply); assertEquals(1, calls.get()) }
    }

    @Test fun renderFailureLeavesSourceVisibleAndApplyDisabled() {
        source = icon()
        val failed = CompletableDeferred<Unit>()
        mount { _, _ -> failed.complete(Unit); throw IllegalStateException("Test rendering failure") }
        compose.runOnIdle { options = scaled(0.8f) }
        await { failed.isCompleted && !observed.generating }
        assertFalse(observed.canApply)
        assertSame(source, observed.icon)
    }

    @Test fun cancelledRenderDoesNotPublishAnApplicableResult() {
        source = icon()
        val cancelled = CompletableDeferred<Unit>()
        mount { _, _ -> cancelled.complete(Unit); throw CancellationException("Cancelled preview") }
        compose.runOnIdle { options = scaled(0.8f) }
        await { cancelled.isCompleted }
        compose.runOnIdle { assertFalse(observed.canApply); assertSame(source, observed.icon) }
    }
}
