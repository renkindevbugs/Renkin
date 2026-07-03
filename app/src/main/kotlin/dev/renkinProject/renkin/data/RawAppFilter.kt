package dev.renkinProject.renkin.data

abstract class RawElement

data class RawItem(
    val component: String,
    val drawableLink: String
): RawElement()

data class RawCalendar(
    val component: String,
    val prefix: String
): RawElement()

data class RawDynamicClock(
    val drawableLink: String,
    val defaultHour: String,
    val defaultMinute: String,
    val hourLayerIndex: String,
    val minuteLayerIndex: String
): RawElement()

/**
 * The classic icon-pack fallback styling for apps the pack doesn't theme: one of [backs] is drawn
 * behind the (down-[scale]d) original icon, [upon] is drawn on top, and [mask] clips the result —
 * giving unmatched apps the pack's uniform shape/background. Any field may be empty (a pack can
 * declare only some). [isEmpty] is true when the pack declares no fallback at all.
 */
data class IconPackFallback(
    val backs: List<String> = emptyList(),
    val mask: String? = null,
    val upon: String? = null,
    val scale: Float = 1f
) {
    val isEmpty: Boolean get() = backs.isEmpty() && mask == null && upon == null
}

fun InstalledApplication.toComponentInfo(): String {
    return "ComponentInfo{${this.packageName}/${this.activityName}}"
}