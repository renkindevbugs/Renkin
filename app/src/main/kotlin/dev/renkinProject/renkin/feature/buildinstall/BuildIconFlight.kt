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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
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
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.floor
import kotlin.math.sin

private const val MAX_FLIGHTS = 64
private const val CHROME_FADE_MILLIS = 180f

// Renkin Pack symbol colours: copper into gold into pale gold.
private val SwirlCopper = Color(0xFFC85028)
private val SwirlGold = Color(0xFFD5B15B)
private val SwirlPale = Color(0xFFFFE8A0)
private val SwirlWhite = Color(0xFFFFF8E6)

/** A stable pseudo-random value in 0..1, identical in both windows so no grain jumps when they swap. */
private fun noise(seed: Int): Float {
    val x = sin(seed * 12.9898f) * 43758.547f
    return x - floor(x)
}

private fun dustColor(pick: Float) = when {
    pick < 0.4f -> SwirlGold
    pick < 0.75f -> SwirlPale
    else -> SwirlCopper
}

/**
 * The whole build animation as a function of time since the Build tap, shared by both windows:
 * Pack preview draws its first moments, and the home screen resumes it at the same instant once
 * visible, so nothing jumps when the windows swap.
 */
private object BuildSwirl {
    // Icons hold still briefly so the home screen is already visible when they shatter.
    private const val ICON_HOLD = 200f
    private const val ICON_STAGGER = 15f
    // Many icons (tablets) share this span so the last one does not start late.
    private const val MAX_STAGGER_SPAN = 360f
    // How long one icon takes to come apart, its fragments breaking off one after another.
    private const val ICON_DETACH_SPREAD = 490f
    private const val FRAGMENT_SHAKE = 90f
    private const val DUST_FLIGHT = 825f
    private const val DUST_TURNS = 1.25f
    private const val GALAXY_IN_START = 225f
    private const val GALAXY_IN_END = 1125f
    private const val SPIN_FAST_UNTIL = 1350f
    private const val SPIN_STOP = 2400f
    private const val CONDENSE_START = 1875f
    private const val CONDENSE_END = 2475f
    private const val FLOWER_IN_START = 1725f
    private const val FLOWER_IN_END = 2400f
    private const val CORE_OUT_START = 2175f
    private const val CORE_OUT_END = 2550f
    private const val POP_START = 2850f
    const val TOTAL = 3150f
    private const val DEGREES_PER_MILLI = 0.45f

    private fun ramp(t: Float, from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)
    private fun ease(u: Float) = IconFlightEasing.transform(u)

    /** Fast spin, then an even slow-down to rest at [SPIN_STOP]. */
    fun angle(t: Float): Float {
        if (t <= SPIN_FAST_UNTIL) return DEGREES_PER_MILLI * t
        val span = SPIN_STOP - SPIN_FAST_UNTIL
        val u = ramp(t, SPIN_FAST_UNTIL, SPIN_STOP)
        return DEGREES_PER_MILLI * (SPIN_FAST_UNTIL + span * (u - u * u / 2f))
    }

    val restAngle = angle(SPIN_STOP)

    private fun galaxyIn(t: Float) = ease(ramp(t, GALAXY_IN_START, GALAXY_IN_END))
    private fun condense(t: Float) = ease(ramp(t, CONDENSE_START, CONDENSE_END))

    /** The arms fade while the galaxy condenses into the symbol. */
    fun arms(t: Float) = galaxyIn(t) * (1f - condense(t))
    fun galaxyScale(t: Float) = 1f - 0.85f * condense(t)
    /** The bright core outlives the arms and fades last, uncovering the finished symbol. */
    fun core(t: Float) = galaxyIn(t) * (0.75f + 0.25f * condense(t)) * (1f - ease(ramp(t, CORE_OUT_START, CORE_OUT_END)))
    // Small while the arms show, it swells only to hide the symbol forming inside.
    fun coreScale(t: Float) = 0.3f + 0.7f * condense(t)
    fun symbolGlow(t: Float) = flowerAlpha(t) * (1f - pop(t))
    fun flowerAlpha(t: Float) = ease(ramp(t, FLOWER_IN_START, FLOWER_IN_END))
    fun pop(t: Float) = ease(ramp(t, POP_START, TOTAL))

