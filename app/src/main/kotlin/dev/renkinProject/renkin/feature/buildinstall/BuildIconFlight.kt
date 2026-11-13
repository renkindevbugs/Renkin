package dev.renkinProject.renkin.feature.buildinstall

import android.content.Intent
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.ExportThemedKey
import dev.renkinProject.renkin.data.getBackgroundColor
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.packages.supportDynamicColors
import dev.renkinProject.renkin.ui.IconFlightEasing

private const val MAX_FLIGHTS = 24
private const val ICON_FLIGHT_MILLIS = 1500f
private const val ICON_STAGGER_MILLIS = 24f
private const val CHROME_FADE_MILLIS = 180f

/**
 * One flight path for both windows. Pack preview starts it on the Build tap; the home screen
 * resumes it from the same tap time once it is visible again, so the icons never jump.
 */
private object BuildIconPath {
    fun totalMillis(count: Int) = ICON_FLIGHT_MILLIS + ICON_STAGGER_MILLIS * (count - 1).coerceAtLeast(0)

    private fun raw(elapsed: Float, index: Int) = ((elapsed - index * ICON_STAGGER_MILLIS) / ICON_FLIGHT_MILLIS).coerceIn(0f, 1f)

    fun landed(elapsed: Float, index: Int) = raw(elapsed, index) >= 1f

    fun rect(start: Rect, target: Offset, targetSize: Float, elapsed: Float, index: Int): Rect {
        val fraction = IconFlightEasing.transform(raw(elapsed, index))
        val end = Rect(target, Size.Zero).inflate(targetSize / 2f)
        val control = Offset((start.center.x + end.center.x) / 2f, minOf(start.center.y, end.center.y) - start.height * 0.4f)
        val u = 1f - fraction
        val centre = start.center * (u * u) + control * (2f * u * fraction) + end.center * (fraction * fraction)
        val width = start.width + (end.width - start.width) * fraction
        val height = start.height + (end.height - start.height) * fraction
        return Rect(centre - Offset(width / 2f, height / 2f), Size(width, height))
    }

    fun alpha(elapsed: Float, index: Int) = 1f - ((raw(elapsed, index) - 0.85f) / 0.15f).coerceIn(0f, 1f)
}

/** Where the icons land: the FAB's trailing end, which stays put when "Building…" widens it. */
internal fun buildFabTarget(fab: Rect) = Offset(fab.right - fab.height / 2f, fab.center.y)

/** The flight as it crosses windows: tap time, target and the icons' start bounds (window coordinates). */
internal class BuildIconStarts(val keys: List<String>, val bounds: List<Rect>, val target: Offset, val startedAtUptime: Long) {
    fun writeTo(intent: Intent): Intent = intent
        .putStringArrayListExtra(EXTRA_KEYS, ArrayList(keys))
        .putExtra(EXTRA_BOUNDS, bounds.flatMap { listOf(it.left, it.top, it.right, it.bottom) }.toFloatArray())
        .putExtra(EXTRA_TARGET, floatArrayOf(target.x, target.y))
        .putExtra(EXTRA_STARTED, startedAtUptime)

    companion object {
        private const val EXTRA_KEYS = "buildIconKeys"
        private const val EXTRA_BOUNDS = "buildIconBounds"
        private const val EXTRA_TARGET = "buildIconTarget"
        private const val EXTRA_STARTED = "buildIconStarted"

        fun from(intent: Intent?): BuildIconStarts? {
            val keys = intent?.getStringArrayListExtra(EXTRA_KEYS) ?: return null
            val values = intent.getFloatArrayExtra(EXTRA_BOUNDS) ?: return null
            val target = intent.getFloatArrayExtra(EXTRA_TARGET)?.takeIf { it.size == 2 } ?: return null
            if (values.size != keys.size * 4) return null
            val bounds = keys.indices.map { i -> Rect(values[i * 4], values[i * 4 + 1], values[i * 4 + 2], values[i * 4 + 3]) }
            return BuildIconStarts(keys, bounds, Offset(target[0], target[1]), intent.getLongExtra(EXTRA_STARTED, SystemClock.uptimeMillis()))
        }
    }
}

