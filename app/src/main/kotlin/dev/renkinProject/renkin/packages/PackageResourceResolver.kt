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
import dev.renkinProject.renkin.extension.getXmlOrNull
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Resolves resources owned by another installed package.
 *
 * This is deliberately independent from app and icon-pack discovery: renderers that only need
 * a drawable or XML resource should not have to construct the full [ApplicationManager].
 */
internal class PackageResourceResolver(context: Context) {
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

    fun getDrawable(packageName: String, resourceId: Int): Drawable? =
        getResources(packageName)?.let { resources -> getDrawable(resources, resourceId) }

    fun getDrawableByName(packageName: String, name: String): Drawable? {
        val resources = getResources(packageName) ?: return null
        val resourceId = resources.getIdentifierByName(name, "drawable", packageName)
        return getDrawable(resources, resourceId)
    }

    /** Cheap existence check: resolves the resource id without decoding the drawable. */
    fun hasDrawable(packageName: String, name: String): Boolean {
        val resources = getResources(packageName) ?: return false
        return resources.getIdentifierByName(name, "drawable", packageName) > 0
    }

    fun getResourceType(packageName: String, resourceId: Int): String? = try {
        packageManager.getResourcesForApplication(packageName).getResourceTypeName(resourceId)
    } catch (_: Resources.NotFoundException) {
        null
    }

    fun getResourceXml(packageName: String, resourceId: Int): XmlResourceParser? =
        getResources(packageName)?.getXmlOrNull(resourceId)

    fun getXml(resources: Resources, packageName: String, name: String): XmlPullParser? {
        val resourceId = resources.getIdentifierByName(name, "xml", packageName)
        return if (resourceId > 0) resources.getXml(resourceId) else null
    }

    fun getAssetXml(resources: Resources, name: String): XmlPullParser? {
        val assets = resources.assets.list("")
        if (assets == null || !assets.contains(name)) return null

        return XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(resources.assets.open(name), "utf-8")
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
