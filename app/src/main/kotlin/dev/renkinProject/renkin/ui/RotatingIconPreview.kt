package dev.renkinProject.renkin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.apk.CalendarSource
import dev.renkinProject.renkin.apk.DynamicClockSource
import dev.renkinProject.renkin.apk.PreviewClock
import dev.renkinProject.renkin.apk.keepsClockLayers
import dev.renkinProject.renkin.apk.StoredCalendarStage
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.hasVisibleModifierEffect
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.util.runCatchingCancellable
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/** How the editor shows an applied icon: through its retained modifier input, or edits on top of its base. */
internal enum class StoredIconMode { REPLAY, BASE }

/** The New icon as the editor shows it, possibly at a previewed calendar day or clock time. */
internal class RotatingPreview(val icon: IconPackDrawable?, val pending: Boolean, val variants: PreviewVariants?)

private class VariantFrame(val index: Int, val key: Any, val icon: IconPackDrawable?)

// Times that expose hand alignment and clipping: the catalog pose, stacked hands, a right angle,
// both hands at the bottom edge and both on the left.
private val CLOCK_PREVIEW_TIMES = listOf(10 to 10, 12 to 0, 3 to 0, 6 to 30, 9 to 45)

/**
 * Calendar days and live-clock times for a pack icon picked in the editor. Days are rendered
 * through the same pipeline as the draft (and the kept Global style), like the build does.
 * Clocks rotate only with modifiers that keep their layers apart (see keepsClockLayers); the
 * others get an explanation instead of times.
 */
