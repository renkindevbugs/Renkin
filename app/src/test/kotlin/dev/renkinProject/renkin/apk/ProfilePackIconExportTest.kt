package dev.renkinProject.renkin.apk

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import dev.renkinProject.renkin.drawable.AdaptiveIconPackDrawable
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.IconPackDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfilePackIconExportTest {
    private data class Export(val module: ApkModule, val resources: PackageBlock, val manifest: AndroidManifestBlock)

    @Test
    fun legacyCustomIconIsTheManifestIconAndDoesNotRetainDefaultAdaptiveVariants() {
        val pixels = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val result = export(BitmapIconDrawable(pixels))
        val entry = result.resources.getEntry("", "mipmap", "ic_launcher")
        assertNotNull(entry)
        assertEquals(entry.resourceId, result.manifest.iconResourceId)
        assertEquals(entry.resourceId, result.manifest.roundIconResourceId)
        assertNull(result.resources.getEntry("anydpi-v26", "mipmap", "ic_launcher"))
        assertNull(result.resources.getEntry("anydpi-v33", "mipmap", "ic_launcher"))
        assertNull(result.resources.getEntry("", "drawable", "ic_launcher_monochrome"))
        result.module.getInputSource(entry.valueAsString).openStream().use {
            assertEquals(Color.MAGENTA, BitmapFactory.decodeStream(it)!!.getPixel(4, 4))
        }
    }

    @Test
    fun adaptiveCustomIconExportsIndependentLayersAndVersionedMonochrome() {
        val icon = AdaptiveIconPackDrawable.capture(
            AdaptiveIconDrawable(ColorDrawable(Color.BLUE), ColorDrawable(Color.RED)),
            monochrome = ColorDrawable(Color.WHITE)
        )
        val result = export(icon)
        for (name in listOf("ic_launcher_foreground", "ic_launcher_background", "ic_launcher_monochrome")) {
            val layer = result.resources.getEntry("nodpi", "drawable", name)
            assertNotNull("Missing copied layer $name", layer)
            assertNotNull(result.module.getInputSource(layer.valueAsString))
        }
        val legacy = result.resources.getEntry("", "mipmap", "ic_launcher")
        val adaptive = result.resources.getEntry("anydpi-v26", "mipmap", "ic_launcher")
        val themed = result.resources.getEntry("anydpi-v33", "mipmap", "ic_launcher")
        assertNotNull(legacy)
        assertEquals(legacy.resourceId, result.manifest.iconResourceId)
        assertEquals(legacy.resourceId, result.manifest.roundIconResourceId)
        assertTrue(adaptive.valueAsString != themed.valueAsString)
        assertTrue(result.module.decodeXMLFile(adaptive.valueAsString).toString().contains("adaptive-icon"))
        val themedXml = com.reandroid.arsc.chunk.xml.ResXmlDocument().apply {
            result.module.getInputSource(themed.valueAsString).openStream().use { readBytes(it) }
        }
        assertTrue(themedXml.recursiveElements().asSequence().any { it.name == "monochrome" })
    }

    @Test
    fun adaptiveIconWithoutMonochromeDoesNotKeepTheRenkinThemedLogo() {
        val result = export(AdaptiveIconPackDrawable.capture(
            AdaptiveIconDrawable(ColorDrawable(Color.BLUE), ColorDrawable(Color.RED))))
        assertNotNull(result.resources.getEntry("anydpi-v26", "mipmap", "ic_launcher"))
        assertNull(result.resources.getEntry("anydpi-v33", "mipmap", "ic_launcher"))
        assertNull(result.resources.getEntry("nodpi", "drawable", "ic_launcher_monochrome"))
    }

    private fun export(icon: IconPackDrawable): Export {
        val context = RuntimeEnvironment.getApplication()
        val module = ApkModule()
        val table = TableBlock()
        val manifest = AndroidManifestBlock()
        module.tableBlock = table
        module.setManifest(manifest)
        module.initializeAndroidFramework(34)
        val resources = table.newPackage(0x7f, "example.custom.profile")
        manifest.packageName = "example.custom.profile"
        manifest.setApplicationLabel("Custom profile")
        assertNotNull("Fixture must resolve android:drawable", table.getResource(android.R.attr.drawable))
        val builder = IconPackBuilder(context, emptyList(), emptyMap(), emptyMap(), packIcon = icon)
        IconPackBuilder::class.java.getDeclaredMethod("insertIconPackAppIcons", ApkModule::class.java,
            PackageBlock::class.java, AndroidManifestBlock::class.java).apply { isAccessible = true }
            .invoke(builder, module, resources, manifest)
        return Export(module, resources, manifest)
    }
}
