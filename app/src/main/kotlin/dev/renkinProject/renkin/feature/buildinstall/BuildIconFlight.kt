package dev.renkinProject.renkin.feature.buildinstall

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
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
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_FLIGHTS = 64

// Renkin Pack symbol colours: copper into gold into pale gold.
private val SwirlCopper = Color(0xFFC85028)
private val SwirlGold = Color(0xFFD5B15B)
private val SwirlPale = Color(0xFFFFE8A0)
private val SwirlWhite = Color(0xFFFFF8E6)

/** A stable pseudo-random value in 0..1: the same grain keeps its place from frame to frame. */
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
 * The whole build animation as a function of time since the Build tap. Pack preview and the home
 * screen share one window, so a single clock drives the shattering icons, the galaxy, the home
 * screen surfacing underneath and the symbol landing in the Build FAB.
 */
internal object BuildSwirl {
    // Every icon starts to shatter at the same moment, and the preview chrome fades from then on.
    private const val SHATTER_START = 100f
    private const val CHROME_FADE = 500f
    const val CHROME_GONE = SHATTER_START + CHROME_FADE
    // How long one icon takes to come apart, its fragments breaking off one after another.
    private const val ICON_DETACH_SPREAD = 490f
    private const val FRAGMENT_SHAKE = 90f
    private const val DUST_FLIGHT = 825f
    private const val DUST_TURNS = 1.25f
    private const val HOME_IN_START = 600f
    private const val HOME_IN_END = 1600f
    private const val GALAXY_IN_START = 225f
    private const val GALAXY_IN_END = 1125f
    private const val SPIN_FAST_UNTIL = 1350f
    private const val SPIN_STOP = 2650f
    // The symbol assembles from the galaxy's own light, from its centre out to the petal tips.
    private const val FORM_START = 1500f
    private const val FORM_SPREAD = 700f
    private const val FORM_FLIGHT = 450f
    private const val ARMS_OUT_START = 1500f
    private const val ARMS_OUT_END = 2500f
    private const val CORE_OUT_START = 1500f
    private const val CORE_OUT_END = 2200f
    // A ring of light pulses out from the centre and sharpens the grains into the crisp symbol.
    private const val PULSE_START = 2650f
    private const val PULSE_END = 3100f
    private const val POP_START = 3250f
    // The Build button reaches the FAB while the home screen is already surfacing, so it never waits there.
    private const val PILL_HOME = 1100f
    const val TOTAL = 3550f
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

    fun chrome(t: Float) = 1f - ramp(t, SHATTER_START, CHROME_GONE)
    fun pillToButton(t: Float) = 1f - ease(ramp(t, SHATTER_START, PILL_HOME))
    /** The home screen fades in under the galaxy, replacing the wallpaper. */
    fun home(t: Float) = ease(ramp(t, HOME_IN_START, HOME_IN_END))

    private fun galaxyIn(t: Float) = ease(ramp(t, GALAXY_IN_START, GALAXY_IN_END))
    private fun armsOut(t: Float) = ease(ramp(t, ARMS_OUT_START, ARMS_OUT_END))

    /** The arms thin out as their light leaves to build the symbol. */
    fun arms(t: Float) = galaxyIn(t) * (1f - armsOut(t))
    fun galaxyScale(t: Float) = 1f - 0.4f * armsOut(t)
    fun core(t: Float) = galaxyIn(t) * (1f - ease(ramp(t, CORE_OUT_START, CORE_OUT_END)))

    /**
     * Flight progress of the grain bound for a symbol spot at [order] (0 = centre, 1 = petal tip):
     * below 0 it is still part of the galaxy, at 1 it sits in the symbol.
     */
    fun formProgress(t: Float, order: Float) = (t - FORM_START - FORM_SPREAD * order) / FORM_FLIGHT
    /** How far the sharpening ring has travelled (0..1), slowing as it nears the rim of the glow. */
    fun pulse(t: Float) = ramp(t, PULSE_START, PULSE_END).let { 1f - (1f - it) * (1f - it) }
    fun symbolGlow(t: Float) = pulse(t) * (1f - pop(t))
    fun pop(t: Float) = ease(ramp(t, POP_START, TOTAL))

    fun shattered(t: Float) = t >= SHATTER_START
    /** For an icon that could not be sampled into fragments: it simply fades while the others shatter. */
    fun unsampledIconAlpha(t: Float) = 1f - ramp(t, SHATTER_START, SHATTER_START + ICON_DETACH_SPREAD)

    /**
     * Flight progress of one icon fragment: negative while it still sits in the icon, 0..1 on its
     * way to the core, above 1 once absorbed. Fragments break off from the top corner diagonally.
     */
    fun fragmentProgress(t: Float, row: Int, column: Int, grid: Int, seed: Int): Float =
        fragmentProgress(t, fragmentOrder(row, column, grid, seed))

    /** When a fragment breaks off within its icon (0 = first, 1 = last): diagonally, slightly shuffled. */
    fun fragmentOrder(row: Int, column: Int, grid: Int, seed: Int): Float =
        (row + (grid - 1 - column) + noise(seed) * 3f) / (2f * (grid - 1) + 3f)

    fun fragmentProgress(t: Float, order: Float): Float = (t - SHATTER_START - ICON_DETACH_SPREAD * order) / DUST_FLIGHT

