package dev.renkinProject.renkin.apk

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import dev.renkinProject.renkin.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class HomeLauncherTest {
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    private fun registerHome(packageName: String, label: String) {
        shadowOf(application.packageManager).addResolveInfoForIntent(home, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.Launcher"
                applicationInfo = ApplicationInfo().apply {
                    this.packageName = packageName
                    nonLocalizedLabel = label
                }
            }
        })
    }

    @Test
    fun supportTableOnlyNamesDocumentedLaunchers() {
        listOf("app.lawnchair", "app.lawnchair.play", "de.mm20.launcher2.release", "com.teslacoilsw.launcher",
            "bitpit.launcher", "ginlemon.flowerfree", "ginlemon.flowerpro").forEach {
            assertEquals(it, LauncherIconPackSupport.PICK_IN_SETTINGS, launcherIconPackSupport(it))
        }
        assertEquals(LauncherIconPackSupport.THEME_PARK, launcherIconPackSupport("com.sec.android.app.launcher"))
        assertEquals(LauncherIconPackSupport.NONE, launcherIconPackSupport("com.google.android.apps.nexuslauncher"))
        listOf("com.android.launcher", "com.android.launcher3", "com.miui.home", "com.microsoft.launcher").forEach {
            assertEquals(it, LauncherIconPackSupport.UNKNOWN, launcherIconPackSupport(it))
        }
    }

    @Test
    fun defaultHomeAppIsResolvedWithItsLabel() {
        registerHome("app.lawnchair.play", "Lawnchair")
        val launcher = resolveHomeLauncher(application.packageManager)
        assertEquals(HomeLauncher("app.lawnchair.play", "Lawnchair"), launcher)
        assertEquals(LauncherIconPackSupport.PICK_IN_SETTINGS, launcher?.iconPackSupport)
    }

    @Test
    fun platformChooserMeansNoDefaultHomeApp() {
        registerHome("android", "Android System")
        assertNull(resolveHomeLauncher(application.packageManager))
    }

    @Test
    fun missingHomeActivityMeansNoDefaultHomeApp() {
        assertNull(resolveHomeLauncher(application.packageManager))
    }

    @Test
    fun generatedPackDeclaresTheAdwActivityStarterDiscoveryAction() {
        val parser = application.resources.getXml(R.xml.manifest)
        val actions = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "action") {
                actions += parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
            }
        }
        assertTrue("org.adw.ActivityStarter.THEMES" in actions)
        assertTrue("org.adw.launcher.THEMES" in actions)
        assertTrue("com.novalauncher.THEME" in actions)
    }
}
