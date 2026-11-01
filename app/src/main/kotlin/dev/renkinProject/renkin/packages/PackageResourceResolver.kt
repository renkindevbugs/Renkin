package dev.renkinProject.renkin.packages

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.drawable.Drawable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.renkinProject.renkin.extension.getDrawableOrNull
import dev.renkinProject.renkin.extension.getIdentifierByName
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Resolves resources owned by another installed package.
 *
 * This is deliberately independent from app and icon-pack discovery: renderers that only need
 * a drawable or XML resource should not have to construct the full [ApplicationManager].
 */
internal class PackageResourceResolver(private val context: Context) {
    private val packageManager = context.packageManager

    companion object {
        /**
         * The night mode Renkin's Compose theme currently displays. Package resources must follow
         * this value because an in-app theme override does not update Android's configuration.
         */
        var displayedNightMode: Boolean? by mutableStateOf(null)
    }

    fun getResources(packageName: String): Resources? = try {
        packageManager.getResourcesForApplication(packageName).withDisplayedNightMode()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun getResources(info: android.content.pm.ApplicationInfo, configuration: Configuration? = null): Resources? = try {
        val manager = configuration?.let { context.createConfigurationContext(Configuration(it)).packageManager }
            ?: packageManager
        manager.getResourcesForApplication(info).withDisplayedNightMode()
    } catch (_: PackageManager.NameNotFoundException) { null }

    fun getDrawable(packageName: String, resourceId: Int): Drawable? =
        getResources(packageName)?.let { resources -> getDrawable(resources, resourceId) }

    fun getDrawableByName(packageName: String, name: String): Drawable? =
        getResources(packageName)?.let { getDrawableByName(it, packageName, name) }

    // Callers looking up many names resolve Resources once (a night-mode override builds a new one).
    fun getDrawableByName(resources: Resources, packageName: String, name: String): Drawable? =
        getDrawable(resources, resources.getIdentifierByName(name, "drawable", packageName))

    /** Cheap existence check: resolves the resource id without decoding the drawable. */
    fun hasDrawable(packageName: String, name: String): Boolean =
        getResources(packageName)?.let { hasDrawable(it, packageName, name) } ?: false

    fun hasDrawable(resources: Resources, packageName: String, name: String): Boolean =
        resources.getIdentifierByName(name, "drawable", packageName) > 0

    fun getXml(resources: Resources, packageName: String, name: String): XmlResourceParser? {
        val resourceId = resources.getIdentifierByName(name, "xml", packageName)
        return if (resourceId > 0) resources.getXml(resourceId) else null
    }

    /**
     * Parses [name] from the package's res/xml, otherwise from its `assets/[name].xml`, and closes
     * the parser or stream it opened whether [read] returns or throws. Null when neither exists.
     */
    inline fun <T> withPackXml(resources: Resources, packageName: String, name: String, read: (XmlPullParser) -> T): T? {
        getXml(resources, packageName, name)?.let { parser ->
            return try {
                read(parser)
            } finally {
                parser.close()
            }
        }
        val assets = resources.assets.list("")
        if (assets == null || !assets.contains("$name.xml")) return null
        return resources.assets.open("$name.xml").use { stream ->
            read(XmlPullParserFactory.newInstance().newPullParser().apply { setInput(stream, "utf-8") })
        }
    }

    fun getDrawable(resources: Resources, resourceId: Int): Drawable? =
        resourceId.takeIf { it > 0 }?.let { resources.getDrawableOrNull(it, null) }

    private fun Resources.withDisplayedNightMode(): Resources {
        val displayedNight = displayedNightMode ?: return this
        val updated = Configuration(configuration)
        val resolvedNight =
            updated.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (resolvedNight == displayedNight) return this

        updated.uiMode = (updated.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (displayedNight) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        @Suppress("DEPRECATION")
        return Resources(assets, displayMetrics, updated)
    }
}