    private fun shatterStart(index: Int, count: Int) = ICON_HOLD + index * minOf(ICON_STAGGER, MAX_STAGGER_SPAN / count)
    fun shattered(t: Float, index: Int, count: Int) = t >= shatterStart(index, count)

    /**
     * Flight progress of one icon fragment: negative while it still sits in the icon, 0..1 on its
     * way to the core, above 1 once absorbed. Fragments break off from the top corner diagonally.
     */
    fun fragmentProgress(t: Float, index: Int, count: Int, row: Int, column: Int, grid: Int, seed: Int): Float {
        val order = (row + (grid - 1 - column) + noise(seed) * 3f) / (2f * (grid - 1) + 3f)
        return (t - shatterStart(index, count) - ICON_DETACH_SPREAD * order) / DUST_FLIGHT
    }

    /** Below 0 when the fragment starts trembling just before it breaks off. */
    fun isShaking(progress: Float) = progress > -FRAGMENT_SHAKE / DUST_FLIGHT && progress < 0f

    /** The fragment spirals in with the galaxy's turn, speeding up as it nears the core. */
    fun dustPosition(start: Offset, centre: Offset, u: Float): Offset {
        val pull = u * u * (3f - 2f * u)
        val from = start - centre
        val radius = from.getDistance() * (1f - pull)
        val angle = atan2(from.y, from.x) + DUST_TURNS * 2f * PI.toFloat() * pull
        return centre + Offset(cos(angle) * radius, sin(angle) * radius)
    }
}

/** The animation as it crosses windows: tap time, centre and the icons' start bounds (window coordinates). */
internal class BuildIconStarts(val keys: List<String>, val bounds: List<Rect>, val centre: Offset, val startedAtUptime: Long) {
    fun writeTo(intent: Intent): Intent = intent
        .putStringArrayListExtra(EXTRA_KEYS, ArrayList(keys))
        .putExtra(EXTRA_BOUNDS, bounds.flatMap { listOf(it.left, it.top, it.right, it.bottom) }.toFloatArray())
        .putExtra(EXTRA_CENTRE, floatArrayOf(centre.x, centre.y))
        .putExtra(EXTRA_STARTED, startedAtUptime)

    companion object {
        private const val EXTRA_KEYS = "buildIconKeys"
        private const val EXTRA_BOUNDS = "buildIconBounds"
        private const val EXTRA_CENTRE = "buildIconCentre"
        private const val EXTRA_STARTED = "buildIconStarted"

        fun from(intent: Intent?): BuildIconStarts? {
            val keys = intent?.getStringArrayListExtra(EXTRA_KEYS) ?: return null
            val values = intent.getFloatArrayExtra(EXTRA_BOUNDS) ?: return null
            val centre = intent.getFloatArrayExtra(EXTRA_CENTRE)?.takeIf { it.size == 2 } ?: return null
            if (values.size != keys.size * 4) return null
            val bounds = keys.indices.map { i -> Rect(values[i * 4], values[i * 4 + 1], values[i * 4 + 2], values[i * 4 + 3]) }
            return BuildIconStarts(keys, bounds, Offset(centre[0], centre[1]), intent.getLongExtra(EXTRA_STARTED, SystemClock.uptimeMillis()))
        }
    }
}

private const val EXTRA_ANIMATE_BUILD = "animateBuild"

/** Asks Pack preview to hand the build animation to the home screen (Reduce icon motion is off). */
internal fun Intent.putAnimateBuild(): Intent = putExtra(EXTRA_ANIMATE_BUILD, true)
internal fun Intent.animatesBuild(): Boolean = getBooleanExtra(EXTRA_ANIMATE_BUILD, false)

/**
 * Pack preview side: remembers where each preview icon sits, and on Build starts the animation
 * at once while the preview closes over the home screen.
 */
