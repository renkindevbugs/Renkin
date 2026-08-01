@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.renkinProject.renkin.ui

import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.DialogProperties
import dev.renkinProject.renkin.ui.theme.InnerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.MainActivity
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.dataStore
import dev.renkinProject.renkin.ui.theme.DialogShape
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The hosting [MainActivity], provided at the root by MainActivity itself.
 * Lets composables reach the activity without walking and casting the context
 * chain (the old getCurrentActivity() as MainActivity).
 */
val LocalMainActivity = staticCompositionLocalOf<MainActivity> {
    error("LocalMainActivity not provided")
}

@Composable
fun getCurrentContext(): Context {
    return LocalContext.current.applicationContext
}

@Composable
fun getCurrentMainActivity(): MainActivity = LocalMainActivity.current

@Composable
fun getPreferences(): DataStore<Preferences> {
    return getCurrentContext().dataStore
}

/** Light tactile tick for selecting/opening an item (e.g. picking a pack icon). */
fun View.performTapHaptic() {
    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
}

/** The system's own long-press tick, so a press-and-hold feels like it does everywhere else. */
fun View.performLongPressHaptic() {
    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}

/** Stronger confirmation tick for committing an action (e.g. building the pack). */
fun View.performConfirmHaptic() {
    val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.CONFIRM
    } else {
        HapticFeedbackConstants.LONG_PRESS
    }
    performHapticFeedback(constant)
}

/**
 * Clickable that gives immediate tactile + visual feedback: a light haptic plus a
 * quick spring "squish" while pressed. Meant for icon tiles with no background of
 * their own, where a rectangular ripple would look odd — the scale is the feedback.
 */
@Composable
fun Modifier.tappableIcon(onClick: () -> Unit): Modifier {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "iconPressScale"
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interactionSource, indication = null) {
            view.performTapHaptic()
            onClick()
        }
}

/**
 * App-wide toast surface. UI code (and the [MainViewModel] event bridge) calls [show];
 * a single [ToastHost] near the composition root actually displays the messages, one at a
 * time. Replaces the repeated `var done by remember { mutableStateOf(false) }; if (done) {
 * ShowToast(...); done = false }` one-shot idiom that was scattered across the UI.
 */
class Toaster {
    // BUFFERED so events fired while no host is collecting (e.g. before first composition)
    // are not dropped; they replay to the host once it attaches.
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()

    /** Queues [message] to be shown once. Safe to call from any thread. */
    fun show(message: String) {
        messages.trySend(message)
    }
}

/** The active [Toaster], provided at the root by MainActivity. */
val LocalToaster = staticCompositionLocalOf<Toaster> {
    error("LocalToaster not provided")
}

/**
 * Shared rounded search field: a search leading icon plus a clear (×) trailing button that
 * appears once there is text. Used by the app list and the icon browser so both look and
 * behave identically; the sort/filter menu beside it stays at the call site since it differs.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    // Extra control(s) rendered inside the field after the clear button — e.g. the home
    // list's sort/filter menu, so it belongs to the field instead of floating beside it.
    extraTrailing: (@Composable () -> Unit)? = null
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        shape = CircleShape,
        singleLine = true,
        // Enter / the IME search key just dismisses the keyboard and the cursor — the list already
        // filters live as you type, so there is nothing to "submit".
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = {
            Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(
                            Icons.Filled.Close,
                            stringResource(R.string.clear),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                extraTrailing?.invoke()
            }
        },
        modifier = modifier
    )
}

/**
 * Shared non-destructive dialog: every Renkin [AlertDialog] gets the same [DialogShape] on an
 * elevated surface with the standard title colour, so callers only supply the content slots. For
 * a destructive confirm use [ConfirmDialog] instead.
 */
@Composable
fun RenkinAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        properties = properties,
        dismissButton = dismissButton,
        icon = icon,
        // Expressive emphasized titles across every Renkin dialog, in one place.
        title = title?.let {
            { ProvideTextStyle(MaterialTheme.typography.headlineSmallEmphasized) { it() } }
        },
        text = text,
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface
    )
}

/**
 * Turns `**double-asterisk**` segments semi-bold — dialog bodies use it so the load-bearing
 * part of a long text (what gets replaced, which pack, what to tap) stands out at a glance.
 */
fun String.withBoldMarkers(): AnnotatedString = buildAnnotatedString {
    split("**").forEachIndexed { index, part ->
        if (index % 2 == 1) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part) }
        } else {
            append(part)
        }
    }
}

/** [stringResource] whose result renders `**marked**` segments bold (see [withBoldMarkers]). */
@Composable
fun boldStringResource(@StringRes id: Int, vararg formatArgs: Any): AnnotatedString =
    stringResource(id, *formatArgs).withBoldMarkers()

/**
 * A primary-coloured clickable text link that opens [url]. Carries a [Role.Button] semantic so
 * TalkBack announces it as actionable instead of plain text.
 */