/** Puts the home FAB's landing point into the intent that opens Pack preview. */
internal fun Intent.putBuildFabTarget(target: Offset): Intent = putExtra(EXTRA_FAB_TARGET, floatArrayOf(target.x, target.y))

internal fun Intent.buildFabTarget(): Offset? =
    getFloatArrayExtra(EXTRA_FAB_TARGET)?.takeIf { it.size == 2 }?.let { Offset(it[0], it[1]) }

private const val EXTRA_FAB_TARGET = "buildFabTarget"

/**
 * Pack preview side: remembers where each preview icon sits, and on Build flies the visible ones
 * toward the home FAB at once, while the preview closes over the home screen.
 */
@Stable
internal class BuildIconSource(private val target: Offset) {
    private var gridViewport: Rect? = null
    // Only read on the Build tap, never while drawing.
    private val iconBounds = HashMap<String, Rect>()
    private var origin by mutableStateOf(Offset.Zero)
    internal val elapsed = Animatable(0f)
    // The preview publishes its current apps here so the tap keeps their on-screen order.
    var apps: List<PackageInfoStruct> = emptyList()

    var flight by mutableStateOf<BuildIconStarts?>(null)
        private set
    internal var flightApps by mutableStateOf<List<PackageInfoStruct>>(emptyList())
        private set

    fun onOverlayPlaced(coordinates: LayoutCoordinates) { origin = coordinates.boundsInWindow().topLeft }
    fun onGridPlaced(coordinates: LayoutCoordinates) { gridViewport = coordinates.boundsInWindow() }
    fun onIconPlaced(key: String, coordinates: LayoutCoordinates) { iconBounds[key] = coordinates.boundsInWindow() }
    fun onIconRemoved(key: String) { iconBounds.remove(key) }

    fun launch(): BuildIconStarts {
        val viewport = gridViewport
        val visible = apps.mapNotNull { app ->
            iconBounds[app.key]?.takeIf { viewport == null || it.overlaps(viewport) }?.let { app to it }
        }.take(MAX_FLIGHTS)
        val started = BuildIconStarts(visible.map { it.first.key }, visible.map { it.second }, target, SystemClock.uptimeMillis())
        flightApps = visible.map { it.first }
        flight = started
        return started
    }

    internal suspend fun fly(count: Int) {
        val total = BuildIconPath.totalMillis(count)
        elapsed.animateTo(total, tween(total.toInt(), easing = LinearEasing))
    }

    // The preview chrome clears quickly so the icons cross the wallpaper toward the home screen.
    val chromeAlpha: Float get() = if (flight == null) 1f else 1f - (elapsed.value / CHROME_FADE_MILLIS).coerceIn(0f, 1f)

    internal fun local(rect: Rect) = rect.translate(-origin)
    internal fun local(point: Offset) = point - origin
}

/** Draws the departing icons above Pack preview, which hides its own grid once they leave. */
@Composable
internal fun BuildIconDepartureOverlay(source: BuildIconSource, iconBackground: Color?) {
    val flight = source.flight
    val painters = source.flightApps.map { it.createdIcon?.getPainter() }
    if (flight != null) LaunchedEffect(flight) { source.fly(flight.keys.size) }
    Canvas(Modifier.fillMaxSize().onGloballyPositioned(source::onOverlayPlaced)) {
        val started = source.flight ?: return@Canvas
        drawIconFlights(started, painters, iconBackground, source.elapsed.value, source::local, source.local(started.target))
    }
}

/** The background themed packs put behind each icon, as Pack preview shows it. */
@Composable
internal fun previewIconBackground(preferences: Preferences): Color? = when {
    !preferences.getBooleanValue(ExportThemedKey) -> null
    supportDynamicColors() -> colorResource(R.color.icon_background_color)
    else -> preferences.getBackgroundColor(LocalContext.current)
}