@Stable
internal class BuildIconSource {
    private var gridViewport: Rect? = null
    // Only read on the Build tap, never while drawing.
    private val iconBounds = HashMap<String, Rect>()
    private var overlay by mutableStateOf(Rect.Zero)
    internal val elapsed = Animatable(0f)
    // The preview publishes its current apps here so the tap keeps their on-screen order.
    var apps: List<PackageInfoStruct> = emptyList()

    var flight by mutableStateOf<BuildIconStarts?>(null)
        private set
    internal var flightApps by mutableStateOf<List<PackageInfoStruct>>(emptyList())
        private set

    // The preview chrome clears quickly so the animation plays over the wallpaper.
    val chromeAlpha: Float get() = if (flight == null) 1f else 1f - (elapsed.value / CHROME_FADE_MILLIS).coerceIn(0f, 1f)

    // The grid hides only once the overlay animates, so the frame that composes the overlay icons still shows them.
    val isFlying: Boolean get() = flight != null && elapsed.value > 0f

    fun onOverlayPlaced(coordinates: LayoutCoordinates) { overlay = coordinates.boundsInWindow() }
    fun onGridPlaced(coordinates: LayoutCoordinates) { gridViewport = coordinates.boundsInWindow() }
    fun onIconPlaced(key: String, coordinates: LayoutCoordinates) { iconBounds[key] = coordinates.boundsInWindow() }
    fun onIconRemoved(key: String) { iconBounds.remove(key) }

    fun launch(): BuildIconStarts {
        val viewport = gridViewport
        val visible = apps.mapNotNull { app ->
            iconBounds[app.key]?.takeIf { viewport == null || it.overlaps(viewport) }?.let { app to it }
        }.take(MAX_FLIGHTS)
        val started = BuildIconStarts(visible.map { it.first.key }, visible.map { it.second }, overlay.center, SystemClock.uptimeMillis())
        flightApps = visible.map { it.first }
        flight = started
        return started
    }

    internal suspend fun play() = elapsed.animateTo(BuildSwirl.TOTAL, tween(BuildSwirl.TOTAL.toInt(), easing = LinearEasing))

    internal fun local(rect: Rect) = rect.translate(-overlay.topLeft)
    internal fun local(point: Offset) = point - overlay.topLeft
}

