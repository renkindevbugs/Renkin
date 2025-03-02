package dev.alembiconsProject.alembicons.icon.creator

import dev.alembiconsProject.alembicons.data.InstalledApplication
import dev.alembiconsProject.alembicons.drawable.ResourceDrawable

typealias ApplicationDrawables = Map<InstalledApplication, ResourceDrawable>

class IconPackContainer(val iconPackName: String, private val drawables: ApplicationDrawables) {
    fun getApplicationIcon(packageName: String): ResourceDrawable? {
        val item = drawables.entries.find { it.key.packageName == packageName }

        if (item != null) {
            return item.value
        }

        return null
    }
}