package dev.renkinProject.renkin.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.toSize
import dev.renkinProject.renkin.apk.IconApplyOutcome
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.ui.theme.iconTileShape


/**
 * Flight tuning. The easing starts gently (the icon eases off the row rather than snapping
 * away), is fastest mid-way and settles softly in the slot. Slower or faster: change the
 * durations. A gentler start: raise the first easing number (0.4f) toward 0.6f; a longer,
 * softer landing: lower the third (0.2f) toward 0f.
 */
const val ICON_FLIGHT_DURATION_MS = 700
const val ICON_RETURN_DURATION_MS = 550
val IconFlightEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

enum class IconFlightPhase { OUTBOUND, LANDED, RETURNING, HOME }

/**
 * One trip of an app icon between its home-screen row and the edit dialog's header. The dialog
 * is its own window, so every end is measured in screen coordinates, and every end is re-measured
 * each frame: the dialog slides while icons fly, and the list may scroll during the return.
 *
 * Up to two icons travel together: the app's icon between the row and the Current slot, and the
 * row's custom icon (if it has one) between the row's result slot and the New slot.
 */
@Stable
class IconFlightState internal constructor(
    val bitmap: ImageBitmap,
    origin: Rect,
    private val anchor: IconAnchor,
    /** The row result slot's picture before the edit; a different one means the applied icon is drawn. */
    val resultBefore: ImageBitmap?,
    resultOrigin: Rect?
) {
    var target by mutableStateOf<Rect?>(null)
    var phase by mutableStateOf(IconFlightPhase.OUTBOUND)
        internal set
    internal val launchSize = origin.size
    internal val progress = Animatable(0f)

    // Last known row positions, used when the row leaves the screen mid-flight.
    private var lastHome = origin
    private var lastResultSlot = resultOrigin

    /** The custom icon that flies from the row's result slot into New as the editor opens. */
    val arriving: ImageBitmap? = resultBefore?.takeIf { resultOrigin != null }

    /** The slot shows its own icon only while nothing is flying to or from it. */
    val slotVisible: Boolean get() = phase == IconFlightPhase.LANDED

    /**
     * The slot keeps reporting its position until the return starts; from then the closing
     * dialog slides it away, and following it would swing the icon sideways before it heads home.
     */
    val tracksSlot: Boolean get() = phase == IconFlightPhase.OUTBOUND || phase == IconFlightPhase.LANDED

    // Kept current by the New slot; read while icons fly to or from it.
    internal var newSlotBounds: Rect? = null
    internal var newSlotIcon: IconPackDrawable? = null

    /** The editor Apply whose outcome decides what the result slot keeps once the icon lands. */
    var applyRequestId: Long? = null

    /** What flies into the row's result slot while the original goes home; see [beginReturn]. */
    var delivery by mutableStateOf<IconDelivery?>(null)
        private set

    val delivering: Boolean get() = phase == IconFlightPhase.RETURNING && delivery != null

    /** New stays empty while its icon is in the air, in either direction. */
    val newSlotHidden: Boolean
        get() = (phase == IconFlightPhase.OUTBOUND && arriving != null) || delivering

    internal fun homeBounds(): Rect =
        anchor.coordinates?.takeIf { it.isAttached }?.boundsOnScreen()?.also { lastHome = it } ?: lastHome

    internal fun resultSlotBounds(): Rect? =
        anchor.deliveryCoordinates?.takeIf { it.isAttached }?.boundsOnScreen()?.also { lastResultSlot = it }
            ?: lastResultSlot

    /**
     * Sends the icon back to its row as the dialog closes, and fills the row's result slot the
     * same way: after Apply ([applied]) with the New icon, otherwise with the custom icon the
     * row already had, so that one also visibly returns instead of waiting in the slot.
     * Skipped when the row is gone (it scrolled away or the list changed), and the dialog then
     * simply slides out.
     */
    fun beginReturn(applied: Boolean = false) {
        if (phase != IconFlightPhase.OUTBOUND && phase != IconFlightPhase.LANDED) return
        if (anchor.coordinates?.isAttached != true) return
        val from = newSlotBounds
        if (from != null && resultSlotBounds() != null) {
            delivery = when {
                applied -> newSlotIcon?.let { IconDelivery(it, null, from, applied = true, applyRequestId) }
                else -> resultBefore?.let { IconDelivery(null, it, from, applied = false) }
            }
        }
        phase = IconFlightPhase.RETURNING
    }
}

