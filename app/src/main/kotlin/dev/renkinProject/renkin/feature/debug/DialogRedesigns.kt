package dev.renkinProject.renkin.feature.debug

import androidx.compose.runtime.Composable

/**
 * Gallery-only redesign proposals, keyed by gallery entry id; each shows as a "New" button next
 * to the entry's current dialog. Approved ones move into the real dialogs and leave this map.
 */
internal fun dialogRedesigns(): Map<String, @Composable (close: () -> Unit) -> Unit> = emptyMap()