/** Draws the icons streaming into the spinning light above Pack preview, which hides its own grid. */
@Composable
internal fun BuildIconDepartureOverlay(source: BuildIconSource, iconBackground: Color?) {
    val flight = source.flight
    val painters = source.flightApps.map { it.createdIcon?.getPainter() }
    val fragments = rememberFragments(source.flightApps, painters, iconBackground)
    val galaxyRadius = galaxyRadius()
    if (flight != null) LaunchedEffect(flight) { source.play() }
    Canvas(Modifier.fillMaxSize().onGloballyPositioned(source::onOverlayPlaced)) {
        val started = source.flight ?: return@Canvas
        val t = source.elapsed.value
        val centre = source.local(started.centre)
        drawIconStreams(started, painters, fragments, iconBackground, t, source::local, centre)
        drawGalaxyArms(centre, t, galaxyRadius)
        drawGalaxyCore(centre, t)
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
 * Home side: resumes the animation at the moment the home screen becomes visible (time since the
 * tap). The light slows down, the Renkin Pack symbol settles in it and pops into the Build FAB.
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
        val now = (SystemClock.uptimeMillis() - caught.startedAtUptime).toFloat().coerceIn(0f, BuildSwirl.TOTAL)
        elapsed.snapTo(now)
        elapsed.animateTo(BuildSwirl.TOTAL, tween((BuildSwirl.TOTAL - now).toInt(), easing = LinearEasing))
        bump.animateTo(1.08f, tween(90))
        bump.animateTo(1f, tween(180, easing = IconFlightEasing))
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
 * Draws the animation from the FAB's own draw pass, outside its bounds and above the home content,
 * and applies the arrival bump. Painters are resolved here because they are composable.
 */
@Composable
internal fun Modifier.buildIconLanding(landing: BuildIconLanding, iconBackground: Color?): Modifier {
    val painters = landing.apps.map { it?.createdIcon?.getPainter() }
    val fragments = rememberFragments(landing.apps, painters, iconBackground)
    val galaxyRadius = galaxyRadius()
    val symbol = painterResource(R.drawable.renkinpack_ic_launcher_foreground)
    return this
        .onGloballyPositioned { landing.fabWindow = it.boundsInWindow() }
        .drawWithContent {
            drawContent()
            val caught = landing.starts ?: return@drawWithContent
            val t = landing.elapsed.value
            val centre = landing.local(caught.centre)
            val iconSlot = if (size.width > size.height * 1.3f) Offset(16.dp.toPx() + 12.dp.toPx(), size.height / 2f)
                else Offset(size.width / 2f, size.height / 2f)
            drawIconStreams(caught, painters, fragments, iconBackground, t, landing::local, centre)
            drawGalaxyArms(centre, t, galaxyRadius)
            drawPackSymbol(symbol, centre, iconSlot, t)
            drawGalaxyCore(centre, t)
        }
        .graphicsLayer {
            scaleX = landing.bump.value
            scaleY = landing.bump.value
        }
}

private class IconFragments(val grid: Int, val cells: List<Color?>)

// Fewer, larger fragments when many icons shatter at once (tablets), to keep the frame cheap.
private fun fragmentGrid(iconCount: Int) = if (iconCount > 30) 7 else 9
private const val FRAGMENT_SAMPLES = 4

/**
 * The icon as a coarse grid of its own colours (null = empty cell), so it can shatter into them.
 * Each cell averages several pixels: thin line icons would otherwise sample as empty and vanish.
 */
private fun sampleFragments(painter: Painter, background: Color?, grid: Int): IconFragments {
    val side = grid * FRAGMENT_SAMPLES
    val bitmap = ImageBitmap(side, side)
    val size = Size(side.toFloat(), side.toFloat())
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bitmap), size) {
        clipPath(Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(size.width * 0.25f))) }) {
            background?.let { drawRect(it) }
            with(painter) { draw(size) }
        }
    }
    val pixels = bitmap.toPixelMap()
    val cells = List(grid * grid) { cell ->
        var red = 0f
        var green = 0f
        var blue = 0f
        var coverage = 0f
        for (dy in 0 until FRAGMENT_SAMPLES) for (dx in 0 until FRAGMENT_SAMPLES) {
            val pixel = pixels[(cell % grid) * FRAGMENT_SAMPLES + dx, (cell / grid) * FRAGMENT_SAMPLES + dy]
            red += pixel.red * pixel.alpha
            green += pixel.green * pixel.alpha
            blue += pixel.blue * pixel.alpha
            coverage += pixel.alpha
        }
        if (coverage < 0.05f * FRAGMENT_SAMPLES * FRAGMENT_SAMPLES) null
        else Color(red / coverage, green / coverage, blue / coverage, (coverage / (FRAGMENT_SAMPLES * FRAGMENT_SAMPLES) * 2.5f).coerceAtMost(1f))
    }
    return IconFragments(grid, cells)
}

@Composable
private fun rememberFragments(apps: List<Any?>, painters: List<Painter?>, background: Color?): List<IconFragments?> =
    remember(apps, background) {
        val grid = fragmentGrid(painters.size)
        painters.map { painter -> painter?.let { sampleFragments(it, background, grid) } }
    }

/** Big enough to read as a galaxy rather than a glow: it spans most of the screen's short side. */
@Composable
private fun galaxyRadius(): Float {
    val configuration = LocalConfiguration.current
    return with(LocalDensity.current) { (minOf(configuration.screenWidthDp, configuration.screenHeightDp) * 0.58f).dp.toPx() }
}

private fun DrawScope.drawIconStreams(
    flight: BuildIconStarts,
    painters: List<Painter?>,
    fragments: List<IconFragments?>,
    background: Color?,
    t: Float,
    local: (Rect) -> Rect,
    centre: Offset
) {
    val count = flight.bounds.size
    flight.bounds.forEachIndexed { index, window ->
        val icon = local(window)
        val painter = painters.getOrNull(index) ?: return@forEachIndexed
        val iconFragments = fragments.getOrNull(index)
        if (iconFragments == null || !BuildSwirl.shattered(t, index, count)) {
            drawStreamIcon(icon, painter, background)
            return@forEachIndexed
        }
        drawIconFragments(index, count, icon, iconFragments, t, centre)
    }
}