/** What flies into the row's result slot: an applied New icon, or the row's own icon going back. */
class IconDelivery internal constructor(
    val icon: IconPackDrawable?,
    val bitmap: ImageBitmap?,
    val from: Rect,
    val applied: Boolean,
    val applyRequestId: Long? = null
)

/**
 * Where a list icon sits on screen and what it shows, kept outside snapshot state so scrolling
 * never recomposes the row just to keep this current.
 */
class IconAnchor {
    internal var coordinates: LayoutCoordinates? = null
    internal var deliveryCoordinates: LayoutCoordinates? = null
    var bitmap: ImageBitmap? = null
    // What the result slot showed when the editor opened, to tell an applied icon from the old one.
    var resultBitmap: ImageBitmap? = null

    fun launch(): IconFlightState? {
        val source = bitmap ?: return null
        val placed = coordinates?.takeIf { it.isAttached } ?: return null
        val resultSlot = deliveryCoordinates?.takeIf { it.isAttached }?.boundsOnScreen()
        return IconFlightState(source, placed.boundsOnScreen(), this, resultBitmap, resultSlot)
    }
}

fun Modifier.iconAnchor(anchor: IconAnchor): Modifier = onGloballyPositioned { anchor.coordinates = it }

/** Marks the row's result slot, where an applied New icon lands. */
fun Modifier.deliveryAnchor(anchor: IconAnchor): Modifier =
    onGloballyPositioned { anchor.deliveryCoordinates = it }

/** True while [this] flight is on screen, so its source can hide instead of showing twice. */
val IconFlightState?.inFlight: Boolean
    get() = this != null && target != null &&
        (phase == IconFlightPhase.OUTBOUND || phase == IconFlightPhase.RETURNING)

/** The flight the open edit dialog should finish, or null when it was opened without one. */
val LocalIconFlight = staticCompositionLocalOf<IconFlightState?> { null }

fun LayoutCoordinates.boundsOnScreen(): Rect = Rect(positionOnScreen(), size.toSize())

/**
 * Draws the flying icons above the dialog content. Only graphics layers change per frame, so
 * the flight never recomposes or relayouts the dialog underneath.
 */
@Composable
fun IconFlightOverlay(flight: IconFlightState) {
    // The slot moves every frame while the dialog slides; only its arrival may start the
    // flight, so composition must not read the position itself.
    val targetKnown by remember(flight) { derivedStateOf { flight.target != null } }
    LaunchedEffect(flight.phase, targetKnown) {
        when (flight.phase) {
            IconFlightPhase.OUTBOUND -> if (targetKnown) {
                flight.progress.animateTo(1f, tween(ICON_FLIGHT_DURATION_MS, easing = IconFlightEasing))
                flight.phase = IconFlightPhase.LANDED
            }
            // Runs the same arc backwards, from wherever an unfinished outbound flight got to.
            IconFlightPhase.RETURNING -> {
                flight.progress.animateTo(0f, tween(ICON_RETURN_DURATION_MS, easing = IconFlightEasing))
                flight.phase = IconFlightPhase.HOME
            }
            IconFlightPhase.LANDED, IconFlightPhase.HOME -> Unit
        }
    }
    if (flight.phase == IconFlightPhase.LANDED || flight.phase == IconFlightPhase.HOME) return

    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOrigin = it.positionOnScreen() }
    ) {
        // progress 0 = home in the row, 1 = in the dialog header, for every icon below.
        FlyingIcon(
            painter = remember(flight.bitmap) { BitmapPainter(flight.bitmap) },
            launchSize = flight.launchSize,
            overlayOrigin = { overlayOrigin },
            progress = { flight.progress.value },
            rowEnd = flight::homeBounds,
            dialogEnd = { flight.target }
        )
        val arriving = flight.arriving
        val arrivingStart = flight.resultSlotBounds()
        if (flight.phase == IconFlightPhase.OUTBOUND && arriving != null && arrivingStart != null) {
            FlyingIcon(
                painter = remember(arriving) { BitmapPainter(arriving) },
                launchSize = arrivingStart.size,
                overlayOrigin = { overlayOrigin },
                progress = { flight.progress.value },
                rowEnd = { flight.resultSlotBounds() ?: arrivingStart },
                dialogEnd = { flight.newSlotBounds }
            )
        }
        flight.delivery?.let { delivery ->
            FlyingIcon(
                painter = delivery.icon?.getPainter()
                    ?: remember(delivery) { BitmapPainter(requireNotNull(delivery.bitmap)) },
                launchSize = delivery.from.size,
                overlayOrigin = { overlayOrigin },
                progress = { flight.progress.value },
                rowEnd = { flight.resultSlotBounds() ?: delivery.from },
                dialogEnd = { delivery.from }
            )
        }
    }
}

