package dev.alembiconsProject.alembicons.ui

import android.content.Context
import android.os.Build
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.alembiconsProject.alembicons.MainActivity
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.dataStore
import dev.alembiconsProject.alembicons.ui.theme.DialogShape
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
    placeholder: String? = null
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
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        Icons.Filled.Close,
                        stringResource(R.string.clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface
    )
}

/**
 * A primary-coloured clickable text link that opens [url]. Carries a [Role.Button] semantic so
 * TalkBack announces it as actionable instead of plain text.
 */
@Composable
fun LinkText(text: String, url: String, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.clickable(role = Role.Button) { uriHandler.openUri(url) }
    )
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
    val context = LocalContext.current
    Box(modifier) {
        content()
        if (!enabled) {
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
            )
        }
    }
}

/**
 * Centered message shown when a list/grid has no items (e.g. an empty filter result). Pass an
 * [icon] for large empty areas (full screen / grid); omit it for thin inline slots (a single
 * pack's preview row) where a 48dp glyph wouldn't fit. Shared by the app list, watch editor and
 * icon-pack browser so every empty state reads the same.
 */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
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
    LaunchedEffect(toaster) {
        toaster.events.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}