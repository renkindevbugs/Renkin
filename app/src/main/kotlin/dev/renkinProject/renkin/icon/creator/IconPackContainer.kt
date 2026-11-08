package dev.renkinProject.renkin.icon.creator

import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.drawable.ResourceDrawable

typealias ApplicationDrawables = Map<InstalledApplication, ResourceDrawable>

class IconPackContainer(
    val iconPackName: String,
    private val lookup: (InstalledApplication) -> ResourceDrawable?
) {
    constructor(iconPackName: String, drawables: ApplicationDrawables) : this(iconPackName, byComponent(drawables))

    fun getApplicationIcon(application: InstalledApplication): ResourceDrawable? = lookup(application)

    private companion object {
        // Packs map icons by the complete ComponentInfo, not merely the package. Keeping that
        // identity in the O(1) index lets two launcher activities of one package receive the
        // different artwork their appfilter declares.
        fun byComponent(drawables: ApplicationDrawables): (InstalledApplication) -> ResourceDrawable? {
            val byComponent = drawables.mapKeys { (application, _) -> application.toComponentInfo() }
            return { application -> byComponent[application.toComponentInfo()] }
        }
    }
}
