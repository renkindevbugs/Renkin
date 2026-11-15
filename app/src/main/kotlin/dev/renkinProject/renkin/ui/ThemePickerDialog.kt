@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.renkinProject.renkin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.DarkMode
import dev.renkinProject.renkin.data.getDarkModeLabels
import dev.renkinProject.renkin.data.isDarkModeEnabled
import dev.renkinProject.renkin.ui.theme.InnerShape
import dev.renkinProject.renkin.ui.theme.SwatchShape
import dev.renkinProject.renkin.ui.theme.renkinColorScheme

@Composable
internal fun ThemePickerDialog(
    selected: DarkMode,
    pureBlack: Boolean,
    onSelect: (DarkMode) -> Unit,
    onOledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val labels = getDarkModeLabels()
    val systemDark = isSystemInDarkTheme()
    MessageDialog(
        onDismissRequest = onDismiss,
        badge = { DialogBadge(Icons.Filled.DarkMode) },
        title = stringResource(R.string.theme),
        body = AnnotatedString(stringResource(R.string.themePreviewDescription)),
        actions = { DialogPrimaryAction(stringResource(R.string.close), onDismiss) }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(DarkMode.FOLLOW_SYSTEM, DarkMode.LIGHT, DarkMode.DARK).forEach { mode ->
                val chosen = selected == mode
                val colors = renkinColorScheme(isDarkModeEnabled(mode, systemDark), pureBlack = pureBlack)
                Column(
                    modifier = Modifier.weight(1f).clip(InnerShape)
                        .border(
                            width = if (chosen) 2.dp else 1.dp,
                            color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = InnerShape
                        )
                        .selectable(selected = chosen, role = Role.RadioButton, onClick = { onSelect(mode) })
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box {
                        ThemeScreenPreview(colors, Modifier.fillMaxWidth().height(132.dp))
                        if (chosen) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.TopEnd).size(20.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                            )
                        }
                    }
                    Text(
                        text = labels.getValue(mode),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        Surface(
            shape = InnerShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
                .toggleable(value = pureBlack, role = Role.Switch, onValueChange = onOledChange)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.oledBlackTitle), style = MaterialTheme.typography.titleSmallEmphasized)
                    Text(
                        stringResource(R.string.oledBlackSummary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = pureBlack, onCheckedChange = null)
            }
        }
    }
}

@Composable
private fun ThemeScreenPreview(colors: ColorScheme, modifier: Modifier = Modifier) {
    Surface(shape = SwatchShape, color = colors.background, modifier = modifier.clearAndSetSemantics { }) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(Modifier.fillMaxWidth(0.5f).height(4.dp).background(colors.onSurface, CircleShape))
            Column(
                Modifier.fillMaxWidth().weight(1f).clip(SwatchShape).background(colors.primaryContainer).padding(8.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(Modifier.size(12.dp).background(colors.primary, CircleShape))
                Box(Modifier.fillMaxWidth(0.7f).height(4.dp).background(colors.onPrimaryContainer, CircleShape))
            }
            repeat(3) {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(SwatchShape)
                        .background(colors.surfaceContainer).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(Modifier.size(8.dp).background(colors.secondary, CircleShape))
                    Box(Modifier.weight(1f).height(3.dp).background(colors.onSurfaceVariant, CircleShape))
                }
            }
        }
    }
}
