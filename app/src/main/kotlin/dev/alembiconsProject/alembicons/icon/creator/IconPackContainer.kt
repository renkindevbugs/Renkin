package dev.alembiconsProject.alembicons.icon.creator

import dev.alembiconsProject.alembicons.data.InstalledApplication
import dev.alembiconsProject.alembicons.drawable.ResourceDrawable

typealias ApplicationDrawables = Map<InstalledApplication, ResourceDrawable>

class IconPackContainer(val iconPackName: String, drawables: ApplicationDrawables) {
    // Index by package name once, so a bulk build (every app × every pack entry)
    // is O(1) per lookup instead of a linear scan over the whole pack.
    private val byPackageName: Map<String, ResourceDrawable> = buildMap {
        for ((application, drawable) in drawables) {
            // Keep the first entry on a duplicate package name, matching the
            // previous find-first behaviour.
            if (!containsKey(application.packageName)) {
                put(application.packageName, drawable)
            }
        }
    }

    fun getApplicationIcon(packageName: String): ResourceDrawable? {
        return byPackageName[packageName]
    }
}