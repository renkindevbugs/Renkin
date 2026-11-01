package dev.renkinProject.renkin.data

import android.app.Application
import dev.renkinProject.renkin.apk.parseComponentInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric only for the real org.json implementation (android.jar ships stubs). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class AppTargetCodecTest {

    @Test fun roundTripKeepsOrderAndDropsDuplicateComponents() {
        val targets = listOf(
            AppTarget("org.fdroid.fdroid", "org.fdroid.fdroid.views.main.MainActivity", "F-Droid"),
            AppTarget("com.termux", "com.termux.app.TermuxActivity", "Termux")
        )
        val encoded = AppTargetCodec.encode(targets + targets.first().copy(label = "Duplicate"))
        assertEquals(targets, AppTargetCodec.decode(encoded))
        assertEquals("", AppTargetCodec.encode(emptyList()))
        assertTrue(AppTargetCodec.decode("").isEmpty())
    }

    @Test fun malformedDocumentsAndEntriesAreSkippedNotFatal() {
        assertTrue(AppTargetCodec.decode("not json").isEmpty())
        val decoded = AppTargetCodec.decode(
            """[1, {"packageName":"nodot","activityName":"Main"}, {"packageName":"com.ok","activityName":"Main","label":"  "}]"""
        )
        assertEquals(listOf(AppTarget("com.ok", "com.ok.Main", "com.ok")), decoded)
    }

    @Test fun activityShorthandExpandsLikeTheManifest() {
        assertEquals("com.app.Main", AppTargetCodec.expandActivityName("com.app", ".Main"))
        assertEquals("com.app.Main", AppTargetCodec.expandActivityName("com.app", "Main"))
        assertEquals("other.pkg.Main", AppTargetCodec.expandActivityName("com.app", "other.pkg.Main"))
    }

    @Test fun validationRejectsWhatALauncherCouldNeverMatch() {
        assertTrue(AppTargetCodec.isValidPackageName("com.example.app_2"))
        assertFalse(AppTargetCodec.isValidPackageName("single"))
        assertFalse(AppTargetCodec.isValidPackageName("com.1bad"))
        assertFalse(AppTargetCodec.isValidPackageName("com..empty"))
        assertTrue(AppTargetCodec.isValidClassName("com.app.Main\$Inner"))
        assertFalse(AppTargetCodec.isValidClassName("com.app.Main Activity"))
        assertNull(AppTargetCodec.validTarget("com.app", "", "Name"))
        assertEquals(AppTargetCodec.MAX_LABEL_LENGTH, AppTargetCodec.validTarget("com.app", ".Main", "x".repeat(300))!!.label.length)
    }

    @Test fun appfilterComponentsParseIncludingRelativeActivities() {
        assertEquals("com.termux" to "com.termux.app.TermuxActivity",
            parseComponentInfo("ComponentInfo{com.termux/com.termux.app.TermuxActivity}"))
        assertEquals("com.app" to "com.app.Main", parseComponentInfo(" ComponentInfo{com.app/.Main} "))
        assertNull(parseComponentInfo("ComponentInfo{com.app}"))
        assertNull(parseComponentInfo(":LAUNCHER_ACTION_APP_DRAWER"))
    }
}
