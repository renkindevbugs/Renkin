package dev.renkinProject.renkin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.feature.presets.ColorPresetDialog
import dev.renkinProject.renkin.feature.presets.LocalColorPresets
import dev.renkinProject.renkin.feature.presets.SaveColorPresetDialog
import dev.renkinProject.renkin.feature.presets.savedPresetMatching
import dev.renkinProject.renkin.icon.creator.ColorizerStyle

private fun GlobalModifierState.panelColor(target: GlobalStylePanel): ColorizerStyle = when (target) {
    GlobalStylePanel.SHADOW_COLOR -> shadow.style
    GlobalStylePanel.SHAPE_COLOR -> shape.fill?.let { it.style ?: ColorizerStyle(firstColor = it.color) }
        ?: modifiers.effect.colorizer
    GlobalStylePanel.OUTLINE_COLOR -> outline.fill.style ?: ColorizerStyle(firstColor = outline.fill.color)
    else -> modifiers.effect.colorizer
}

private fun GlobalModifierState.updatePanelColor(target: GlobalStylePanel, style: ColorizerStyle) {
    when (target) {
        GlobalStylePanel.SHADOW_COLOR -> updateShadow(shadow.copy(style = style))
        GlobalStylePanel.SHAPE_COLOR -> updateShape { copy(fill = style.asFill()) }
        GlobalStylePanel.OUTLINE_COLOR -> updateOutline { copy(fill = style.asFill()) }
        else -> updateEffect { copy(colorizer = style) }
    }
}

@Composable
internal fun GlobalStyleColorControls(state: GlobalModifierState, navigation: GlobalStylePanelState) {
    val target = navigation.colorTarget
    val style = state.panelColor(target)
    val update: (ColorizerStyle) -> Unit = { state.updatePanelColor(target, it) }
    when (navigation.page) {
        GlobalStylePanel.SAVED_COLORS -> ColorPresetDialog(
            onPick = { update(it); navigation.back() },
            onDismiss = { navigation.back() },
            embedded = true
        )
        GlobalStylePanel.GRADIENTS -> GradientGalleryDialog(
            onUse = { picked ->
                update(style.copy(
                    mode = picked.mode,
                    gradientType = picked.gradientType,
                    firstColor = picked.firstColor,
                    gradientStops = picked.gradientStops,
                    gradientPositions = picked.gradientPositions,
                    gradientAngle = picked.gradientAngle
                ))
            },
            onDismiss = { navigation.back() },
            embedded = true
        )
        else -> ColorizerStyleEditor(
            style = style,
            onStyleChange = update,
            showSingleColorEffects = target == GlobalStylePanel.COLORIZE
        )
    }
}

@Composable
internal fun GlobalStyleColorActions(state: GlobalModifierState, navigation: GlobalStylePanelState) {
    val style = state.panelColor(navigation.colorTarget)
    val store = LocalColorPresets.current
    val saved = savedPresetMatching(store.presets, style)
    var saveOpen by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RenkinTooltipBox(stringResource(R.string.gradientGalleryTitle)) {
            IconButton(onClick = { navigation.open(GlobalStylePanel.GRADIENTS) }) {
                Icon(Icons.Filled.Palette, stringResource(R.string.gradientGalleryTitle))
            }
        }
        RenkinTooltipBox(stringResource(R.string.savedColorsTitle)) {
            IconButton(onClick = { navigation.open(GlobalStylePanel.SAVED_COLORS) }) {
                Icon(Icons.Filled.Bookmarks, stringResource(R.string.savedColorsTitle))
            }
        }
        val saveLabel = stringResource(if (saved == null) R.string.savedColorsSaveTitle else R.string.savedColorsRemoveTitle)
        RenkinTooltipBox(saveLabel) {
            IconButton(onClick = { if (saved == null) saveOpen = true else store.delete(saved.id) }) {
                Icon(if (saved == null) Icons.Filled.BookmarkAdd else Icons.Filled.Bookmark, saveLabel)
            }
        }
    }
    if (saveOpen) SaveColorPresetDialog(style = style, onDismiss = { saveOpen = false })
}
