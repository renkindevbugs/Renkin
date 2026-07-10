@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import dev.renkinProject.renkin.R
import androidx.compose.ui.window.Dialog
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.ui.theme.DialogShape
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.ui.graphics.Color
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
    // Drives the app bar's enter-always collapse (pixel-tied to the icon list's scroll). Null on
    // wide screens, where the header is a single static row.
    scrollBehavior: TopAppBarScrollBehavior? = null,
    // How much of the Current/New labels to show: 1 = full (list at the very top), 0 = gone. Pixel-
    // tied to the list's distance from the top, so the labels only return when scrolled fully up.
    labelExpand: Float = 1f,
    // Create-tab chrome (Mihon-style): [titleContent] replaces the app-name title (the inline
    // search field), [extraActions] adds buttons before the overflow (the sort menu),
    // [onNavigateBack] swaps the close button for a back arrow (collapses the pack grid first),
    // and [showProgress] draws a thin indeterminate line under the bar while icons still load.
    titleContent: (@Composable () -> Unit)? = null,
    extraActions: (@Composable () -> Unit)? = null,
    onNavigateBack: (() -> Unit)? = null,
    showProgress: Boolean = false
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
                    NavigationButton(onNavigateBack, onDismiss)
                    if (titleContent != null) {
                        Box(Modifier.weight(1f)) { titleContent() }
                    } else {
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
                }

                Row(verticalAlignment = Alignment.Top) {
                    CurrentSlot(heroBitmap, flyIn, flyInEnter, 44.dp, labelExpand = 0f)
                    ComparisonArrow(44.dp)
                    NewSlot(previewIcon, previewLoading, flyIn, flyInEnter, 44.dp, labelExpand = 0f)
                }

                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)
                ) {
                    ApplyButton(onConfirm)
                    extraActions?.invoke()
                    OverflowMenu(onClear)
                }
            }
        }
    } else {
        // Phones: the app bar (close / name / overflow) sits on its own, separate from the card
        // holding the comparison + Apply. Scrolling the icon list hides the Current/New labels —
        // the icons keep their size — so Apply rises tight under them. The label show/hide and
        // the resulting height change animate smoothly (AnimatedVisibility inside each slot).
        Column(Modifier.fillMaxWidth()) {
            // Real Material top app bar so its collapse is pixel-tied to the scroll (enter-always):
            // scrolling down slides it up out of view, scrolling up pulls it straight back, and you
            // can stop mid-way. Transparent — the dialog surface shows through — and no status-bar
            // inset of its own (the dialog already applies statusBarsPadding).
            TopAppBar(
                title = {
                    if (titleContent != null) titleContent()
                    else Text(
                        text = appName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = { NavigationButton(onNavigateBack, onDismiss) },
                actions = {
                    extraActions?.invoke()
                    OverflowMenu(onClear)
                },
                scrollBehavior = scrollBehavior,
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
            // Mihon-style activity line right under the bar: visible while pack rows are still
            // resolving the query, so "search finished" is unambiguous.
            if (showProgress) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                )
            }

            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
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
                        CurrentSlot(heroBitmap, flyIn, flyInEnter, 56.dp, labelExpand = labelExpand)
                        ComparisonArrow(56.dp)
                        NewSlot(previewIcon, previewLoading, flyIn, flyInEnter, 56.dp, labelExpand = labelExpand)
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

/** Back arrow (Create tab, [onNavigateBack] set) or the tonal close button. */
@Composable
private fun NavigationButton(onNavigateBack: (() -> Unit)?, onDismiss: () -> Unit) {
    if (onNavigateBack != null) {
        IconButton(onClick = onNavigateBack, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.dismiss),
                modifier = Modifier.size(22.dp)
            )
        }
    } else {
        CloseButton(onDismiss)
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
    labelExpand: Float
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
        // Pixel-tied to the scroll: the label's height and opacity track [labelExpand], so it
        // shrinks/fades away as the list leaves the top and Apply rises smoothly with it.
        Text(
            text = stringResource(R.string.iconCurrent),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.collapsibleHeight { labelExpand }.padding(top = 4.dp)
        )
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
    labelExpand: Float
) {
    val borderColor = if (previewIcon != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    // Tapping the preview blows it up, so the result is judgeable before applying.
    var enlarged by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(visibleState = flyIn, enter = flyInEnter) {
            Surface(
                modifier = Modifier
                    .size(size)
                    .clickable(enabled = previewIcon != null) { enlarged = true },
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
        Text(
            text = stringResource(R.string.iconNew),
            style = MaterialTheme.typography.labelSmall,
            color = if (previewIcon != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.collapsibleHeight { labelExpand }.padding(top = 4.dp)
        )
    }

    if (enlarged && previewIcon != null) {
        EnlargedIconDialog(previewIcon) { enlarged = false }
    }
}

/** Blown-up look at the new icon — the 56dp slot hides detail the launcher will show. */
@Composable
private fun EnlargedIconDialog(icon: IconPackDrawable, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = DialogShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            // Tapping the blown-up icon closes it again — no chrome needed.
            modifier = Modifier.clickable(onClick = onDismiss)
        ) {
            Box(Modifier.padding(32.dp), contentAlignment = Alignment.Center) {
                Image(
                    painter = icon.getPainter(),
                    contentDescription = stringResource(R.string.iconNew),
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(60.dp))
                )
            }
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