@Composable
internal fun rememberRotatingPreview(
    viewModel: MainViewModel,
    app: PackageInfoStruct,
    generationApplication: PackageInfoStruct,
    pickedPackIcon: ResourceDrawable?,
    iconPack: String,
    rotatingCalendar: CalendarSource?,
    freshAppCalendar: Boolean,
    options: GenerationOptions,
    keptGlobalOptions: GenerationOptions?,
    draftIcon: IconPackDrawable?,
    storedMode: StoredIconMode?
): RotatingPreview {
    val rotates = pickedPackIcon != null || freshAppCalendar || storedMode != null
    val calendarDays by produceState(emptyMap<Int, ResourceDrawable>(), rotates, rotatingCalendar) {
        value = if (rotates && rotatingCalendar != null) {
            runCatchingCancellable { viewModel.calendarDays(rotatingCalendar) }
                .getOrDefault(emptyMap())
        } else emptyMap()
    }
    // An applied icon keeps no pick, so its stage is recovered the way the build recovers it.
    val storedStage by produceState<StoredCalendarStage?>(null, storedMode, rotatingCalendar) {
        value = if (storedMode != null && rotatingCalendar != null) {
            runCatchingCancellable { viewModel.storedCalendarStage(app, rotatingCalendar) }.getOrNull()
        } else null
    }
    val clockSource by produceState<DynamicClockSource?>(null, pickedPackIcon?.resourceId, storedMode, iconPack, rotatingCalendar) {
        value = when {
            rotatingCalendar != null -> null
            pickedPackIcon != null ->
                runCatchingCancellable { viewModel.dynamicClockSource(app, iconPack, pickedPackIcon.resourceId) }.getOrNull()
            storedMode != null -> runCatchingCancellable { viewModel.storedDynamicClockSource(app) }.getOrNull()
            else -> null
        }
    }
    var selected by remember(pickedPackIcon?.resourceId, storedMode, rotatingCalendar) { mutableStateOf<Int?>(null) }
    val todayLabel = stringResource(R.string.previewToday)
    val nowLabel = stringResource(R.string.previewTimeNow)
    val clockStillNote = stringResource(R.string.previewClockStill)
    val today = remember { LocalDate.now().dayOfMonth }

    suspend fun withKeptGlobal(icon: IconPackDrawable): IconPackDrawable =
        keptGlobalOptions?.let { viewModel.applyModifier(icon, it) } ?: icon

    val days = calendarDays
    val stage = storedStage
    val clock = clockSource
    val appDay by produceState<Int?>(null, rotatingCalendar, freshAppCalendar) {
        value = (rotatingCalendar as? CalendarSource.App)?.let { viewModel.appCalendarDay(it, app.iconID) }
    }
    // A fresh pick and a replayed retained input regenerate each day from its package; an icon
    // shown without replay gets the editor's modifiers on top of its recovered day renders.
    val renderDay: (suspend (Int) -> IconPackDrawable?)? = when {
        days.isEmpty() || rotatingCalendar == null -> null
        pickedPackIcon != null || freshAppCalendar || (storedMode == StoredIconMode.REPLAY && stage?.isRetained == true) -> { day ->
            days[day]?.let { viewModel.previewCalendarDay(generationApplication, rotatingCalendar, options, it) }
        }
        storedMode == StoredIconMode.BASE && stage != null -> { day ->
            stage.days[day]?.let { viewModel.applyModifier(it, options) }
        }
        else -> null
    }
    // Only effects reach the clock layers; an untouched clock is the pack's own drawable.
    val clockChain = listOfNotNull(options, keptGlobalOptions).filter { it.hasVisibleModifierEffect() }
    val clockKeepsLayers = clockChain.all { it.keepsClockLayers() }
    val styledClock by produceState<PreviewClock?>(null, clock, clockChain, clockKeepsLayers, draftIcon) {
        value = if (clock != null && clockKeepsLayers) {
            runCatchingCancellable { viewModel.styleDynamicClock(clock, clockChain, draftIcon) }.getOrNull()
        } else null
    }
    val draftDay = if (pickedPackIcon != null) {
        days.entries.firstOrNull { it.value.resourceId == pickedPackIcon.resourceId }?.key
    } else if (freshAppCalendar) appDay else stage?.draftDay
    val variants = when {
        renderDay != null -> PreviewVariants(
            kind = RotatingIconKind.CALENDAR,
            labels = (1..31).map { if (it == today) todayLabel else it.toString() },
            selected = selected,
            draftIndex = draftDay?.minus(1),
            renderKey = listOf(options, keptGlobalOptions, days, stage),
            onSelect = { selected = it },
            render = { index -> renderDay(index + 1)?.let { withKeptGlobal(it) } }
        )
        clock == null -> null
        !clockKeepsLayers ->
            PreviewVariants(RotatingIconKind.CLOCK, emptyList(), null, null, Unit, onSelect = {}, render = { null }, note = clockStillNote)
        else -> styledClock?.let { styled ->
            val labels = CLOCK_PREVIEW_TIMES.map { (hour, minute) ->
                String.format(Locale.getDefault(), "%d:%02d", hour, minute)
            } + nowLabel
            PreviewVariants(
                kind = RotatingIconKind.CLOCK,
                labels = labels,
                selected = selected,
                draftIndex = null,
                renderKey = styled,
                onSelect = { selected = it },
                render = { index ->
                    val (hour, minute) = CLOCK_PREVIEW_TIMES.getOrNull(index)
                        ?: LocalTime.now().let { it.hour to it.minute }
                    viewModel.dynamicClockFrame(styled, hour, minute)
                }
            )
        }
    }

    val active = variants?.shownVariant
    val frame by produceState<VariantFrame?>(null, active, variants?.renderKey) {
        val current = variants
        if (active != null && current != null) {
            value = VariantFrame(active, current.renderKey, runCatchingCancellable { current.render(active) }.getOrNull())
        }
    }
    if (active == null || variants == null) return RotatingPreview(draftIcon, pending = false, variants = variants)
    val shown = frame
    val pending = shown == null || shown.index != active || shown.key != variants.renderKey
    return RotatingPreview(shown?.icon ?: draftIcon, pending, variants)
}