    /** Below 0 when the fragment starts trembling just before it breaks off. */
    fun isShaking(progress: Float) = progress > -FRAGMENT_SHAKE / DUST_FLIGHT && progress < 0f

    /** The fragment spirals in with the galaxy's turn, speeding up as it nears the core. */
    fun dustPosition(start: Offset, centre: Offset, u: Float): Offset {
        val from = start - centre
        return dustPosition(centre, from.getDistance(), atan2(from.y, from.x), u)
    }

    /** The same flight for a fragment whose start is already known as distance and angle from the centre. */
    fun dustPosition(centre: Offset, startDistance: Float, startAngle: Float, u: Float): Offset {
        val pull = u * u * (3f - 2f * u)
        val radius = startDistance * (1f - pull)
        val angle = startAngle + DUST_TURNS * 2f * PI.toFloat() * pull
        return Offset(centre.x + cos(angle) * radius, centre.y + sin(angle) * radius)
    }
}

/**
 * The preview icons captured on the Build tap, in root coordinates and on-screen order, with the
 * bitmaps the preview tiles already show (null for a tile still loading).
 */
internal class BuildFlight(val bounds: List<Rect>, val images: List<ImageBitmap?>)

/**
 * The build animation's state. Pack preview and the home screen share one window, so one clock
 * drives everything: the preview reports where its icons sit, the Build FAB reports where it is,
 * and the overlay above both draws the icons shattering into the galaxy and the symbol landing.
 */
@Stable
internal class BuildSwirlState {
    private var gridViewport: Rect? = null
    // Only read on the Build tap, never while drawing.
    private val iconBounds = HashMap<String, Rect>()
    private val iconBitmaps = HashMap<String, ImageBitmap>()
    // The preview publishes its current apps here so the tap keeps their on-screen order.
    var apps: List<PackageInfoStruct> = emptyList()

    private val elapsed = Animatable(0f)
    private var clockStarted by mutableStateOf(false)
    // Zero until this run's clock starts: the Animatable still holds the end of the previous run.
    internal val time: Float get() = if (clockStarted) elapsed.value else 0f
    internal val bump = Animatable(1f)
    /** 0 = home screen with the Build FAB, 1 = Pack preview with the Build button. */
    internal val previewMorph = Animatable(0f)
    // Painted off the main thread while the preview is open, so it is ready by the Build tap.
    internal var galaxyTexture by mutableStateOf<GalaxyTexture?>(null)
    // Decoded and sampled off the main thread with the galaxy; it does not depend on the screen.
    internal var packSymbol by mutableStateOf<PackSymbol?>(null)
    // Off when the Build animation is reduced: the two controls then simply crossfade with their screens.
    var pillEnabled by mutableStateOf(true)
    internal val fab = PillEnd()
    internal val buildButton = PillEnd()
    internal val fabBounds: Rect get() = fab.container
    var flight by mutableStateOf<BuildFlight?>(null)
        private set

    val isRunning: Boolean get() = flight != null
    // The grid hides only once the overlay animates, so the frame that composes the overlay icons still shows them.
    val isFlying: Boolean get() = flight != null && time > 0f
    // The preview chrome fades while the icons shatter, leaving them over the bare wallpaper.
    val chromeAlpha: Float get() = if (flight == null) 1f else BuildSwirl.chrome(time)
    /** The home screen surfaces under the galaxy while the animation runs. */
    val homeAlpha: Float get() = if (flight == null) 1f else BuildSwirl.home(time)

    /** The Build FAB and the preview's Build button are one pill travelling between their two places. */
    val isMorphing: Boolean get() = pillEnabled && !isRunning && previewMorph.value > 0f && previewMorph.value < 1f
    // After Build the pill returns to the FAB with the fading chrome, then hands over to the real FAB.
    internal val pillToButton: Float get() = if (isRunning) BuildSwirl.pillToButton(time) else previewMorph.value
    // The idle FAB as it stood on the Build tap: once building, the real one swaps its wrench for a spinner.
    internal var fabAtBuild: PillPlaces? = null
        private set
    internal val pillAlpha: Float get() = when {
        isRunning -> 1f - BuildSwirl.home(time)
        isMorphing -> 1f
        else -> 0f
    }

    fun onGridPlaced(coordinates: LayoutCoordinates) { gridViewport = coordinates.boundsInRoot() }
    fun onIconPlaced(key: String, coordinates: LayoutCoordinates) { iconBounds[key] = coordinates.boundsInRoot() }
    fun onIconBitmap(key: String, bitmap: ImageBitmap?) {
        if (bitmap == null) iconBitmaps.remove(key) else iconBitmaps[key] = bitmap
    }
    fun onIconRemoved(key: String) {
        iconBounds.remove(key)
        iconBitmaps.remove(key)
    }

    fun launch() {
        val viewport = gridViewport
        val visible = apps.mapNotNull { app ->
            iconBounds[app.key]?.takeIf { viewport == null || it.overlaps(viewport) }?.let { app.key to it }
        }.take(MAX_FLIGHTS)
        fabAtBuild = PillPlaces(fab.container, fab.icon, fab.label)
        flight = BuildFlight(visible.map { it.second }, visible.map { iconBitmaps[it.first] })
    }