/**
 * One icon on the arc between a row end and a dialog end. Both ends are read every frame, so the
 * icon follows a scrolling list or a sliding dialog instead of jumping when it lands.
 */
@Composable
private fun FlyingIcon(
    painter: Painter,
    launchSize: Size,
    overlayOrigin: () -> Offset,
    progress: () -> Float,
    rowEnd: () -> Rect,
    dialogEnd: () -> Rect?
) {
    val launchDp = with(LocalDensity.current) { launchSize.width.toDp() }
    Image(
        painter = painter,
        contentDescription = null,
        modifier = Modifier
            .graphicsLayer {
                val fraction = progress()
                val start = rowEnd()
                val end = dialogEnd() ?: start
                // Horizontal travel runs ahead of vertical, which bends the path into an arc.
                val horizontal = 1f - (1f - fraction) * (1f - fraction)
                val left = lerp(start.left, end.left, horizontal)
                val top = lerp(start.top, end.top, fraction)
                val width = lerp(start, end, fraction).width
                val origin = overlayOrigin()
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = left - origin.x
                translationY = top - origin.y
                scaleX = width / launchSize.width
                scaleY = scaleX
            }
            .requiredSize(launchDp)
            .clip(iconTileShape(launchDp))
    )
}

private fun lerp(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

/**
 * Whether the row's result slot should stay hidden for [this] flight: while its custom icon is
 * flying into the editor and while an icon flies back into it. An applied icon that has landed is
 * drawn by the slot itself until the handoff (see [landedApplyIcon]), so the slot never sits empty.
 */
val IconFlightState?.holdsResultSlot: Boolean
    get() = this != null && (
        (phase == IconFlightPhase.OUTBOUND && arriving != null && target != null) ||
            (delivery != null && phase == IconFlightPhase.RETURNING) ||
            (phase == IconFlightPhase.HOME && delivery?.applied == false)
        )

/** The applied icon a landed flight leaves in the row's result slot until the row takes over. */
val IconFlightState?.landedApplyIcon: IconPackDrawable?
    get() = this?.takeIf { phase == IconFlightPhase.HOME }?.delivery?.takeIf { it.applied }?.icon

/**
 * Whether a landed delivery can hand the result slot back to the row. A returning own icon and a
 * row that already draws the applied icon hand over at once. A failed Apply hands over at once too,
 * so the row's authoritative icon replaces the optimistic one. A successful Apply waits for the
 * row to draw it; the caller's reveal cap ends any wait, showing the row's own icon if none came.
 */
internal fun releasesDelivery(
    applied: Boolean,
    rowShowsAppliedIcon: Boolean,
    outcome: IconApplyOutcome?
): Boolean =
    !applied || rowShowsAppliedIcon || (outcome != null && outcome != IconApplyOutcome.APPLIED)
