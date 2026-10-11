package dev.renkinProject.renkin.ui

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.icon.creator.OutlineMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorAutosaveContentTest {
    @Test fun navigationRemainsInFullWitnessButIsNotAnEdit() {
        val session = EditorRecoveryFixtures.editor()
        val before = session.captureRenderWitness()
        session.selectedTab = 2
        session.browsingIconPacks = true
        val after = session.captureRenderWitness()
        assertNotEquals(before, after)
        assertEquals(before.autosaveContent(), after.autosaveContent())
        assertEquals(2, after.controls.selectedTab)
        assertTrue(after.controls.create.browsingIconPacks)
    }

    @Test fun seedingVectorPathsIsNotAnEditButPathChangesAndReversalAreDetected() {
        val session = EditorRecoveryFixtures.editor()
        val before = session.captureRenderWitness().autosaveContent()
        val state = session.vectorEditState
        val vector = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(listOf(PathNode.MoveTo(1f, 2f)), fill = SolidColor(Color.White))
        }.build()
        state.entries = listOf(PathEntry(vector.root[0] as VectorPath, true, 1f))
        state.initialized = true
        state.captureSeed()
        assertEquals(before, session.captureRenderWitness().autosaveContent())
        state.thickness = 1.5f
        assertNotEquals(before, session.captureRenderWitness().autosaveContent())
        state.thickness = 1f
        assertEquals(before, session.captureRenderWitness().autosaveContent())
    }

    @Test fun scaleColorizeOutlineAndBrushAllContributeToContent() {
        val session = EditorRecoveryFixtures.editor()
        var before = session.captureRenderWitness().autosaveContent()
        val changes: List<EditorSession.() -> Unit> = listOf(
            { adjustments.iconScale = 0.7f },
            { imageEdit = ImageEdit.COLORIZE },
            { adjustments.outlineMode = OutlineMode.ADD },
            { adjustments.eraseStrokes = listOf(BrushStroke(0.1f, listOf(Offset(0.2f, 0.3f)))) },
            { adjustments.backgroundBrushStrokes = listOf(BrushStroke(0.2f, listOf(Offset(0.3f, 0.4f)))) }
        )
        for (change in changes) {
            session.change()
            val after = session.captureRenderWitness().autosaveContent()
            assertNotEquals(before, after)
            before = after
        }
    }

    @Test fun changedOriginAndUploadSelectionContributeToContent() {
        val session = EditorRecoveryFixtures.editor()
        val before = session.captureRenderWitness().autosaveContent()
        session.upload.zoomLevel = 0.75f
        assertNotEquals(before, session.captureRenderWitness().autosaveContent())
        session.upload.zoomLevel = 1f
        session.activateVector(EditorRecoveryFixtures.icon())
        assertNotEquals(before, session.captureRenderWitness().autosaveContent())
    }

    @Test fun calendarAndAttributionArePreservedAsEdits() {
        val session = EditorRecoveryFixtures.editor()
        val before = session.captureRenderWitness().autosaveContent()
        session.calendarEnabled = true
        assertNotEquals(before, session.captureRenderWitness().autosaveContent())
        session.calendarEnabled = false
        session.onlineImageUrl = "https://example.com/icon.svg"
        assertNotEquals(before, session.captureRenderWitness().autosaveContent())
    }

    @Test fun equalOpeningRepublishedWithNewSerialIsStillUnchanged() = runBlocking {
        val session = EditorRecoveryFixtures.editor()
        val options = EditorRecoveryFixtures.options()
        session.draft.regenerateCreate(EditorRecoveryFixtures.builder, session.application, options,
            emptyList(), session.captureRenderWitness())
        val serial = session.draft.completedFrameSerial
        session.selectedTab = 2
        session.draft.acknowledgeWitness(session.captureRenderWitness(), session.application, options, emptyList())
        assertNotEquals(serial, session.draft.completedFrameSerial)
        assertTrue(session.draft.unchangedFromOpening)
    }

    @Test fun contentIsDetachedFromSubsequentBrushListMutation() {
        val session = EditorRecoveryFixtures.editor()
        val points = mutableListOf(Offset(0.1f, 0.2f))
        session.adjustments.eraseStrokes = listOf(BrushStroke(0.1f, points))
        val before = session.captureRenderWitness().autosaveContent()
        points += Offset(0.7f, 0.8f)
        assertEquals(1, before.controls.adjustments.eraseStrokes.single().points.size)
        assertNotEquals(before, session.captureRenderWitness().autosaveContent())
    }
}