    /**
     * Runs the whole animation. The faded-out preview stays composed until [onFinished]: tearing
     * its grid down mid-animation cost a visible frame just as the home screen surfaced.
     */
    internal suspend fun play(onFinished: () -> Unit) {
        try {
            elapsed.snapTo(0f)
            clockStarted = true
            elapsed.animateTo(BuildSwirl.TOTAL, tween(BuildSwirl.TOTAL.toInt(), easing = LinearEasing))
            bump.animateTo(1.08f, tween(90))
            bump.animateTo(1f, tween(180, easing = IconFlightEasing))
            // The home screen is already back, so the preview must not morph closed afterwards.
            previewMorph.snapTo(0f)
        } finally {
            // Also on cancellation: a stuck flight would keep the home screen transparent.
            onFinished()
            clockStarted = false
            flight = null
            iconBounds.clear()
            iconBitmaps.clear()
            fabAtBuild = null
        }
    }
}

internal val LocalBuildSwirl = staticCompositionLocalOf<BuildSwirlState?> { null }

/** Draws the build animation above both Pack preview and the home screen. */
@Composable
internal fun BuildSwirlOverlay(state: BuildSwirlState, iconBackground: Color?, onFinished: () -> Unit) {
    val flight = state.flight ?: return
    val fragments = rememberFragments(flight, iconBackground)
    val fragmentBatch = remember { FragmentBatch() }
    val galaxyRadius = galaxyRadius()
    val resources = LocalContext.current.resources
    // Prepared while the preview was open; loading it here is only the fallback for a very fast tap.
    val packSymbol = state.packSymbol ?: remember { loadPackSymbol(resources) }
    val symbol = remember(packSymbol) { BitmapPainter(packSymbol.image) }
    val finished by rememberUpdatedState(onFinished)
    LaunchedEffect(flight) { state.play { finished() } }
    Canvas(Modifier.fillMaxSize()) {
        val t = state.time
        val fab = state.fabBounds
        val fabIconSlot = if (fab.width > fab.height * 1.3f) Offset(fab.left + 28.dp.toPx(), fab.center.y) else fab.center
        drawIconStreams(flight.bounds, flight.images, fragments, fragmentBatch, iconBackground, t, center)
        drawGalaxyArms(center, t, galaxyRadius, state.galaxyTexture)
        drawGalaxyCore(center, t)
        drawSymbolAssembly(packSymbol.spots, center, t, galaxyRadius)
        drawPackSymbol(symbol, center, fabIconSlot, t)
    }
}

private const val FAB_CORNER_DP = 16
private const val BUTTON_CONTAINER_HEIGHT_DP = 40
private const val MIN_TOUCH_TARGET_DP = 48

internal class PillPlaces(val container: Rect, val icon: Rect, val label: Rect)

/** Where one end of the pill morph (the FAB or the Build button) keeps its container, wrench and label. */
@Stable
internal class PillEnd {
    var container by mutableStateOf(Rect.Zero)
    var icon by mutableStateOf(Rect.Zero)
    var label by mutableStateOf(Rect.Zero)
    var labelStyle by mutableStateOf<TextStyle?>(null)

    fun onContainerPlaced(coordinates: LayoutCoordinates) { container = coordinates.boundsInRoot() }
    fun onIconPlaced(coordinates: LayoutCoordinates) { icon = coordinates.boundsInRoot() }
    fun onLabelPlaced(coordinates: LayoutCoordinates) { label = coordinates.boundsInRoot() }
}

/** Reports the wrench or label of one pill end; the pill is drawn from these measured places, never from guesses. */
internal fun Modifier.pillPart(onPlaced: ((LayoutCoordinates) -> Unit)?): Modifier =
    if (onPlaced == null) this else onGloballyPositioned(onPlaced)

private fun mixRect(from: Rect, to: Rect, share: Float) = Rect(
    from.left + (to.left - from.left) * share,
    from.top + (to.top - from.top) * share,
    from.right + (to.right - from.right) * share,
    from.bottom + (to.bottom - from.bottom) * share
)

/**
 * The Build FAB and the preview's Build button drawn as one pill while it travels between their
 * two places; both real controls hide for that time. Every end value is measured from the real
 * control (container, wrench, label, text style), so the pill lands exactly on it.
 */
