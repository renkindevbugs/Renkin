package dev.renkinProject.renkin.icon.creator

import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.drawable.ResourceDrawable

typealias ApplicationDrawables = Map<InstalledApplication, ResourceDrawable>

class IconPackContainer(val iconPackName: String, drawables: ApplicationDrawables) {
    // Packs map icons by the complete ComponentInfo, not merely the package. Keeping that
    // identity in the O(1) index lets two launcher activities of one package receive the
    // different artwork their appfilter declares.
    private val byComponent: Map<String, ResourceDrawable> =
        drawables.mapKeys { (application, _) -> application.toComponentInfo() }

    fun getApplicationIcon(application: InstalledApplication): ResourceDrawable? =
        byComponent[application.toComponentInfo()]
}
