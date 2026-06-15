@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package dev.alembiconsProject.alembicons.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct
import dev.alembiconsProject.alembicons.data.IconPack
import dev.alembiconsProject.alembicons.data.ImageEdit
import dev.alembiconsProject.alembicons.data.Source
import dev.alembiconsProject.alembicons.data.TextType
import dev.alembiconsProject.alembicons.data.getImageEditLabels
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
import dev.alembiconsProject.alembicons.drawable.ResourceDrawable
import dev.alembiconsProject.alembicons.icon.creator.GenerationOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/** The source that produced the icon currently being previewed and confirmed. */
private enum class IconOrigin { CREATE, UPLOAD, VECTOR }

@Composable
fun OptionsDialog(
    iconPacks: List<IconPack>,
    app: PackageInfoStruct,
    themed: Boolean,
    onConfirm: (icon: IconPackDrawable?) -> Unit,
    onDismiss: () -> Unit,
    onIconClear: () -> Unit
) {
    val activity = getCurrentMainActivity()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var source by rememberSaveable { mutableStateOf(Source.ICON_PACK) }
    var imageEdit by rememberSaveable { mutableStateOf(ImageEdit.NONE) }
    var textType by rememberSaveable { mutableStateOf(TextType.FULL_NAME) }
    var useVector by rememberSaveable { mutableStateOf(false) }
    var useMonochrome by rememberSaveable { mutableStateOf(false) }
    var iconColor by rememberSaveable(saver = colorSaver()) { mutableStateOf(Color.White) }
    var iconPack by rememberSaveable { mutableStateOf(iconPacks.firstOrNull()?.packageName ?: "") }
    var customIconList by rememberSaveable { mutableStateOf<List<ResourceDrawable>>(listOf()) }
    // Start with the icon the app already has so it stays visible (e.g. when only the
    // modifier is being changed) instead of forcing the user to find it again.
    var currentIcon by remember { mutableStateOf(app.createdIcon) }
    var uploadIcon by remember { mutableStateOf<IconPackDrawable?>(null) }
    var vectorIcon by remember { mutableStateOf<IconPackDrawable?>(null) }
    // Which source actually produced the icon being previewed/confirmed. Without
    // this the modifier tab would fall back to the create-source icon and wipe a
    // freshly built vector (or upload) just by switching tabs.
    var iconOrigin by remember { mutableStateOf(IconOrigin.CREATE) }
    var showConfirmClear by remember { mutableStateOf(false) }
    var headerCollapsed by remember { mutableStateOf(false) }
    var optionsInitialized by remember { mutableStateOf(false) }
    var edgeThreshold by rememberSaveable { mutableFloatStateOf(2.5f) }
    var edgeSmoothing by rememberSaveable { mutableFloatStateOf(2f) }
    var edgeContrast by rememberSaveable { mutableStateOf(false) }
    var iconScale by rememberSaveable { mutableFloatStateOf(1f) }
    var removeBackground by rememberSaveable { mutableStateOf(false) }

    // Slide the editor in from the right; closing plays the reverse animation
    // before the dialog window is actually dismissed
    val dialogTransition = remember { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(dialogTransition.targetState, dialogTransition.isIdle) {
        if (!dialogTransition.targetState && dialogTransition.isIdle) onDismiss()
    }
    val startClose: () -> Unit = { dialogTransition.targetState = false }

    val heroBitmap = remember(app.icon) {
        try {
            val d = app.icon
            if (d is BitmapDrawable) {
                d.bitmap
            } else {
                val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else 96
                val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else 96
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, w, h)
                d.draw(Canvas(bmp))
                bmp
            }
        } catch (_: Exception) { null }
    }

    val generatingOptions = GenerationOptions(
        source, imageEdit, textType, iconPack,
        iconColor.toInt(), 0, useVector, useMonochrome, themed, override = true,
        edgeLowThreshold = edgeThreshold,
        edgeHighThreshold = edgeThreshold * 3f,
        edgeGaussianRadius = edgeSmoothing,
        edgeContrastNormalized = edgeContrast,
        iconScale = iconScale,
        removeBackground = removeBackground
    )

    LaunchedEffect(generatingOptions, customIconList) {
        if (!optionsInitialized) {
            // Keep the existing icon on the first composition — only regenerate once
            // the user actually changes a source, modifier or selects an icon.
            optionsInitialized = true
            return@LaunchedEffect
        }
        if (generatingOptions.primarySource == Source.ICON_PACK
            && customIconList.isEmpty() && app.createdIcon == null) {
            // Never auto-pick an icon from a pack — wait for an explicit tap
            currentIcon = null
            return@LaunchedEffect
        }
        val custom = customIconList.firstOrNull()
        currentIcon = activity.appProvider.getIcon(app, generatingOptions, custom)
    }

    // A hand-edited vector isn't built from a source, so the modifier tab can't go
    // through getIcon — apply the chosen modifier (colorize / path / edge) to it here
    val modifiedVector = remember(vectorIcon, generatingOptions) {
        val base = vectorIcon ?: return@remember null
        if (generatingOptions.primaryImageEdit == ImageEdit.NONE) base
        else activity.appProvider.applyModifier(base, generatingOptions)
    }

    // The previewed/confirmed icon follows whichever source produced it, not the
    // open tab — so visiting the modifier tab never silently drops an upload/vector
    val iconToConfirm = when (iconOrigin) {
        IconOrigin.UPLOAD -> uploadIcon ?: currentIcon
        IconOrigin.VECTOR -> modifiedVector
        IconOrigin.CREATE -> currentIcon
    }

    // The modifier needs something to act on — it stays greyed out until then
    val hasIcon = currentIcon != null || uploadIcon != null || vectorIcon != null

    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val selectIconMessage = stringResource(R.string.selectIconFirst)

    LaunchedEffect(selectedTab) {
        if (selectedTab != 0) headerCollapsed = false
        // Modifiers live only in the Modifier tab — leaving it starts the next visit clean
        if (selectedTab != 2) {
            imageEdit = ImageEdit.NONE
            iconScale = 1f
            removeBackground = false
        }
    }

    Dialog(
        onDismissRequest = startClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        AnimatedVisibility(
            visibleState = dialogTransition,
            enter = slideInHorizontally(
                spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)
            ) { it } + fadeIn(),
            exit = slideOutHorizontally(
                spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium)
            ) { it } + fadeOut()
        ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .imePadding()
            ) {
            Column(Modifier.fillMaxSize()) {
                // Sticky comparison header — close/delete/apply live in the same row
                // and the icons shrink while the icon list is scrolled
                ComparisonHeader(
                    heroBitmap = heroBitmap,
                    appName = app.appName,
                    previewIcon = iconToConfirm,
                    collapsed = headerCollapsed,
                    onDismiss = startClose,
                    onClear = { showConfirmClear = true },
                    onConfirm = { onConfirm(iconToConfirm) }
                )

                // The Create tab draws its own divider under the search bar;
                // the other tabs get one right below the header
                if (selectedTab != 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }

                // Tab content
                Box(modifier = Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = {
                            (fadeIn(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)) +
                             slideInVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) { it / 8 }) togetherWith
                            (fadeOut(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium)) +
                             slideOutVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) { -it / 8 })
                        },
                        label = "tabContent"
                    ) { tab ->
                        when (tab) {
                            0 -> CreateTab(
                                source = source,
                                iconPacks = iconPacks,
                                options = generatingOptions,
                                textType = textType,
                                appName = app.appName,
                                onIconSelect = { res, pack ->
                                    customIconList = listOf(res)
                                    iconPack = pack.packageName
                                    iconOrigin = IconOrigin.CREATE
                                },
                                onTextTypeChange = { textType = it; iconOrigin = IconOrigin.CREATE },
                                onCollapsedChange = { headerCollapsed = it }
                            )
                            1 -> UploadColumn(
                                app = app,
                                imageEdit = imageEdit,
                                iconColor = iconColor
                            ) {
                                uploadIcon = it
                                if (it != null) iconOrigin = IconOrigin.UPLOAD
                            }
                            2 -> ModifierTab(
                                source = source,
                                imageEdit = imageEdit,
                                iconColor = iconColor,
                                useVector = useVector,
                                useMonochrome = useMonochrome,
                                edgeThreshold = edgeThreshold,
                                edgeSmoothing = edgeSmoothing,
                                edgeContrast = edgeContrast,
                                iconScale = iconScale,
                                removeBackground = removeBackground,
                                onImageEditChange = { imageEdit = it },
                                onColorChange = { iconColor = it },
                                onVectorChange = { useVector = it },
                                onMonochromeChange = { useMonochrome = it },
                                onEdgeThresholdChange = { edgeThreshold = it },
                                onEdgeSmoothingChange = { edgeSmoothing = it },
                                onEdgeContrastChange = { edgeContrast = it },
                                onIconScaleChange = { iconScale = it },
                                onRemoveBackgroundChange = { removeBackground = it }
                            )
                            else -> PrepareEditVector(app) {
                                vectorIcon = it
                                if (it != null) iconOrigin = IconOrigin.VECTOR
                            }
                        }
                    }
                }

                // Source pills — only when Create tab is active
                AnimatedVisibility(visible = selectedTab == 0) {
                    SourcePills(source = source) { newSource ->
                        source = newSource
                        customIconList = listOf()
                        iconOrigin = IconOrigin.CREATE
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Bottom navigation — Modifier sits last and stays greyed out
                // until an icon is chosen, since it only edits an existing icon
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Filled.Refresh, null) },
                        label = { Text(stringResource(R.string.create)) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Filled.Face, null) },
                        label = { Text(stringResource(R.string.upload)) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Filled.Create, null) },
                        label = { Text(stringResource(R.string.editVector)) }
                    )
                    val disabledTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = {
                            if (hasIcon) {
                                selectedTab = 2
                            } else {
                                snackbarScope.launch {
                                    snackbarHostState.showSnackbar(selectIconMessage)
                                }
                            }
                        },
                        // When enabled, let NavigationBarItem apply its own selected/unselected
                        // colours (matching the other tabs); only override when disabled
                        icon = {
                            if (hasIcon) {
                                Icon(Icons.Filled.Tune, null)
                            } else {
                                Icon(Icons.Filled.Tune, null, tint = disabledTint)
                            }
                        },
                        label = {
                            if (hasIcon) {
                                Text(stringResource(R.string.modifierTab))
                            } else {
                                Text(stringResource(R.string.modifierTab), color = disabledTint)
                            }
                        }
                    )
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
            )
            }
        }
        }
    }

    if (showConfirmClear) {
        ConfirmClearDialog(
            onDismiss = { showConfirmClear = false },
            onIconClear = {
                showConfirmClear = false
                onIconClear()
            }
        )
    }
}