@Composable
internal fun BuildPillMorph(state: BuildSwirlState) {
    val label = stringResource(R.string.buildIconPack)
    val wrench = rememberVectorPainter(Icons.Filled.Build)
    val measurer = rememberTextMeasurer()
    val fallbackStyle = MaterialTheme.typography.labelLarge
    val fabColor = MaterialTheme.colorScheme.primaryContainer
    val onFabColor = MaterialTheme.colorScheme.onPrimaryContainer
    val buttonColor = MaterialTheme.colorScheme.primary
    val onButtonColor = MaterialTheme.colorScheme.onPrimary
    Canvas(Modifier.fillMaxSize()) {
        val alpha = state.pillAlpha
        val fab = state.fab
        val button = state.buildButton
        if (alpha <= 0f || fab.container.isEmpty || button.container.isEmpty || button.icon.isEmpty) return@Canvas
        val toButton = state.pillToButton

        // A button shorter than the minimum touch target reports the padded target, not its visible container.
        val buttonContainer = button.container.let { outer ->
            if (outer.height > MIN_TOUCH_TARGET_DP.dp.toPx() + 1f) outer
            else Rect(outer.left, outer.center.y - BUTTON_CONTAINER_HEIGHT_DP.dp.toPx() / 2f, outer.right, outer.center.y + BUTTON_CONTAINER_HEIGHT_DP.dp.toPx() / 2f)
        }
        val pill = mixRect(fab.container, buttonContainer, toButton)
        // While building, keep the idle FAB's wrench and label, moved along with the live (wider) container.
        val idle = state.fabAtBuild
        val shift = if (idle == null) Offset.Zero else Offset(fab.container.left - idle.container.left, fab.container.center.y - idle.container.center.y)
        val fabIconPlace = idle?.icon?.translate(shift) ?: fab.icon
        val fabLabelPlace = idle?.label?.translate(shift) ?: fab.label
        val corner = FAB_CORNER_DP.dp.toPx() + (buttonContainer.height / 2f - FAB_CORNER_DP.dp.toPx()) * toButton
        drawRoundRect(lerp(fabColor, buttonColor, toButton).copy(alpha = alpha), pill.topLeft, pill.size, CornerRadius(corner))

        val content = lerp(onFabColor, onButtonColor, toButton)
        val fabIcon = fabIconPlace.takeUnless { it.isEmpty } ?: Rect(fab.container.center, button.icon.width / 2f)
        val icon = mixRect(fabIcon, button.icon, toButton)
        translate(icon.left, icon.top) {
            with(wrench) { draw(icon.size, alpha = alpha, colorFilter = ColorFilter.tint(content)) }
        }

        // A collapsed FAB shows the wrench alone; its label then appears on the way to the button.
        val fabHasLabel = !fabLabelPlace.isEmpty && fabLabelPlace.overlaps(fab.container)
        val buttonStyle = button.labelStyle ?: fallbackStyle
        val fabStyle = fab.labelStyle ?: buttonStyle
        val text = measurer.measure(label, lerp(fabStyle, buttonStyle, toButton))
        val fabLabelStart = if (fabHasLabel) fabLabelPlace.topLeft else Offset(fabIcon.right, fabIcon.center.y - text.size.height / 2f)
        val labelStart = fabLabelStart + (button.label.topLeft - fabLabelStart) * toButton
        val labelAlpha = alpha * if (fabHasLabel) 1f else toButton
        if (labelAlpha > 0f) clipRect(pill.left, pill.top, pill.right, pill.bottom) {
            drawText(text, content, labelStart, alpha = labelAlpha)
        }
    }
}

/** The background themed packs put behind each icon, as Pack preview shows it. */
@Composable
internal fun previewIconBackground(preferences: Preferences): Color? = when {
    !preferences.getBooleanValue(ExportThemedKey) -> null
    supportDynamicColors() -> colorResource(R.color.icon_background_color)
    else -> preferences.getBackgroundColor(LocalContext.current)
}

/** The Build FAB reports where the symbol lands and bumps when it arrives. */
internal fun Modifier.buildSwirlTarget(state: BuildSwirlState?): Modifier =
    if (state == null) this else this
        .onGloballyPositioned(state.fab::onContainerPlaced)
        .graphicsLayer {
            scaleX = state.bump.value
            scaleY = state.bump.value
            alpha = if (state.isMorphing) 0f else 1f
        }

/** One piece of an icon: its cell, its own colour, the gold dust it turns into and when it breaks off (0..1). */
private class Fragment(val row: Int, val column: Int, val color: Color, val gold: Color, val order: Float, val seed: Int)

/**
 * An icon as pieces. Everything that does not change during the flight is worked out once —
 * thousands of pieces are drawn every frame, so the frame itself only blends and places them.
 */
private class IconFragments(val grid: Int, val pieces: List<Fragment>) {
    private var placedFor: Rect? = null
    private var placedAround = Offset.Unspecified
    // Each piece's start as distance and angle from the galaxy centre.
    var distances = FloatArray(0)
        private set
    var angles = FloatArray(0)
        private set

    fun placeAround(icon: Rect, centre: Offset) {
        if (placedFor == icon && placedAround == centre) return
        val cell = icon.width / grid
        distances = FloatArray(pieces.size)
        angles = FloatArray(pieces.size)
        pieces.forEachIndexed { index, piece ->
            val x = icon.left + (piece.column + 0.5f) * cell - centre.x
            val y = icon.top + (piece.row + 0.5f) * cell - centre.y
            distances[index] = hypot(x, y)
            angles[index] = atan2(y, x)
        }
        placedFor = icon
        placedAround = centre
    }
}

// Fewer, larger fragments when many icons shatter at once (tablets), to keep the frame cheap.
private fun fragmentGrid(iconCount: Int) = if (iconCount > 30) 7 else 9
private const val FRAGMENT_SAMPLES = 4

/**
 * The icon as a coarse grid of its own colours, so it can shatter into them. Each cell averages
 * several pixels: thin line icons would otherwise sample as empty and vanish. Null when there is
 * nothing to sample (the icon is empty). Safe off the main thread: it only reads [image].
 */