/** Each fragment keeps the icon's own colour as it breaks off, then turns to gold dust near the core. */
private fun DrawScope.drawIconFragments(index: Int, count: Int, icon: Rect, fragments: IconFragments, t: Float, centre: Offset) {
    val grid = fragments.grid
    val cell = icon.width / grid
    val grain = 3.dp.toPx()
    fragments.cells.forEachIndexed { slot, color ->
        if (color == null) return@forEachIndexed
        val row = slot / grid
        val column = slot % grid
        val seed = (index * grid * grid + slot) * 7
        val u = BuildSwirl.fragmentProgress(t, index, count, row, column, grid, seed)
        if (u >= 1f) return@forEachIndexed
        val home = Offset(icon.left + (column + 0.5f) * cell, icon.top + (row + 0.5f) * cell)
        if (u < 0f) {
            val shake = if (BuildSwirl.isShaking(u)) {
                val beat = (t / 30f).toInt()
                Offset(noise(seed + beat) - 0.5f, noise(seed + beat + 1) - 0.5f) * (cell * 0.3f)
            } else Offset.Zero
            drawRect(color, home + shake - Offset(cell / 2f, cell / 2f), Size(cell, cell))
            return@forEachIndexed
        }
        val position = BuildSwirl.dustPosition(home, centre, u)
        val gold = ((u - 0.25f) / 0.55f).coerceIn(0f, 1f)
        val tint = lerp(color, dustColor(noise(seed + 3)), gold)
        val side = cell + (grain - cell) * (u / 0.4f).coerceAtMost(1f)
        val fade = 1f - ((u - 0.85f) / 0.15f).coerceIn(0f, 1f)
        val alpha = (color.alpha + (1f - color.alpha) * gold) * fade
        if (gold > 0f) drawCircle(tint.copy(alpha = 0.3f * gold * fade), side * 1.8f, position)
        drawRect(tint.copy(alpha = alpha), position - Offset(side / 2f, side / 2f), Size(side, side))
    }
}

private fun DrawScope.drawStreamIcon(rect: Rect, painter: Painter, background: Color?) {
    if (rect.width <= 0f) return
    val shape = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(rect.width * 0.25f))) }
    clipPath(shape) {
        background?.let { drawRect(it, rect.topLeft, rect.size) }
        translate(rect.left, rect.top) { with(painter) { draw(rect.size) } }
    }
}

// Five trails of light, one per petal of the Renkin Pack symbol.
private const val GALAXY_ARMS = 5
private const val GALAXY_ARM_GLOWS = 34
private const val GALAXY_ARM_GRAINS = 150
private const val GALAXY_FIELD_STARS = 90
// How far each trail winds around the core. Much more and neighbouring trails merge into rings.
private const val GALAXY_ARM_WIND_DEGREES = 330f
private const val GALAXY_INNER_RADIUS = 0.06f
private const val GALAXY_SPARKLES = 10
private const val GALAXY_FLATTEN = 0.9f

/** A point on trail [arm] at [along] (0 = core, 1 = rim): a logarithmic spiral trailing the spin. */
private fun spiralPoint(centre: Offset, radius: Float, spin: Float, arm: Int, along: Float, drift: Float = 0f): Offset {
    val distance = radius * GALAXY_INNER_RADIUS * exp(ln(1f / GALAXY_INNER_RADIUS) * along) + drift
    val degrees = spin + arm * 360f / GALAXY_ARMS - along * GALAXY_ARM_WIND_DEGREES
    val angle = Math.toRadians(degrees.toDouble())
    return centre + Offset((cos(angle) * distance).toFloat(), (sin(angle) * distance * GALAXY_FLATTEN).toFloat())
}