@Composable
fun ConfirmClearDialog(onDismiss: () -> Unit, onIconClear: () -> Unit) {
    AlertDialog(
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.outline,
        onDismissRequest = { onDismiss() },
        title = { Text(stringResource(R.string.confirmClear)) },
        text = {
            Text(stringResource(R.string.confirmClearText))
        },
        confirmButton = {
            IconButton(onClick = {
                onDismiss()
                onIconClear()
            }) {
                Icon(
                    imageVector = Icons.Filled.Done,
                    contentDescription = stringResource(R.string.confirm),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = {
            IconButton(onClick = {
                onDismiss()
            }) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.dismiss),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    )
}

@Composable
private fun ComparisonHeader(
    heroBitmap: Bitmap?,
    appName: String,
    previewIcon: IconPackDrawable?,
    collapsed: Boolean,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onConfirm: () -> Unit
) {
    val iconSize by animateDpAsState(
        targetValue = if (collapsed) 36.dp else 64.dp,
        animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
        label = "headerIconSize"
    )
    val verticalPadding by animateDpAsState(
        targetValue = if (collapsed) 6.dp else 12.dp,
        animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
        label = "headerPadding"
    )

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilledTonalIconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.dismiss),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Both icons fly up into their header slots when the dialog opens
            val flyIn = remember { MutableTransitionState(false).apply { targetState = true } }
            val flyInSpec = spring<androidx.compose.ui.unit.IntOffset>(
                Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow
            )

            AnimatedVisibility(
                visibleState = flyIn,
                enter = slideInVertically(flyInSpec) { it * 2 } + fadeIn() +
                        scaleIn(initialScale = 0.5f)
            ) {
                if (heroBitmap != null) {
                    Image(
                        painter = BitmapPainter(heroBitmap.asImageBitmap()),
                        contentDescription = null,
                        modifier = Modifier
                            .size(iconSize)
                            .clip(RoundedCornerShape(iconSize / 4))
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(iconSize),
                        shape = RoundedCornerShape(iconSize / 4),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Face, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(iconSize / 2))
                        }
                    }
                }
            }

            Text(
                text = "→",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            val borderColor = if (previewIcon != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            AnimatedVisibility(
                visibleState = flyIn,
                enter = slideInVertically(flyInSpec) { it * 2 } + fadeIn() +
                        scaleIn(initialScale = 0.5f)
            ) {
                Surface(
                    modifier = Modifier.size(iconSize),
                    shape = RoundedCornerShape(iconSize / 4),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(2.dp, borderColor)
                ) {
                    if (previewIcon != null) {
                        Image(
                            painter = previewIcon.getPainter(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.size(iconSize / 2)
                            )
                        }
                    }
                }
            }

            Text(
                text = appName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 2.dp)
            )

            IconButton(onClick = onClear, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.clearIcons),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
            FilledIconButton(onClick = onConfirm, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.Done,
                    contentDescription = stringResource(R.string.confirm),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun SourcePills(
    source: Source,
    onSourceChange: (Source) -> Unit
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        SegmentedButton(
            selected = source == Source.ICON_PACK,
            onClick = { onSourceChange(Source.ICON_PACK) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
        ) { Text(stringResource(R.string.iconPack)) }
        SegmentedButton(
            selected = source == Source.APPLICATION_ICON,
            onClick = { onSourceChange(Source.APPLICATION_ICON) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
        ) { Text(stringResource(R.string.sourceAppIcon)) }
        SegmentedButton(
            selected = source == Source.APPLICATION_NAME,
            onClick = { onSourceChange(Source.APPLICATION_NAME) },
            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
        ) { Text(stringResource(R.string.sourceText)) }
    }
}

@Composable
private fun ModifierTab(
    source: Source,
    imageEdit: ImageEdit,
    iconColor: Color,
    useVector: Boolean,
    useMonochrome: Boolean,
    edgeThreshold: Float,
    edgeSmoothing: Float,
    edgeContrast: Boolean,
    iconScale: Float,
    removeBackground: Boolean,
    onImageEditChange: (ImageEdit) -> Unit,
    onColorChange: (Color) -> Unit,
    onVectorChange: (Boolean) -> Unit,
    onMonochromeChange: (Boolean) -> Unit,
    onEdgeThresholdChange: (Float) -> Unit,
    onEdgeSmoothingChange: (Float) -> Unit,
    onEdgeContrastChange: (Boolean) -> Unit,
    onIconScaleChange: (Float) -> Unit,
    onRemoveBackgroundChange: (Boolean) -> Unit
) {
    val editLabels = getImageEditLabels()
    var colorPickerOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.imageEdit),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        editLabels.forEach { (edit, label) ->
            val selected = imageEdit == edit
            Surface(
                onClick = { onImageEditChange(edit) },
                shape = RoundedCornerShape(20.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f)
                    )
                    if (selected) {
                        Icon(
                            imageVector = Icons.Filled.Done,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        if (imageEdit == ImageEdit.EDGE) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    // Detail: inverse of the Canny threshold — right = more edges kept
                    val detail = 1f - (edgeThreshold - 0.5f) / 4.5f
                    Text(
                        text = stringResource(R.string.edgeDetail),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = detail.coerceIn(0f, 1f),
                        onValueChange = { onEdgeThresholdChange(0.5f + (1f - it) * 4.5f) },
                        valueRange = 0f..1f
                    )
                    Text(
                        text = stringResource(R.string.edgeSmoothing),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = edgeSmoothing,
                        onValueChange = { onEdgeSmoothingChange(it) },
                        valueRange = 0.5f..4f
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.edgeContrast),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = edgeContrast,
                            onCheckedChange = { onEdgeContrastChange(it) }
                        )
                    }
                }
            }
        }

        if (imageEdit != ImageEdit.NONE) {
            Surface(
                onClick = { colorPickerOpen = true },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.iconColor),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = CircleShape,
                        color = iconColor,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.size(28.dp)
                    ) {}
                }
            }
        }

        if (isPathTracingEnabled(source, imageEdit)) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    VectorSwitch(useVector) { onVectorChange(it) }
                    MonochromeSwitch(useMonochrome) { onMonochromeChange(it) }
                }
            }
        }

        // Per-icon adjustments, independent of the modifier chosen above
        Text(
            text = stringResource(R.string.adjustments),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.removeBackground),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = removeBackground,
                        onCheckedChange = { onRemoveBackgroundChange(it) }
                    )
                }
                Text(
                    text = stringResource(R.string.iconScale),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                // Centred at 1.0: left shrinks (padding), right enlarges (zoom)
                Slider(
                    value = iconScale,
                    onValueChange = { onIconScaleChange(it) },
                    valueRange = 0.5f..1.5f
                )
            }
        }
    }

    if (colorPickerOpen) {
        ColorDialog(
            onDismiss = { colorPickerOpen = false },
            currentlySelected = iconColor,
            onColorSelected = { onColorChange(it) }
        )
    }
}