private fun sampleFragments(image: ImageBitmap, background: Color?, grid: Int, iconIndex: Int): IconFragments? {
    val side = grid * FRAGMENT_SAMPLES
    val bitmap = ImageBitmap(side, side)
    val size = Size(side.toFloat(), side.toFloat())
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bitmap), size) {
        clipPath(Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(size.width * 0.25f))) }) {
            background?.let { drawRect(it) }
            drawImage(image, dstSize = IntSize(side, side))
        }
    }
    val pixels = bitmap.toPixelMap()
    val pieces = ArrayList<Fragment>()
    for (cell in 0 until grid * grid) {
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
        if (coverage < 0.05f * FRAGMENT_SAMPLES * FRAGMENT_SAMPLES) continue
        val color = Color(red / coverage, green / coverage, blue / coverage, (coverage / (FRAGMENT_SAMPLES * FRAGMENT_SAMPLES) * 2.5f).coerceAtMost(1f))
        val row = cell / grid
        val column = cell % grid
        val seed = (iconIndex * grid * grid + cell) * 7
        pieces += Fragment(row, column, color, dustColor(noise(seed + 3)), BuildSwirl.fragmentOrder(row, column, grid, seed), seed)
    }
    return if (pieces.isEmpty()) null else IconFragments(grid, pieces)
}

/**
 * Sampled once per flight on a worker thread: doing it in the first composition after the tap
 * cost that frame ~280 ms. Null until ready; the icons stay whole before the shatter anyway.
 * An icon with nothing to sample gets null and just fades.
 */
@Composable
private fun rememberFragments(flight: BuildFlight, background: Color?): List<IconFragments?>? {
    var fragments by remember(flight, background) { mutableStateOf<List<IconFragments?>?>(null) }
    LaunchedEffect(flight, background) {
        val grid = fragmentGrid(flight.images.size)
        fragments = withContext(Dispatchers.Default) {
            flight.images.mapIndexed { index, image -> image?.let { sampleFragments(it, background, grid, index) } }
        }
    }
    return fragments
}

/** Big enough to read as a galaxy rather than a glow: it spans most of the screen's short side. */
@Composable
internal fun galaxyRadius(): Float {
    val configuration = LocalConfiguration.current
    return with(LocalDensity.current) { (minOf(configuration.screenWidthDp, configuration.screenHeightDp) * 0.58f).dp.toPx() }
}

/**
 * Every fragment square of a frame as one mesh. Thousands of separate draw calls cost most of a
 * frame; one call with reused arrays does not. The platform draws coloured meshes on
 * hardware-accelerated canvases from API 29, so older versions fall back to single rectangles.
 */
private class FragmentBatch {
    private var positions = FloatArray(0)
    private var colors = IntArray(0)
    private var indices = ShortArray(0)
    private var squares = 0
    private val paint = android.graphics.Paint()
    val supported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q

    fun start(maxSquares: Int) {
        squares = 0
        val capacity = maxSquares.coerceAtMost(MAX_SQUARES)
        if (positions.size >= capacity * 8) return
        positions = FloatArray(capacity * 8)
        // The platform reads one colour per vertex but checks the array against the position count.
        colors = IntArray(capacity * 8)
        indices = ShortArray(capacity * 6) { i ->
            val corner = (i / 6) * 4
            (corner + SQUARE_CORNERS[i % 6]).toShort()
        }
    }

    fun add(left: Float, top: Float, side: Float, argb: Int) {
        if (squares * 8 >= positions.size) return
        val p = squares * 8
        positions[p] = left; positions[p + 1] = top
        positions[p + 2] = left + side; positions[p + 3] = top
        positions[p + 4] = left + side; positions[p + 5] = top + side
        positions[p + 6] = left; positions[p + 7] = top + side
        val c = squares * 4
        colors[c] = argb; colors[c + 1] = argb; colors[c + 2] = argb; colors[c + 3] = argb
        squares++
    }

    fun draw(canvas: android.graphics.Canvas) {
        if (squares == 0) return
        canvas.drawVertices(
            android.graphics.Canvas.VertexMode.TRIANGLES, squares * 8, positions, 0,
            null, 0, colors, 0, indices, 0, squares * 6, paint
        )
    }

    private companion object {
        // Short indices address 65536 vertices, four per square.
        const val MAX_SQUARES = 16_000
        val SQUARE_CORNERS = intArrayOf(0, 1, 2, 0, 2, 3)
    }
}

private fun DrawScope.drawIconStreams(
    bounds: List<Rect>,
    images: List<ImageBitmap?>,
    fragments: List<IconFragments?>?,
    batch: FragmentBatch,
    background: Color?,
    t: Float,
    centre: Offset
) {
    // Every piece has reached the core: nothing left to walk through for the rest of the animation.
    if (BuildSwirl.fragmentProgress(t, order = 1f) >= 1f) return
    // Until the fragments are sampled the icons stay whole, even past the shatter start on a slow phone.
    if (!BuildSwirl.shattered(t) || fragments == null) {
        bounds.forEachIndexed { index, icon -> images.getOrNull(index)?.let { drawStreamIcon(icon, it, background, 1f) } }
        return
    }
    // A piece is at most its square plus a glow square.
    batch.start(fragments.sumOf { (it?.pieces?.size ?: 0) * 2 })
    bounds.forEachIndexed { index, icon ->
        val image = images.getOrNull(index) ?: return@forEachIndexed
        val iconFragments = fragments.getOrNull(index)
        if (iconFragments == null) drawStreamIcon(icon, image, background, BuildSwirl.unsampledIconAlpha(t))
        else drawIconFragments(icon, iconFragments, batch, t, centre)
    }
    drawIntoCanvas { batch.draw(it.nativeCanvas) }
}

