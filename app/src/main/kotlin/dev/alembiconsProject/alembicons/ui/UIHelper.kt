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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.alembiconsProject.alembicons.MainActivity
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.dataStore
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
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        shape = CircleShape,
        singleLine = true,
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