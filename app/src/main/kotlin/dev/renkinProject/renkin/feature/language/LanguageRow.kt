package dev.renkinProject.renkin.feature.language

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.CheckableDropdownItem
import dev.renkinProject.renkin.ui.SettingsRow

/** Settings row for the app language: the current choice as subtitle, options in a dropdown. */
@Composable
internal fun LanguageRow(selectedTag: String, onSelect: (String) -> Unit) {
    val selected = supportedAppLanguage(selectedTag)
    val followDeviceLabel = stringResource(R.string.languageFollowDevice)
    var open by remember { mutableStateOf(false) }

    Box {
        SettingsRow(
            icon = Icons.Filled.Translate,
            label = stringResource(R.string.language),
            subtitle = selected?.menuLabel() ?: followDeviceLabel,
            onClick = { open = true }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CheckableDropdownItem(followDeviceLabel, checked = selected == null) {
                open = false
                onSelect("")
            }
            SupportedAppLanguages.forEach { language ->
                CheckableDropdownItem(language.menuLabel(), checked = language == selected) {
                    open = false
                    onSelect(language.tag)
                }
            }
        }
    }
}

// Contributed translations can lag behind, so a partial one says how much of the app it covers.
@Composable
private fun AppLanguage.menuLabel(): String {
    val name = "$flag  $nativeName"
    val percent = translatedPercent?.takeIf { it < 100 } ?: return name
    return stringResource(R.string.languageTranslatedShare, name, percent)
}
