package dev.renkinProject.renkin.apk

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import dev.renkinProject.renkin.drawable.AdaptiveIconPackDrawable
import dev.renkinProject.renkin.packages.PackageInfoStruct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The generated pack's preview screen (issue #28) looks its data up by name, so a renamed or
 * missing resource only shows as an empty screen on the device, and a smali typo only fails the
 * pack build there. Both contracts are checked here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PackPreviewResourcesTest {

    @Test
    fun previewListsOneLabelledIconPerApp() {
        val apps = listOf(app("Chrome", "com.android.chrome"), app("", "org.example.unnamed"))
        val (_, resources) = writePreview(apps, profileName = "Work")

        assertEquals(listOf("com_android_chrome", "org_example_unnamed"), array(resources, "renkin_preview_icons"))
        assertEquals(listOf("Chrome", "org_example_unnamed"), array(resources, "renkin_preview_labels"))
        val subtitle = resources.getEntry("", "string", "renkin_preview_subtitle").valueAsString
        assertTrue(subtitle, subtitle.startsWith("Profile Work · 2 icons · "))
    }

    @Test
    fun previewWritesEveryTextAndGlyphTheActivityReads() {
        val (_, resources) = writePreview(emptyList(), profileName = "")
        val smali = context().assets.open("MainActivity").use { it.readBytes().toString(Charsets.UTF_8) }
        val names = Regex("\"(renkin_preview_[a-z_]+)\"").findAll(smali).map { it.groupValues[1] }.toSet()

        assertTrue(names.isNotEmpty())
        for (name in names) {
            val found = listOf("string", "array", "drawable").any { resources.getEntry("", it, name) != null }
            assertTrue("Missing pack resource $name", found)
        }
    }

    @Test
    fun packSmaliAssemblesIntoOneDex() {
        val (module, _) = writePreview(emptyList(), profileName = "")
        IconPackBuilder::class.java.getDeclaredMethod("buildDex", ApkModule::class.java)
            .apply { isAccessible = true }
            .invoke(builder(emptyList(), ""), module)

        val dex = module.getInputSource("classes.dex").openStream().use { it.readBytes() }.toString(Charsets.ISO_8859_1)
        assertTrue(dex.contains("Ldev/renkinProject/renkinpack/MainActivity;"))
        assertTrue(dex.contains("Ldev/renkinProject/renkinpack/IconAdapter;"))
    }

    private fun writePreview(apps: List<PackageInfoStruct>, profileName: String): Pair<ApkModule, PackageBlock> {
        val module = ApkModule()
        val table = TableBlock()
        module.tableBlock = table
        module.setManifest(AndroidManifestBlock())
        module.initializeAndroidFramework(34)
        val resources = table.newPackage(0x7f, "example.preview")
        IconPackBuilder::class.java.getDeclaredMethod(
            "createPreviewResources", ApkModule::class.java, PackageBlock::class.java, Map::class.java
        ).apply { isAccessible = true }
            .invoke(builder(apps, profileName), module, resources, IconPackBuilder.uniqueDrawableFileNames(apps))
        return module to resources
    }

    private fun builder(apps: List<PackageInfoStruct>, profileName: String) =
        IconPackBuilder(context(), apps, emptyMap(), emptyMap(), packLabel = "Pack", profileName = profileName)

    private fun array(resources: PackageBlock, name: String): List<String> =
        resources.getEntry("", "array", name).also { assertNotNull(name, it) }
            .resValueMapArray.iterator().asSequence().map { it.valueAsString }.toList()

    private fun app(name: String, packageName: String) = PackageInfoStruct(
        appName = name,
        packageName = packageName,
        activityName = "$packageName.Main",
        icon = ColorDrawable(Color.RED),
        iconID = 0,
        createdIcon = AdaptiveIconPackDrawable.capture(
            AdaptiveIconDrawable(ColorDrawable(Color.BLUE), ColorDrawable(Color.RED))
        )
    )

    private fun context() = RuntimeEnvironment.getApplication()
}