/** White near the core, through pale gold and gold, to copper at the rim. */
private fun armColor(along: Float): Color = when {
    along < 0.25f -> lerp(SwirlWhite, SwirlPale, along / 0.25f)
    along < 0.65f -> lerp(SwirlPale, SwirlGold, (along - 0.25f) / 0.4f)
    else -> lerp(SwirlGold, SwirlCopper, (along - 0.65f) / 0.35f)
}

/**
 * A whirling galaxy of gold light. Each of the five trails is light, not a line: overlapping soft
 * glows that widen toward the rim, filled with drifting gold dust, among twinkling stars.
 */
private fun DrawScope.drawGalaxyArms(centre: Offset, t: Float, fullRadius: Float) {
    val strength = BuildSwirl.arms(t)
    if (strength <= 0f) return
    val radius = fullRadius * BuildSwirl.galaxyScale(t)
    val spin = BuildSwirl.angle(t)
    for (arm in 0 until GALAXY_ARMS) for (i in 0 until GALAXY_ARM_GLOWS) {
        val along = (i + 0.5f) / GALAXY_ARM_GLOWS
        val position = spiralPoint(centre, radius, spin, arm, along)
        val glow = radius * (0.035f + 0.11f * along)
        val color = armColor(along)
        val alpha = strength * (1f - 0.65f * along)
        drawCircle(color.copy(alpha = 0.05f * alpha), glow * 1.7f, position)
        drawCircle(color.copy(alpha = 0.08f * alpha), glow, position)
        drawCircle(color.copy(alpha = 0.14f * alpha), glow * 0.45f, position)
    }
    val grainRadius = 1.6.dp.toPx()
    for (arm in 0 until GALAXY_ARMS) for (i in 0 until GALAXY_ARM_GRAINS) {
        val seed = 50000 + (arm * GALAXY_ARM_GRAINS + i) * 7
        val along = (i + noise(seed)) / GALAXY_ARM_GRAINS
        // Bell-shaped spread: most dust hugs the middle of the trail, a little strays to its edges.
        val spread = (noise(seed + 1) + noise(seed + 2) + noise(seed + 6)) / 3f - 0.5f
        val drift = spread * radius * (0.06f + 0.3f * along)
        val position = spiralPoint(centre, radius, spin, arm, along, drift)
        val alpha = strength * (1f - 0.55f * along) * (0.45f + 0.55f * noise(seed + 3))
        val color = if (along < 0.2f) SwirlWhite else dustColor(noise(seed + 4))
        val size = grainRadius * (0.45f + 1.1f * noise(seed + 5))
        if (i % 4 == 0) drawCircle(color.copy(alpha = alpha * 0.2f), size * 4f, position)
        drawCircle(color.copy(alpha = alpha), size, position)
    }
    repeat(GALAXY_FIELD_STARS) { k ->
        val seed = 70000 + k * 7
        val distance = radius * (0.15f + 0.95f * noise(seed))
        val angle = Math.toRadians((spin * 0.8f + noise(seed + 1) * 360f).toDouble())
        val position = centre + Offset((cos(angle) * distance).toFloat(), (sin(angle) * distance * GALAXY_FLATTEN).toFloat())
        val twinkle = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * 0.009f + k * 1.3f))
        drawCircle(SwirlWhite.copy(alpha = strength * twinkle * 0.8f), grainRadius * (0.4f + 0.6f * noise(seed + 2)), position)
    }
    repeat(GALAXY_SPARKLES) { k ->
        val seed = 90000 + k * 7
        val position = spiralPoint(centre, radius, spin, k % GALAXY_ARMS, 0.25f + 0.65f * noise(seed))
        val twinkle = 0.5f + 0.5f * sin(t * 0.01f + k * 1.7f)
        drawSparkle(position, (5f + 7f * noise(seed + 2)).dp.toPx(), strength * twinkle)
    }
}

