package dev.renkinProject.renkin.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import dev.renkinProject.renkin.R

internal enum class GlobalStylePanel(@get:StringRes val title: Int) {
    OPTIONS(R.string.globalOptionsTitle),
    SHADOW(R.string.globalShadowEditorTitle),
    SHADOW_COLOR(R.string.shadowColor),
    SHAPE_COLOR(R.string.shapeColor),
    OUTLINE_COLOR(R.string.outlineColor),
    COLORIZE(R.string.colorize),
    TARGETS(R.string.globalTargetsTitle),
    SOURCES(R.string.globalSourcesTitle),
    HISTORY(R.string.globalHistoryTitle),
    GRADIENTS(R.string.gradientGalleryTitle),
    SAVED_COLORS(R.string.savedColorsTitle);

    val isColorEditor: Boolean
        get() = when (this) {
            SHADOW_COLOR, SHAPE_COLOR, OUTLINE_COLOR, COLORIZE -> true
            else -> false
        }
}

@Stable
internal class GlobalStylePanelState(
    initialPage: GlobalStylePanel = GlobalStylePanel.OPTIONS,
    initialColorTarget: GlobalStylePanel = GlobalStylePanel.COLORIZE
) {
    var page by mutableStateOf(initialPage)
        private set
    var colorTarget by mutableStateOf(initialColorTarget)
        private set

    val parent: GlobalStylePanel
        get() = when (page) {
            GlobalStylePanel.SHADOW_COLOR -> GlobalStylePanel.SHADOW
            GlobalStylePanel.GRADIENTS, GlobalStylePanel.SAVED_COLORS -> colorTarget
            else -> GlobalStylePanel.OPTIONS
        }

    fun open(destination: GlobalStylePanel) {
        if (destination.isColorEditor) colorTarget = destination
        page = destination
    }

    fun back(): Boolean {
        if (page == GlobalStylePanel.OPTIONS) return false
        page = parent
        return true
    }

    companion object {
        val Saver = listSaver<GlobalStylePanelState, String>(
            save = { listOf(it.page.name, it.colorTarget.name) },
            restore = { saved ->
                val colorTarget = GlobalStylePanel.entries.firstOrNull { it.name == saved.getOrNull(1) }
                    ?: GlobalStylePanel.COLORIZE
                GlobalStylePanelState(
                    GlobalStylePanel.entries.firstOrNull { it.name == saved.firstOrNull() } ?: colorTarget,
                    colorTarget
                )
            }
        )
    }
}
