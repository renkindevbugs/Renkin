package dev.renkinProject.renkin.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.MainViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.Profile

/**
 * The top-bar title as a profile switcher: shows the active profile's name (the default profile
 * shows the app name) and opens a dropdown with every profile plus "Create new profile". Each
 * profile is its own icon set + preferences + built pack; the default one can't be deleted.
 */
@Composable
fun ProfileSwitcherTitle() {
    val viewModel: MainViewModel = hiltViewModel()
    val profiles by viewModel.profiles.collectAsState(initial = emptyList())
    val activeId = viewModel.activeProfileId
    val active = profiles.find { it.id == activeId }

    var menuOpen by remember { mutableStateOf(false) }
    var createOpen by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Profile?>(null) }

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { menuOpen = true }
        ) {
            Text(
                text = if (activeId == DEFAULT_PROFILE_ID) stringResource(R.string.app_name)
                    else active?.name ?: stringResource(R.string.app_name)
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.profilesTitle),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(profile.name)
                            if (profile.description.isNotEmpty()) {
                                Text(
                                    text = profile.description,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    leadingIcon = if (profile.id == activeId) {
                        { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    trailingIcon = if (profile.id != DEFAULT_PROFILE_ID) {
                        {
                            IconButton(onClick = {
                                menuOpen = false
                                pendingDelete = profile
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.deleteProfile),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    } else null,
                    onClick = {
                        menuOpen = false
                        if (profile.id != activeId) viewModel.switchProfile(profile.id)
                    }
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DropdownMenuItem(
                text = { Text(stringResource(R.string.createProfile)) },
                leadingIcon = { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary) },
                onClick = {
                    menuOpen = false
                    createOpen = true
                }
            )
        }
    }

    if (createOpen) {
        CreateProfileDialog(
            onCreate = { name, description, packLabel ->
                createOpen = false
                viewModel.createProfile(name, description, packLabel)
            },
            onDismiss = { createOpen = false }
        )
    }

    pendingDelete?.let { profile ->
        ConfirmDialog(
            title = stringResource(R.string.deleteProfile),
            text = stringResource(R.string.deleteProfileText, profile.name),
            onConfirm = {
                pendingDelete = null
                viewModel.deleteProfile(profile.id)
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/** Name + optional description + the launcher label of the profile's built pack. */
@Composable
private fun CreateProfileDialog(
    onCreate: (name: String, description: String, packLabel: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var packLabel by rememberSaveable { mutableStateOf("") }

    RenkinAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.createProfileTitle)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.profileName)) },
                    singleLine = true,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.profileDescription)) },
                    singleLine = true,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = packLabel,
                    onValueChange = { packLabel = it },
                    label = { Text(stringResource(R.string.profilePackLabel)) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // The pack label falls back to the profile name when left empty.
                    onCreate(name.trim(), description.trim(), packLabel.trim().ifEmpty { name.trim() })
                },
                enabled = name.isNotBlank()
            ) { Text(stringResource(R.string.createProfileAction)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    )
}