/** Each fragment keeps the icon's own colour as it breaks off, then turns to gold dust near the core. */
private fun DrawScope.drawIconFragments(icon: Rect, fragments: IconFragments, batch: FragmentBatch, t: Float, centre: Offset) {
    fragments.placeAround(icon, centre)
    val cell = icon.width / fragments.grid
    val grain = 3.dp.toPx()
    val distances = fragments.distances
    val angles = fragments.angles
    fun square(left: Float, top: Float, side: Float, color: Color) {
        if (batch.supported) batch.add(left, top, side, color.toArgb()) else drawRect(color, Offset(left, top), Size(side, side))
    }
    fragments.pieces.forEachIndexed { index, piece ->
        val u = BuildSwirl.fragmentProgress(t, piece.order)
        if (u >= 1f) return@forEachIndexed
        if (u < 0f) {
            var left = icon.left + piece.column * cell
            var top = icon.top + piece.row * cell
            if (BuildSwirl.isShaking(u)) {
                val beat = (t / 30f).toInt()
                left += (noise(piece.seed + beat) - 0.5f) * cell * 0.3f
                top += (noise(piece.seed + beat + 1) - 0.5f) * cell * 0.3f
            }
            square(left, top, cell, piece.color)
            return@forEachIndexed
        }
        val position = BuildSwirl.dustPosition(centre, distances[index], angles[index], u)
        val gold = ((u - 0.25f) / 0.55f).coerceIn(0f, 1f)
        val tint = mixColor(piece.color, piece.gold, gold)
        val side = cell + (grain - cell) * (u / 0.4f).coerceAtMost(1f)
        val fade = 1f - ((u - 0.85f) / 0.15f).coerceIn(0f, 1f)
        val alpha = (piece.color.alpha + (1f - piece.color.alpha) * gold) * fade
        // Every other grain of gold dust glows.
        if (gold > 0f && index % 2 == 0) {
            val glow = side * 3.2f
            square(position.x - glow / 2f, position.y - glow / 2f, glow, tint.copy(alpha = 0.22f * gold * fade))
        }
        square(position.x - side / 2f, position.y - side / 2f, side, tint.copy(alpha = alpha))
    }
}

private fun DrawScope.drawStreamIcon(rect: Rect, image: ImageBitmap, background: Color?, alpha: Float) {
    if (rect.width <= 0f || alpha <= 0f) return
    val shape = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(rect.width * 0.25f))) }
    clipPath(shape) {
        background?.let { drawRect(it, rect.topLeft, rect.size, alpha = alpha) }
        drawImage(
            image,
            dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()),
            dstSize = IntSize(rect.width.toInt(), rect.height.toInt()),
            alpha = alpha
        )
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
private fun spiralPoint(
    centre: Offset,
    radius: Float,
    spin: Float,
    arm: Int,
    along: Float,
    drift: Float = 0f,
    flatten: Float = GALAXY_FLATTEN
): Offset {
    val distance = radius * GALAXY_INNER_RADIUS * exp(ln(1f / GALAXY_INNER_RADIUS) * along) + drift
    val degrees = spin + arm * 360f / GALAXY_ARMS - along * GALAXY_ARM_WIND_DEGREES
    val angle = Math.toRadians(degrees.toDouble())
    return centre + Offset((cos(angle) * distance).toFloat(), (sin(angle) * distance * flatten).toFloat())
}

/** White near the core, through pale gold and gold, to copper at the rim. */
private fun armColor(along: Float): Color = when {
    along < 0.25f -> lerp(SwirlWhite, SwirlPale, along / 0.25f)
    along < 0.65f -> lerp(SwirlPale, SwirlGold, (along - 0.25f) / 0.4f)
    else -> lerp(SwirlGold, SwirlCopper, (along - 0.65f) / 0.35f)
}

/**
 * The five trails at rest: each is light, not a line — overlapping soft glows that widen toward
 * the rim, filled with gold dust. Drawn round (unflattened) and unspun, for [renderGalaxyTexture].
 */
private fun DrawScope.drawGalaxyTrails(centre: Offset, radius: Float) {
    for (arm in 0 until GALAXY_ARMS) for (i in 0 until GALAXY_ARM_GLOWS) {
        val along = (i + 0.5f) / GALAXY_ARM_GLOWS
        val position = spiralPoint(centre, radius, 0f, arm, along, flatten = 1f)
        val glow = radius * (0.035f + 0.11f * along)
        val color = armColor(along)
        val alpha = 1f - 0.65f * along
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
        val position = spiralPoint(centre, radius, 0f, arm, along, drift, flatten = 1f)
        val alpha = (1f - 0.55f * along) * (0.45f + 0.55f * noise(seed + 3))
        val color = if (along < 0.2f) SwirlWhite else dustColor(noise(seed + 4))
        val size = grainRadius * (0.45f + 1.1f * noise(seed + 5))
        if (i % 4 == 0) drawCircle(color.copy(alpha = alpha * 0.2f), size * 4f, position)
        drawCircle(color.copy(alpha = alpha), size, position)
    }
}

// Glows and stray dust reach past the nominal radius.
private const val GALAXY_TEXTURE_REACH = 1.27f
private const val GALAXY_TEXTURE_SCALE = 0.75f
private const val GALAXY_TEXTURE_MAX_PX = 1280f

/** The galaxy's trails as one image for a galaxy of [radius] px. */
internal class GalaxyTexture(val image: ImageBitmap, val radius: Float)