/** The white-hot core: small while the arms show, it swells to hide the Renkin Pack symbol forming inside. */
private fun DrawScope.drawGalaxyCore(centre: Offset, t: Float) {
    val strength = BuildSwirl.core(t)
    if (strength <= 0f) return
    val core = 110.dp.toPx() * BuildSwirl.coreScale(t)
    drawCircle(
        Brush.radialGradient(
            0f to SwirlWhite.copy(alpha = strength),
            0.4f to SwirlPale.copy(alpha = 0.85f * strength),
            0.7f to SwirlGold.copy(alpha = 0.4f * strength),
            1f to SwirlGold.copy(alpha = 0f),
            center = centre,
            radius = core
        ),
        radius = core,
        center = centre
    )
}

private fun DrawScope.drawSparkle(centre: Offset, length: Float, alpha: Float) {
    if (alpha <= 0f) return
    val color = SwirlWhite.copy(alpha = alpha)
    val width = 1.2.dp.toPx()
    drawLine(color, centre - Offset(length, 0f), centre + Offset(length, 0f), width, StrokeCap.Round)
    drawLine(color, centre - Offset(0f, length), centre + Offset(0f, length), width, StrokeCap.Round)
    drawCircle(color, width * 1.5f, centre)
}

private const val SYMBOL_ORBIT_GRAINS = 22

/** The Renkin Pack symbol grows inside the condensing galaxy, comes to rest upright and pops into the FAB. */
private fun DrawScope.drawPackSymbol(symbol: Painter, centre: Offset, fabSlot: Offset, t: Float) {
    val alpha = BuildSwirl.flowerAlpha(t)
    if (alpha <= 0f) return
    val pop = BuildSwirl.pop(t)
    if (pop >= 1f) return
    // The adaptive foreground keeps the symbol inside its middle ~60%, so draw it larger.
    val restSize = 150.dp.toPx() * (0.4f + 0.6f * alpha)
    val endSize = 40.dp.toPx()
    val size = restSize + (endSize - restSize) * pop
    val u = 1f - pop
    val control = Offset(centre.x, fabSlot.y)
    val position = centre * (u * u) + control * (2f * u * pop) + fabSlot * (pop * pop)
    drawSymbolAura(position, size, t, BuildSwirl.symbolGlow(t))
    rotate(BuildSwirl.angle(t) - BuildSwirl.restAngle, pivot = position) {
        translate(position.x - size / 2f, position.y - size / 2f) {
            with(symbol) { draw(Size(size, size), alpha = alpha * (1f - ((pop - 0.85f) / 0.15f).coerceIn(0f, 1f))) }
        }
    }
}

/** An ethereal galactic glow around the finished symbol: a soft breathing halo with gold dust orbiting it. */
private fun DrawScope.drawSymbolAura(position: Offset, size: Float, t: Float, strength: Float) {
    if (strength <= 0f) return
    val breath = 0.85f + 0.15f * sin(t * 0.006f)
    val halo = size * 0.75f * breath
    drawCircle(
        Brush.radialGradient(
            0f to SwirlPale.copy(alpha = 0.55f * strength),
            0.45f to SwirlGold.copy(alpha = 0.25f * strength),
            1f to SwirlCopper.copy(alpha = 0f),
            center = position,
            radius = halo
        ),
        radius = halo,
        center = position
    )
    val grainRadius = 1.6.dp.toPx()
    repeat(SYMBOL_ORBIT_GRAINS) { k ->
        val seed = 120000 + k * 7
        val degrees = k * 360f / SYMBOL_ORBIT_GRAINS + t * (0.04f + 0.04f * noise(seed)) + noise(seed + 1) * 20f
        val orbit = size * (0.42f + 0.22f * noise(seed + 2))
        val angle = Math.toRadians(degrees.toDouble())
        val grain = position + Offset((cos(angle) * orbit).toFloat(), (sin(angle) * orbit * 0.8f).toFloat())
        val twinkle = 0.4f + 0.6f * (0.5f + 0.5f * sin(t * 0.012f + k * 2.1f))
        val color = dustColor(noise(seed + 3))
        drawCircle(color.copy(alpha = 0.25f * strength * twinkle), grainRadius * 3.5f, grain)
        drawCircle(color.copy(alpha = strength * twinkle), grainRadius * (0.6f + 0.6f * noise(seed + 4)), grain)
    }
}
