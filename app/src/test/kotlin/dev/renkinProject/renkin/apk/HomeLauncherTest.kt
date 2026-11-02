package dev.renkinProject.renkin.apk

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
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
        val activity = ActivityInfo().apply {
            this.packageName = packageName
            name = "$packageName.Launcher"
            enabled = true
            exported = true
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                nonLocalizedLabel = label
            }
        }
        shadowOf(application.packageManager).apply {
            addOrUpdateActivity(activity)
            addIntentFilterForActivity(ComponentName(activity.packageName, activity.name),
                IntentFilter(home.action).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addCategory(Intent.CATEGORY_DEFAULT)
                })
        }
    }

    @Test
    fun defaultHomeAppIsResolvedWithItsLabel() {
        registerHome("app.lawnchair.play", "Lawnchair")
        val launcher = resolveHomeLauncher(application.packageManager)
        assertEquals(HomeLauncher("app.lawnchair.play", "Lawnchair"), launcher)
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