/**
 * The trails only ever turn as a whole, so they are painted once (off the main thread) and each
 * frame just rotates the image: drawing their ~1300 translucent circles live cost most of a frame.
 */
internal fun renderGalaxyTexture(radius: Float, density: Density): GalaxyTexture {
    val side = (radius * GALAXY_TEXTURE_REACH * 2f * GALAXY_TEXTURE_SCALE).coerceIn(1f, GALAXY_TEXTURE_MAX_PX).toInt()
    val textureRadius = side / (2f * GALAXY_TEXTURE_REACH)
    val image = ImageBitmap(side, side)
    // Scaled density keeps the dust grains in proportion to the smaller image.
    val scaled = Density(density.density * textureRadius / radius, density.fontScale)
    CanvasDrawScope().draw(scaled, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(image), Size(side.toFloat(), side.toFloat())) {
        drawGalaxyTrails(center, textureRadius)
    }
    return GalaxyTexture(image, radius)
}

/** A whirling galaxy of gold light: the five trails turning as one, among twinkling stars. */
private fun DrawScope.drawGalaxyArms(centre: Offset, t: Float, fullRadius: Float, texture: GalaxyTexture?) {
    val strength = BuildSwirl.arms(t)
    if (strength <= 0f) return
    val radius = fullRadius * BuildSwirl.galaxyScale(t)
    val spin = BuildSwirl.angle(t)
    if (texture != null) {
        val half = radius * GALAXY_TEXTURE_REACH
        withTransform({
            scale(1f, GALAXY_FLATTEN, pivot = centre)
            rotate(spin, pivot = centre)
        }) {
            drawImage(
                image = texture.image,
                dstOffset = IntOffset((centre.x - half).toInt(), (centre.y - half).toInt()),
                dstSize = IntSize((half * 2f).toInt(), (half * 2f).toInt()),
                alpha = strength
            )
        }
    }
    val grainRadius = 1.6.dp.toPx()
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

/** The white-hot core: small and strong, it dims as the symbol starts to assemble where it burned. */
private fun DrawScope.drawGalaxyCore(centre: Offset, t: Float) {
    val strength = BuildSwirl.core(t)
    if (strength <= 0f) return
    val core = 36.dp.toPx()
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

private const val SYMBOL_GRID = 52
// The adaptive foreground keeps the symbol inside its middle ~60%, so it is drawn larger.
private const val SYMBOL_SIZE_DP = 150

/**
 * One spot of the Renkin Pack symbol: where it sits (-0.5..0.5), its colour, how far from the
 * centre (0..1), and the grain of galaxy dust that will fill it — its colour and its place on a
 * trail of a unit-radius galaxy at rest. All fixed, so a frame only turns and blends them.
 */
internal class SymbolSpot(
    val x: Float,
    val y: Float,
    val color: Color,
    val order: Float,
    val dust: Color,
    val fromX: Float,
    val fromY: Float
)

/** Cheap straight blend for per-grain use every frame (the library lerp converts through Oklab). */
private fun mixColor(from: Color, to: Color, share: Float) = Color(
    from.red + (to.red - from.red) * share,
    from.green + (to.green - from.green) * share,
    from.blue + (to.blue - from.blue) * share,
    from.alpha + (to.alpha - from.alpha) * share
)

/** The Renkin Pack symbol the animation lands in the FAB, and the spots its grains of light fill. */
internal class PackSymbol(val image: ImageBitmap, val spots: List<SymbolSpot>)

/** Decodes and samples the symbol; slow enough that it belongs on a worker thread. */
internal fun loadPackSymbol(resources: Resources): PackSymbol {
    // Native 432 px is about the size it is drawn at; density scaling would only enlarge it.
    val options = BitmapFactory.Options().apply { inScaled = false }
    val decoded = BitmapFactory.decodeResource(resources, R.drawable.renkinpack_ic_launcher_foreground, options)
    return PackSymbol(decoded.asImageBitmap(), sampleSymbol(decoded))
}

/** The symbol as a cloud of spots, so grains of light can fly in and build it from the centre outward. */
private fun sampleSymbol(symbol: Bitmap): List<SymbolSpot> {
    val grid = Bitmap.createScaledBitmap(symbol, SYMBOL_GRID, SYMBOL_GRID, true)
    val pixels = IntArray(SYMBOL_GRID * SYMBOL_GRID)
    grid.getPixels(pixels, 0, SYMBOL_GRID, 0, 0, SYMBOL_GRID, SYMBOL_GRID)
    if (grid !== symbol) grid.recycle()
    val places = ArrayList<Triple<Float, Float, Color>>()
    for (row in 0 until SYMBOL_GRID) for (column in 0 until SYMBOL_GRID) {
        val color = Color(pixels[row * SYMBOL_GRID + column])
        if (color.alpha < 0.5f) continue
        places += Triple((column + 0.5f) / SYMBOL_GRID - 0.5f, (row + 0.5f) / SYMBOL_GRID - 0.5f, color.copy(alpha = 1f))
    }
    val reach = places.maxOfOrNull { hypot(it.first, it.second) } ?: return emptyList()
    return places.mapIndexed { index, (x, y, color) ->
        val seed = 200000 + index * 5
        val along = 0.2f + 0.75f * noise(seed)
        val from = spiralPoint(Offset.Zero, 1f, 0f, index % GALAXY_ARMS, along, (noise(seed + 1) - 0.5f) * 0.2f * along, flatten = 1f)
        SymbolSpot(x, y, color, hypot(x, y) / reach, dustColor(noise(seed + 2)), from.x, from.y)
    }
}

// The sharpening pulse travels from the symbol's centre to the rim of its glow.
private const val SYMBOL_GLOW_REACH = 0.75f

private fun DrawScope.pulseRadius(t: Float) = SYMBOL_SIZE_DP.dp.toPx() * SYMBOL_GLOW_REACH * BuildSwirl.pulse(t)

/**
 * Grains of the galaxy's light leave its arms and settle into the Renkin Pack symbol, the centre
 * first and the petal tips last, while the whole shape turns with the slowing galaxy. The pulse
 * then sweeps them away: inside its ring the crisp symbol has already taken over.
 */
private fun DrawScope.drawSymbolAssembly(spots: List<SymbolSpot>, centre: Offset, t: Float, galaxyRadius: Float) {
    if (BuildSwirl.pulse(t) >= 1f) return
    val sharpened = pulseRadius(t)
    val size = SYMBOL_SIZE_DP.dp.toPx()
    val grain = size / SYMBOL_GRID * 0.62f
    val spin = Math.toRadians(BuildSwirl.angle(t).toDouble())
    val spinCos = cos(spin).toFloat()
    val spinSin = sin(spin).toFloat()
    val turn = Math.toRadians((BuildSwirl.angle(t) - BuildSwirl.restAngle).toDouble())
    val turnCos = cos(turn).toFloat()
    val turnSin = sin(turn).toFloat()
    val radius = galaxyRadius * BuildSwirl.galaxyScale(t)
    val halo = SwirlPale
    spots.forEachIndexed { index, spot ->
        val u = BuildSwirl.formProgress(t, spot.order)
        if (u <= 0f) return@forEachIndexed
        val arrived = IconFlightEasing.transform(u.coerceAtMost(1f))
        val targetX = centre.x + (spot.x * turnCos - spot.y * turnSin) * size
        val targetY = centre.y + (spot.x * turnSin + spot.y * turnCos) * size
        val fromX = centre.x + (spot.fromX * spinCos - spot.fromY * spinSin) * radius
        val fromY = centre.y + (spot.fromX * spinSin + spot.fromY * spinCos) * radius * GALAXY_FLATTEN
        val position = Offset(fromX + (targetX - fromX) * arrived, fromY + (targetY - fromY) * arrived)
        if ((position - centre).getDistance() < sharpened) return@forEachIndexed
        val alpha = (u / 0.15f).coerceAtMost(1f)
        if (index % 3 == 0) drawCircle(halo.copy(alpha = 0.16f * alpha), grain * 3.5f, position)
        drawCircle(mixColor(spot.dust, spot.color, arrived).copy(alpha = alpha), grain * (1.7f - 0.7f * arrived), position)
    }
}

private const val SYMBOL_ORBIT_GRAINS = 22

/**
 * A ring of light pulses out from the centre and wherever it has passed the grains are the crisp
 * symbol; the ring fades out at the rim of the glow. The finished symbol then pops into the FAB.
 */
private fun DrawScope.drawPackSymbol(symbol: Painter, centre: Offset, fabSlot: Offset, t: Float) {
    val pulse = BuildSwirl.pulse(t)
    if (pulse <= 0f) return
    val pop = BuildSwirl.pop(t)
    if (pop >= 1f) return
    val restSize = SYMBOL_SIZE_DP.dp.toPx()
    val endSize = 40.dp.toPx()
    val size = restSize + (endSize - restSize) * pop
    val u = 1f - pop
    val control = Offset(centre.x, fabSlot.y)
    val position = centre * (u * u) + control * (2f * u * pop) + fabSlot * (pop * pop)
    drawSymbolAura(position, size, t, BuildSwirl.symbolGlow(t))
    val sharpened = pulseRadius(t)
    val drawSymbol: DrawScope.() -> Unit = {
        rotate(BuildSwirl.angle(t) - BuildSwirl.restAngle, pivot = position) {
            translate(position.x - size / 2f, position.y - size / 2f) {
                with(symbol) { draw(Size(size, size), alpha = 1f - ((pop - 0.85f) / 0.15f).coerceIn(0f, 1f)) }
            }
        }
    }
    if (pulse >= 1f) {
        drawSymbol()
        return
    }
    clipPath(Path().apply { addOval(Rect(centre, sharpened)) }) { drawSymbol() }
    val ring = 1f - pulse
    drawCircle(SwirlPale.copy(alpha = 0.35f * ring), sharpened, centre, style = Stroke(10.dp.toPx()))
    drawCircle(SwirlWhite.copy(alpha = ring), sharpened, centre, style = Stroke(3.dp.toPx()))
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

private fun DrawScope.drawSparkle(centre: Offset, length: Float, alpha: Float) {
    if (alpha <= 0f) return
    val color = SwirlWhite.copy(alpha = alpha)
    val width = 1.2.dp.toPx()
    drawLine(color, centre - Offset(length, 0f), centre + Offset(length, 0f), width, StrokeCap.Round)
    drawLine(color, centre - Offset(0f, length), centre + Offset(0f, length), width, StrokeCap.Round)
    drawCircle(color, width * 1.5f, centre)
}