@Composable
fun LinkText(text: String, url: String, modifier: Modifier = Modifier) {
    val openLink = rememberLinkOpener()
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.clickable(role = Role.Button) { openLink(url) }
    )
}

/**
 * Opens a web link, reporting failure as a toast instead of crashing. A browser is the norm but
 * not a guarantee — stripped ROMs and locked-down work profiles can have no handler, and
 * `openUri` throws when nothing resolves.
 */
@Composable
fun rememberLinkOpener(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    val toaster = LocalToaster.current
    val failed = stringResource(R.string.linkOpenFailed)
    return remember(uriHandler, toaster, failed) {
        { url: String ->
            if (runCatching { uriHandler.openUri(url) }.isFailure) toaster.show(failed)
        }
    }
}

/**
 * Collapses a composable's height and fades it by [fraction] (1 = full, 0 = gone), re-measured each
 * frame so it tracks the scroll pixel-by-pixel instead of snapping like AnimatedVisibility.
 * [fraction] is a lambda read only in the draw / layout phases, so a value that changes every scroll
 * frame re-lays-out this element without recomposing the caller (cheap even over a heavy list).
 */
fun Modifier.collapsibleHeight(fraction: () -> Float): Modifier =
    graphicsLayer { alpha = fraction() }
        .clipToBounds()
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val height = (placeable.height * fraction()).roundToInt()
            layout(placeable.width, height) { placeable.place(0, 0) }
        }

/**
 * Borderless search field for a TopAppBar's title slot (Mihon-style): transparent container, no
 * underline, and a clear (×) button while there's a query. The IME search action drops focus.
 */
@Composable
fun AppBarSearchField(query: String, onQueryChange: (String) -> Unit, placeholder: String) {
    val focusManager = LocalFocusManager.current
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, stringResource(R.string.clear))
                }
            }
        } else null,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Explains a disabled control on tap: while [enabled] is false, a transparent overlay catches the
 * tap (a disabled button swallows it) and shows [message] as a toast, so the user learns what to do
 * instead of getting a dead button. Wrap the button and keep passing [enabled] to it for the visuals.
 */
@Composable
fun DisabledExplanation(
    enabled: Boolean,
    message: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val toaster = LocalToaster.current
    Box(modifier) {
        content()
        if (!enabled) {
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { toaster.show(message) }
            )
        }
    }
}

/**
 * Centered message shown when a list/grid has no items (e.g. an empty filter result). Pass an
 * [icon] for large empty areas (full screen / grid); omit it for thin inline slots (a single
 * pack's preview row) where a 48dp glyph wouldn't fit. Shared by the app list, watch editor and
 * icon-pack browser so every empty state reads the same. An optional [actionLabel]/[onAction]
 * pair adds a button offering the obvious way out (e.g. clearing an active filter).
 */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(12.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/** A [DropdownMenuItem] that shows a check mark in the leading slot while [checked]. */
@Composable
fun CheckableDropdownItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        leadingIcon = if (checked) {
            { Icon(Icons.Filled.Done, null, tint = MaterialTheme.colorScheme.primary) }
        } else null
    )
}

/**
 * Drives the single toast surface: collects [toaster] events and shows each as a toast.
 * Provide [LocalToaster] and place this once near the composition root.
 */
@Composable
fun ToastHost(toaster: Toaster) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<Toast?>(null) }
    LaunchedEffect(toaster) {
        toaster.events.collect { message ->
            // Android queues toasts, so rapid actions left a stale one on screen long after the
            // action that caused it. Cancelling the previous keeps the newest message honest.
            shown?.cancel()
            shown = Toast.makeText(context, message, Toast.LENGTH_SHORT).also { it.show() }
        }
    }
    DisposableEffect(Unit) { onDispose { shown?.cancel() } }
}
/**
 * Position provider for tooltips that CLAMPS the popup into the window horizontally — the
 * stock M3 plain-tooltip provider centres over the anchor without clamping, so a tooltip on a
 * badge near the screen edge runs off screen. Vertically it prefers above the anchor and
 * flips below when there is no room.
 */
@Composable
private fun rememberClampedTooltipPositionProvider(): PopupPositionProvider {
    val spacingPx = with(LocalDensity.current) { 6.dp.roundToPx() }
    return remember(spacingPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                var y = anchorBounds.top - popupContentSize.height - spacingPx
                if (y < 0) y = anchorBounds.bottom + spacingPx
                return IntOffset(x, y)
            }
        }
    }
}

/**
 * The app-wide tooltip: long-press (or hover) the [content] anchor to show [text]. Stays inside
 * the window (see [rememberClampedTooltipPositionProvider]) and wraps long text into a rounded,
 * width-capped bubble instead of one clipped line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenkinTooltipBox(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = rememberClampedTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(shape = InnerShape) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
        },
        state = rememberTooltipState(),
        modifier = modifier
    ) {
        content()
    }
}
