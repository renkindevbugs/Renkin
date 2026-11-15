package dev.renkinProject.renkin.feature.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.ui.DialogBadge
import dev.renkinProject.renkin.ui.DialogCard
import dev.renkinProject.renkin.ui.DialogSecondaryAction
import dev.renkinProject.renkin.ui.MessageDialog

@Composable
fun WatchNotificationProfileDialog(
    profiles: List<Profile>,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    MessageDialog(
        onDismissRequest = onDismiss,
        badge = { DialogBadge(Icons.Filled.NotificationsNone) },
        title = stringResource(R.string.watchNotificationChooseProfile),
        body = AnnotatedString(stringResource(R.string.watchNotificationChooseProfileBody)),
        content = {
            DialogCard {
                profiles.forEach { profile ->
                    TextButton(onClick = { onSelect(profile.id) }, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Layers, contentDescription = null)
                            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                Text(profile.name, style = MaterialTheme.typography.bodyLarge)
                                if (profile.description.isNotBlank()) {
                                    Text(
                                        profile.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                    }
                }
            }
        },
        actions = { DialogSecondaryAction(stringResource(R.string.close), onDismiss) }
    )
}
