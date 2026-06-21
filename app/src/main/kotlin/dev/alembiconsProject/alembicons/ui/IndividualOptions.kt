@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.alembiconsProject.alembicons.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.alembiconsProject.alembicons.MainViewModel
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct
import dev.alembiconsProject.alembicons.data.IconPack
import dev.alembiconsProject.alembicons.data.ImageEdit
import dev.alembiconsProject.alembicons.data.Source
import dev.alembiconsProject.alembicons.data.TextType
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
import dev.alembiconsProject.alembicons.drawable.ResourceDrawable
import dev.alembiconsProject.alembicons.icon.creator.GenerationOptions
import androidx.compose.foundation.layout.Box
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
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow

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
    val viewModel: MainViewModel = hiltViewModel()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var source by rememberSaveable { mutableStateOf(Source.ICON_PACK) }
    var imageEdit by rememberSaveable { mutableStateOf(ImageEdit.NONE) }
    var textType by rememberSaveable { mutableStateOf(TextType.FULL_NAME) }
    var useVector by rememberSaveable { mutableStateOf(false) }
    var useMonochrome by rememberSaveable { mutableStateOf(false) }
    var iconColor by rememberSaveable(saver = colorSaver()) { mutableStateOf(Color.White) }
    var iconPack by rememberSaveable { mutableStateOf(iconPacks.firstOrNull()?.packageName ?: "") }
    // remember (not rememberSaveable): ResourceDrawable holds a live Drawable that isn't
    // Parcelable, so saving the list on stop crashes.
    var customIconList by remember { mutableStateOf<List<ResourceDrawable>>(listOf()) }
    // Start with the icon the app already has so it stays visible (e.g. when only the
    // modifier is being changed) instead of forcing the user to find it again.
    var currentIcon by remember { mutableStateOf(app.createdIcon) }
    // Raw uploaded icon (zoom/adaptive applied) before the shared modifier. The
    // modifier is applied at dialog level (below) so it previews live even from the
    // Modifier tab, instead of only recomputing while the Upload tab is open.
    var uploadBase by remember { mutableStateOf<IconPackDrawable?>(null) }
    var uploadIcon by remember { mutableStateOf<IconPackDrawable?>(null) }
    var vectorIcon by remember { mutableStateOf<IconPackDrawable?>(null) }
    // Hoisted above the tab AnimatedContent so leaving the vector tab and coming back
    // keeps the user's paths instead of disposing the editor and resetting them.
    val vectorEditState = remember { VectorEditState() }
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

    // Slide the editor in from the right; closing plays the reverse animation
    // before the dialog window is actually dismissed
    val dialogTransition = remember { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(dialogTransition.targetState, dialogTransition.isIdle) {
        if (!dialogTransition.targetState && dialogTransition.isIdle) onDismiss()
    }
    val startClose: () -> Unit = { dialogTransition.targetState = false }

    // The Create tab's icon-pack browser is heavy to mount, so defer it until the open
    // animation has settled — the dialog then appears instantly
    val createTabReady by remember {
        derivedStateOf { dialogTransition.isIdle && dialogTransition.currentState }
    }

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
        iconScale = iconScale
    )

    // Drives the spinner over the "New" preview slot while an icon is (re)generated
    var generatingPreview by remember { mutableStateOf(false) }

    LaunchedEffect(generatingOptions, customIconList) {
        if (!optionsInitialized) {
            // Keep the existing icon on the first composition — only regenerate once
            // the user actually changes a source, modifier or selects an icon.
            optionsInitialized = true
            return@LaunchedEffect
        }
        val custom = customIconList.firstOrNull()
        // getIcon / applyModifier hop to Dispatchers.Default internally, so this no
        // longer blocks the main thread; show the spinner for the duration.
        generatingPreview = true
        currentIcon = when {
            // Explicit pick from a pack
            custom != null -> viewModel.appProvider.getIcon(app, generatingOptions, custom)
            // Icon-pack source with no new pick: apply the modifier to the already
            // saved icon rather than pulling a fresh one from the first pack (which
            // would swap the icon out from under the user). Null until a tap if none.
            generatingOptions.primarySource == Source.ICON_PACK ->
                app.createdIcon?.let { viewModel.appProvider.applyModifier(it, generatingOptions) }
            // Text / app-icon sources generate from the source itself
            else -> viewModel.appProvider.getIcon(app, generatingOptions, null)
        }
        generatingPreview = false
    }

    // A hand-edited vector isn't built from a source, so the modifier tab can't go
    // through getIcon — apply the chosen modifier (colorize / path / edge) here, off
    // the main thread (it used to run during composition and jank the dialog).
    var modifiedVector by remember { mutableStateOf<IconPackDrawable?>(null) }
    LaunchedEffect(vectorIcon, generatingOptions) {
        val base = vectorIcon
        modifiedVector = when {
            base == null -> null
            // Only skip when there's truly nothing to apply — scale (iconScale) is applied
            // by applyModifier too, so a scale change with no image-edit must still run it.
            generatingOptions.primaryImageEdit == ImageEdit.NONE && generatingOptions.iconScale == 1f -> base
            else -> {
                generatingPreview = true
                val result = viewModel.appProvider.applyModifier(base, generatingOptions)
                generatingPreview = false
                result
            }
        }
    }

    // Uploaded image: apply the shared modifier (edit / color / scale) here at dialog
    // level so changing it on the Modifier tab updates the preview immediately.
    LaunchedEffect(uploadBase, generatingOptions) {
        val base = uploadBase
        uploadIcon = if (base == null) null
            else viewModel.appProvider.applyModifier(base, generatingOptions)
    }

    // The previewed/confirmed icon follows whichever source produced it, not the
    // open tab — so visiting the modifier tab never silently drops an upload/vector
    val iconToConfirm = when (iconOrigin) {
        IconOrigin.UPLOAD -> uploadIcon ?: currentIcon
        IconOrigin.VECTOR -> modifiedVector
        IconOrigin.CREATE -> currentIcon
    }

    // The modifier needs something to act on — it stays greyed out until then
    val hasIcon = currentIcon != null || uploadBase != null || vectorIcon != null

    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val selectIconMessage = stringResource(R.string.selectIconFirst)

    LaunchedEffect(selectedTab) {
        if (selectedTab != 0) headerCollapsed = false
        // Modifiers live only in the Modifier tab — leaving it starts the next visit clean
        if (selectedTab != 2) {
            imageEdit = ImageEdit.NONE
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
            // No imePadding here: the keyboard should overlay the bottom tabs
            // rather than lifting them. The search field sits near the top, so it
            // stays visible above the keyboard.
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
            Column(Modifier.fillMaxSize()) {
                // Sticky comparison header — close/delete/apply live in the same row
                // and the icons shrink while the icon list is scrolled
                ComparisonHeader(
                    heroBitmap = heroBitmap,
                    appName = app.appName,
                    previewIcon = iconToConfirm,
                    previewLoading = generatingPreview,
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
                                onCollapsedChange = { headerCollapsed = it },
                                contentReady = createTabReady,
                                selectedResourceId = customIconList.firstOrNull()?.resourceId
                            )
                            1 -> UploadColumn(app = app) {
                                uploadBase = it
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
                                onImageEditChange = { imageEdit = it },
                                onColorChange = { iconColor = it },
                                onVectorChange = { useVector = it },
                                onMonochromeChange = { useMonochrome = it },
                                onEdgeThresholdChange = { edgeThreshold = it },
                                onEdgeSmoothingChange = { edgeSmoothing = it },
                                onEdgeContrastChange = { edgeContrast = it },
                                onIconScaleChange = { iconScale = it }
                            )
                            else -> PrepareEditVector(app, vectorEditState) {
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