/**
 * Home side: picks the flight up at the moment the home screen becomes visible again (time since
 * the tap), lands it in the Build FAB and gives the FAB a small bump.
 */
@Stable
internal class BuildIconLanding(private val appsFor: (List<String>) -> List<PackageInfoStruct>) {
    internal val elapsed = Animatable(0f)
    internal val bump = Animatable(1f)
    internal var fabWindow by mutableStateOf(Rect.Zero)
    var starts by mutableStateOf<BuildIconStarts?>(null)
        private set
    internal var apps by mutableStateOf<List<PackageInfoStruct?>>(emptyList())
        private set

    fun catch(caught: BuildIconStarts) {
        val found = appsFor(caught.keys)
        apps = caught.keys.map { key -> found.firstOrNull { it.key == key } }
        starts = caught
    }

    internal suspend fun land() {
        val caught = starts ?: return
        val total = BuildIconPath.totalMillis(caught.keys.size)
        val now = (SystemClock.uptimeMillis() - caught.startedAtUptime).toFloat().coerceIn(0f, total)
        elapsed.snapTo(now)
        elapsed.animateTo(total, tween((total - now).toInt(), easing = LinearEasing))
        bump.animateTo(1.06f, tween(90))
        bump.animateTo(1f, tween(160, easing = IconFlightEasing))
        // Cleared last: clearing it leaves the LaunchedEffect that runs this landing.
        starts = null
    }

    internal fun local(rect: Rect) = rect.translate(-fabWindow.topLeft)
    internal fun local(point: Offset) = point - fabWindow.topLeft
}

@Composable
internal fun rememberBuildIconLanding(viewModel: MainViewModel): BuildIconLanding {
    val landing = remember(viewModel) {
        BuildIconLanding { keys ->
            val wanted = keys.toHashSet()
            viewModel.applicationState.value.applications.filter { it.key in wanted }
        }
    }
    val caught = landing.starts
    if (caught != null) LaunchedEffect(caught) { landing.land() }
    return landing
}

/**
 * Draws the landing icons from the FAB's own draw pass, outside its bounds and above the home
 * content, and applies the arrival bump. Painters are resolved here because they are composable.
 */
@Composable
internal fun Modifier.buildIconLanding(landing: BuildIconLanding, iconBackground: Color?): Modifier {
    val painters = landing.apps.map { it?.createdIcon?.getPainter() }
    return this
        .onGloballyPositioned { landing.fabWindow = it.boundsInWindow() }
        .drawWithContent {
            drawContent()
            val caught = landing.starts ?: return@drawWithContent
            drawIconFlights(caught, painters, iconBackground, landing.elapsed.value, landing::local, landing.local(caught.target))
        }
        .graphicsLayer {
            scaleX = landing.bump.value
            scaleY = landing.bump.value
        }
}

private fun DrawScope.drawIconFlights(
    flight: BuildIconStarts,
    painters: List<Painter?>,
    background: Color?,
    elapsed: Float,
    local: (Rect) -> Rect,
    target: Offset
) {
    val targetSize = 24.dp.toPx()
    flight.bounds.forEachIndexed { index, window ->
        val painter = painters.getOrNull(index) ?: return@forEachIndexed
        if (BuildIconPath.landed(elapsed, index)) return@forEachIndexed
        val rect = BuildIconPath.rect(local(window), target, targetSize, elapsed, index)
        drawFlyingIcon(rect, painter, background, BuildIconPath.alpha(elapsed, index))
    }
}

private fun DrawScope.drawFlyingIcon(rect: Rect, painter: Painter, background: Color?, alpha: Float) {
    if (rect.width <= 0f || alpha <= 0f) return
    val shape = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(rect.width * 0.25f))) }
    clipPath(shape) {
        background?.let { drawRect(it, rect.topLeft, rect.size, alpha = alpha) }
        translate(rect.left, rect.top) { with(painter) { draw(rect.size, alpha = alpha) } }
    }
}
