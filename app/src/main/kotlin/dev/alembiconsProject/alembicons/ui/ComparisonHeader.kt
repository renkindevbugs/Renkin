@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.alembiconsProject.alembicons.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.Crossfade
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
import dev.alembiconsProject.alembicons.ui.theme.CardShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.EnterTransition

@Composable
internal fun ComparisonHeader(
    heroBitmap: Bitmap?,
    appName: String,
    previewIcon: IconPackDrawable?,
    previewLoading: Boolean,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onConfirm: () -> Unit,
    // Once the icon list is scrolled, the header shrinks to just the icons + arrow + Apply,
    // dropping the app bar (close/name/overflow) and the Current/New labels to free vertical space.
    collapsed: Boolean = false
) {
    // Both icons fly up into their slots when the dialog opens
    val flyIn = remember { MutableTransitionState(false).apply { targetState = true } }
    val flyInSpec = spring<androidx.compose.ui.unit.IntOffset>(
        Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow
    )
    val flyInEnter = remember(flyInSpec) {
        slideInVertically(flyInSpec) { it * 2 } + fadeIn() + scaleIn(initialScale = 0.5f)
    }

    // On wide screens (tablet / unfolded foldable) the whole header fits on a
    // single row; on phones it stacks into two tiers so nothing gets cramped.
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    if (wide) {
        // Single compact row — close + name on the left, comparison icons centered, apply +
        // overflow on the right. Left and right clusters share equal weight so the icons land
        // dead-center; a long name gets ellipsised before it can reach them.
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            shape = CardShape,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CloseButton(onDismiss)
                    Text(
                        text = appName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(horizontal = 4.dp)
                    )
                }

                Row(verticalAlignment = Alignment.Top) {
                    CurrentSlot(heroBitmap, flyIn, flyInEnter, 44.dp, showLabel = false)
                    ComparisonArrow(44.dp)
                    NewSlot(previewIcon, previewLoading, flyIn, flyInEnter, 44.dp, showLabel = false)
                }

                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)
                ) {
                    ApplyButton(onConfirm)
                    OverflowMenu(onClear)
                }
            }
        }
    } else {
        // Phones: the app bar (close / name / overflow) sits on its own, separate from the card
        // holding the comparison + Apply. Scrolling the icon list hides the Current/New labels —
        // the icons keep their size — so Apply rises tight under them. The label show/hide and
        // the resulting height change animate smoothly (AnimatedVisibility inside each slot).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // The app bar slides/fades away once the list is scrolled, leaving only the
            // comparison card; scrolling back up brings it back. Its bottom padding (the gap
            // above the card) collapses with it, so nothing is left hanging when hidden.
            AnimatedVisibility(visible = !collapsed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CloseButton(onDismiss)
                    Text(
                        text = appName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp)
                    )
                    OverflowMenu(onClear)
                }
            }

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = CardShape,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.Top
                    ) {
                        CurrentSlot(heroBitmap, flyIn, flyInEnter, 56.dp, showLabel = !collapsed)
                        ComparisonArrow(56.dp)
                        NewSlot(previewIcon, previewLoading, flyIn, flyInEnter, 56.dp, showLabel = !collapsed)
                    }
                    ApplyButton(
                        onConfirm,
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    )
                }
            }
        }
    }
}

/** Tonal close button used in both header layouts. */
@Composable
private fun CloseButton(onDismiss: () -> Unit) {
    FilledTonalIconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.dismiss),
            modifier = Modifier.size(20.dp)
        )
    }
}

/** Overflow menu holding the destructive "reset to default" action. */
@Composable
private fun OverflowMenu(onClear: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.moreOptions),
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.resetToDefault),
                        color = MaterialTheme.colorScheme.error
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                onClick = {
                    menuOpen = false
                    onClear()
                }
            )
        }
    }
}

/** The "current" icon, flying up into its slot when the dialog opens. */
@Composable
private fun CurrentSlot(
    heroBitmap: Bitmap?,
    flyIn: MutableTransitionState<Boolean>,
    flyInEnter: EnterTransition,
    size: Dp,
    showLabel: Boolean
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(visibleState = flyIn, enter = flyInEnter) {
            if (heroBitmap != null) {
                Image(
                    painter = BitmapPainter(heroBitmap.asImageBitmap()),
                    contentDescription = null,
                    modifier = Modifier
                        .size(size)
                        .clip(RoundedCornerShape(size / 4))
                )
            } else {
                Surface(
                    modifier = Modifier.size(size),
                    shape = RoundedCornerShape(size / 4),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Face, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(size / 2))
                    }
                }
            }
        }
        // Animated so scrolling the list eases the label (and the height below it) away,
        // letting Apply rise smoothly instead of snapping.
        AnimatedVisibility(visible = showLabel) {
            Text(
                text = stringResource(R.string.iconCurrent),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** The "new" icon preview, with a generating spinner and a primary border once a preview exists. */
@Composable
private fun NewSlot(
    previewIcon: IconPackDrawable?,
    previewLoading: Boolean,
    flyIn: MutableTransitionState<Boolean>,
    flyInEnter: EnterTransition,
    size: Dp,
    showLabel: Boolean
) {
    val borderColor = if (previewIcon != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(visibleState = flyIn, enter = flyInEnter) {
            Surface(
                modifier = Modifier.size(size),
                shape = RoundedCornerShape(size / 4),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(2.dp, borderColor)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // Fade between icons so a regenerated/changed preview eases in
                    // instead of snapping.
                    Crossfade(targetState = previewIcon, label = "previewIcon") { icon ->
                        if (icon != null) {
                            Image(
                                painter = icon.getPainter(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.size(size / 2)
                                )
                            }
                        }
                    }
                    // Spinner over the slot while the new icon is being (re)generated
                    if (previewLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(size / 2),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        AnimatedVisibility(visible = showLabel) {
            Text(
                text = stringResource(R.string.iconNew),
                style = MaterialTheme.typography.labelSmall,
                color = if (previewIcon != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * Auto-mirrored arrow separating the current and new icon slots. Sized to [iconSize] tall and
 * centered within it, so it lines up with the middle of the icons rather than the middle of the
 * whole slot column (which would sit lower, pulled down by the "Current"/"New" labels below).
 */
@Composable
private fun ComparisonArrow(iconSize: Dp) {
    Box(
        modifier = Modifier
            .height(iconSize)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** Primary "apply" action; fires a confirmation haptic before [onConfirm]. */
@Composable
private fun ApplyButton(onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    Button(onClick = { view.performConfirmHaptic(); onConfirm() }, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.Done,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.apply))
    }
}
