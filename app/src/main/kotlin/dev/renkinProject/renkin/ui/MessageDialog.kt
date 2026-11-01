package dev.renkinProject.renkin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.renkinProject.renkin.ui.theme.InnerShape

/** What a message dialog is about; picks the badge and primary-action colours together. */
enum class DialogTone { Accent, Danger, Neutral }

/**
 * The shared message layout: badge, optional eyebrow over a hero title, supporting line, body,
 * free content (cards, steps, notes) and full-width stacked actions. When the dialog is about one
 * thing (a pack, a profile, an app), its name is the hero and the action becomes the eyebrow.
 */
@Composable
fun MessageDialog(
    onDismissRequest: () -> Unit,
    badge: @Composable () -> Unit,
    title: String?,
    actions: @Composable ColumnScope.() -> Unit,
    eyebrow: String? = null,
    supporting: String? = null,
    body: AnnotatedString? = null,
    properties: DialogProperties = DialogProperties(),
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    RenkinAlertDialog(
        onDismissRequest = onDismissRequest,
        properties = properties,
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                MessageHeader(badge, title, eyebrow, supporting)
                body?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                    )
                }
                content?.invoke(this)
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp), content = actions)
        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MessageHeader(badge: @Composable () -> Unit, title: String?, eyebrow: String?, supporting: String?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        badge()
        eyebrow?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
        title?.let {
            Text(
                it,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = if (eyebrow != null) 4.dp else 16.dp)
            )
        }
        supporting?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun DialogBadge(icon: ImageVector, tone: DialogTone = DialogTone.Accent) {
    val (container, content) = tone.badgeColors()
    Box(Modifier.size(56.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(28.dp))
    }
}

/** Occupies the badge slot while something runs, so progress dialogs keep the same silhouette. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DialogProgressBadge() {
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        LoadingIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun DialogTone.badgeColors(): Pair<Color, Color> = when (this) {
    DialogTone.Accent -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    DialogTone.Danger -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    DialogTone.Neutral -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun DialogPrimaryAction(text: String, onClick: () -> Unit, tone: DialogTone = DialogTone.Accent, enabled: Boolean = true) {
    val view = LocalView.current
    val colors = if (tone == DialogTone.Danger) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
        )
    } else ButtonDefaults.buttonColors()
    Button(
        onClick = {
            if (tone == DialogTone.Danger) view.performConfirmHaptic()
            onClick()
        },
        enabled = enabled,
        colors = colors,
        modifier = Modifier.fillMaxWidth()
    ) { Text(text) }
}

@Composable
fun DialogSecondaryAction(text: String, onClick: () -> Unit, danger: Boolean = false, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = if (danger && enabled) MaterialTheme.colorScheme.error else Color.Unspecified)
    }
}

/**
 * Two opposite choices side by side, the safe or primary one on the right as in the platform's
 * dialogs. Stacking them would put the destructive choice right under the thumb aiming at Save.
 */
@Composable
fun DialogSplitActions(startText: String, onStart: () -> Unit, endText: String, onEnd: () -> Unit, startDanger: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onStart, modifier = Modifier.weight(1f)) {
            Text(startText, color = if (startDanger) MaterialTheme.colorScheme.error else Color.Unspecified)
        }
        Button(onClick = onEnd, modifier = Modifier.weight(1f)) { Text(endText) }
    }
}

/** A raised block inside a message dialog: steps, lists, facts. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DialogCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = InnerShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            title?.let {
                Text(it, style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.onSurface)
            }
            content()
        }
    }
}

@Composable
fun DialogSteps(title: String, steps: List<AnnotatedString>, footnote: String? = null) {
    DialogCard(title) {
        steps.forEachIndexed { index, step -> DialogStep(index + 1, step) }
        footnote?.let { DialogCardNote(it) }
    }
}

@Composable
private fun DialogStep(number: Int, text: AnnotatedString) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A label and its value on one line, the value emphasised. */
@Composable
fun DialogFactRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun DialogCardNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A centred small line under the body, in the error colour when something went wrong. */
@Composable
fun DialogNote(text: String, isError: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
    )
}
