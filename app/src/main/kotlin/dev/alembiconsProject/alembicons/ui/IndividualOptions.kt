@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package dev.alembiconsProject.alembicons.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.Red
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.EmptyPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct
import dev.alembiconsProject.alembicons.data.IconPack
import dev.alembiconsProject.alembicons.data.ImageEdit
import dev.alembiconsProject.alembicons.data.Source
import dev.alembiconsProject.alembicons.data.TextType
import dev.alembiconsProject.alembicons.data.UploadedImageStore
import dev.alembiconsProject.alembicons.data.getImageEditLabels
import dev.alembiconsProject.alembicons.drawable.BitmapIconDrawable
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
import dev.alembiconsProject.alembicons.drawable.ImageVectorDrawable
import dev.alembiconsProject.alembicons.drawable.InsetIconDrawable
import dev.alembiconsProject.alembicons.drawable.ResourceDrawable
import dev.alembiconsProject.alembicons.drawable.shrinkIfBiggerThan
import dev.alembiconsProject.alembicons.drawable.toSafeBitmapOrNull
import dev.alembiconsProject.alembicons.extension.toDrawable
import dev.alembiconsProject.alembicons.icon.creator.GenerationOptions
import dev.alembiconsProject.alembicons.packages.ApplicationManager
import dev.alembiconsProject.alembicons.extension.createEmptyVector
import dev.alembiconsProject.alembicons.extension.getBuilder
import dev.alembiconsProject.alembicons.drawable.toImageVectorDrawable
import dev.alembiconsProject.alembicons.drawable.MutableVectorPath
import dev.alembiconsProject.alembicons.vector.PathExporter.Companion.toStringPath
import dev.alembiconsProject.alembicons.vector.VectorEditor.Companion.applyAndRemoveGroup
import dev.alembiconsProject.alembicons.vector.VectorEditor.Companion.center
import java.io.File
import java.io.InputStream
import kotlin.math.max
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.Sort
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

const val MIME_TYPE_IMAGE = "image/*"

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
    var showConfirmClear by remember { mutableStateOf(false) }
    var headerCollapsed by remember { mutableStateOf(false) }
    var optionsInitialized by remember { mutableStateOf(false) }
    var edgeThreshold by rememberSaveable { mutableFloatStateOf(2.5f) }
    var edgeSmoothing by rememberSaveable { mutableFloatStateOf(2f) }
    var edgeContrast by rememberSaveable { mutableStateOf(false) }

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
        edgeContrastNormalized = edgeContrast
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

    // On the Upload tab keep showing the already chosen icon until a gallery
    // image is actually selected
    val iconToConfirm = when (selectedTab) {
        1 -> uploadIcon ?: currentIcon
        3 -> vectorIcon
        else -> currentIcon
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab != 0) headerCollapsed = false
        // Modifiers live only in the Modifier tab — leaving it starts the next visit clean
        if (selectedTab != 2) imageEdit = ImageEdit.NONE
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
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .imePadding()
            ) {
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
                                },
                                onTextTypeChange = { textType = it },
                                onCollapsedChange = { headerCollapsed = it }
                            )
                            1 -> UploadColumn(
                                app = app,
                                imageEdit = imageEdit,
                                iconColor = iconColor
                            ) { uploadIcon = it }
                            2 -> ModifierTab(
                                source = source,
                                imageEdit = imageEdit,
                                iconColor = iconColor,
                                useVector = useVector,
                                useMonochrome = useMonochrome,
                                edgeThreshold = edgeThreshold,
                                edgeSmoothing = edgeSmoothing,
                                edgeContrast = edgeContrast,
                                onImageEditChange = { imageEdit = it },
                                onColorChange = { iconColor = it },
                                onVectorChange = { useVector = it },
                                onMonochromeChange = { useMonochrome = it },
                                onEdgeThresholdChange = { edgeThreshold = it },
                                onEdgeSmoothingChange = { edgeSmoothing = it },
                                onEdgeContrastChange = { edgeContrast = it }
                            )
                            else -> PrepareEditVector(app) { vectorIcon = it }
                        }
                    }
                }

                // Source pills — only when Create tab is active
                AnimatedVisibility(visible = selectedTab == 0) {
                    SourcePills(source = source) { newSource ->
                        source = newSource
                        customIconList = listOf()
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Bottom navigation
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
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Filled.Tune, null) },
                        label = { Text(stringResource(R.string.modifierTab)) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Filled.Create, null) },
                        label = { Text(stringResource(R.string.editVector)) }
                    )
                }
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
    onImageEditChange: (ImageEdit) -> Unit,
    onColorChange: (Color) -> Unit,
    onVectorChange: (Boolean) -> Unit,
    onMonochromeChange: (Boolean) -> Unit,
    onEdgeThresholdChange: (Float) -> Unit,
    onEdgeSmoothingChange: (Float) -> Unit,
    onEdgeContrastChange: (Boolean) -> Unit
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
    }

    if (colorPickerOpen) {
        ColorDialog(
            onDismiss = { colorPickerOpen = false },
            currentlySelected = iconColor,
            onColorSelected = { onColorChange(it) }
        )
    }
}

@Composable
fun CreateTab(
    source: Source,
    iconPacks: List<IconPack>,
    options: GenerationOptions,
    textType: TextType,
    appName: String,
    onIconSelect: (ResourceDrawable, IconPack) -> Unit,
    onTextTypeChange: (TextType) -> Unit,
    onCollapsedChange: (Boolean) -> Unit = {}
) {
    var searchQuery by rememberSaveable { mutableStateOf(appName) }
    var debouncedQuery by rememberSaveable { mutableStateOf(appName) }
    var sortOrder by rememberSaveable { mutableStateOf(IconSortOrder.NAME_ASC) }
    var showSortMenu by remember { mutableStateOf(false) }
    var expandedPack by remember { mutableStateOf<IconPack?>(null) }
    var collapsed by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val listScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 60 }
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery != debouncedQuery) {
            delay(300)
            debouncedQuery = searchQuery
        }
    }

    LaunchedEffect(listScrolled, expandedPack) {
        if (expandedPack == null) collapsed = listScrolled
    }

    LaunchedEffect(collapsed, source) {
        onCollapsedChange(collapsed && source == Source.ICON_PACK)
    }

    BackHandler(enabled = expandedPack != null) {
        expandedPack = null
    }

    Column(Modifier.fillMaxSize()) {
        if (source == Source.ICON_PACK) {
            // Search bar slides away while the icon list is scrolled to save space
            AnimatedVisibility(
                visible = !collapsed,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    shape = CircleShape,
                    placeholder = { Text(stringResource(R.string.searchIcons)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.Filled.Sort, "Sort", tint = MaterialTheme.colorScheme.primary)
                    }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("A → Z") },
                            onClick = { sortOrder = IconSortOrder.NAME_ASC; showSortMenu = false },
                            leadingIcon = if (sortOrder == IconSortOrder.NAME_ASC) {
                                { Icon(Icons.Filled.Done, null, tint = MaterialTheme.colorScheme.primary) }
                            } else null
                        )
                        DropdownMenuItem(
                            text = { Text("Z → A") },
                            onClick = { sortOrder = IconSortOrder.NAME_DESC; showSortMenu = false },
                            leadingIcon = if (sortOrder == IconSortOrder.NAME_DESC) {
                                { Icon(Icons.Filled.Done, null, tint = MaterialTheme.colorScheme.primary) }
                            } else null
                        )
                    }
                }
            }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        when (source) {
            Source.ICON_PACK -> {
                val detailPack = expandedPack
                if (detailPack != null) {
                    PackDetailGrid(
                        iconPack = detailPack,
                        options = options,
                        sortOrder = sortOrder,
                        query = debouncedQuery,
                        onBack = { expandedPack = null },
                        onCollapsedChange = { collapsed = it },
                        onSelect = { resource, _ -> onIconSelect(resource, detailPack) }
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        iconPacks.distinctBy { it.packageName }.forEach { pack ->
                            item(key = "${pack.packageName}_header") {
                                PackSectionHeader(pack) { expandedPack = pack }
                            }
                            item(key = "${pack.packageName}_icons") {
                                PackIconsRow(
                                    iconPack = pack,
                                    options = options,
                                    sortOrder = sortOrder,
                                    query = debouncedQuery,
                                    onMore = { expandedPack = pack }
                                ) { resource, _ ->
                                    onIconSelect(resource, pack)
                                }
                            }
                        }
                    }
                }
            }
            Source.APPLICATION_ICON -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.applicationIcon),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Source.APPLICATION_NAME -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                TextTypeDropdown(R.string.textType, textType) { onTextTypeChange(it) }
            }
            else -> {}
        }
    }
}

@Composable
fun UploadColumn(app: PackageInfoStruct,
                 imageEdit: ImageEdit,
                 iconColor: Color,
                 onChange: (icon: IconPackDrawable?) -> Unit) {
    var asAdaptiveIcon by rememberSaveable { mutableStateOf(false) }
    var zoomLevel by rememberSaveable { mutableFloatStateOf(1f) }
    var selectedImagePath by rememberSaveable { mutableStateOf<String?>(null) }
    var savedImages by remember { mutableStateOf<List<File>>(emptyList()) }
    var uploadedImage by remember { mutableStateOf(null as Bitmap?) }
    var modifiedImage by remember { mutableStateOf(null as Bitmap?) }
    var mask by remember { mutableStateOf(null as Bitmap?) }
    var uploadError by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var markedForDelete by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val maxSize = 500

    val activity = getCurrentMainActivity()
    val context = getCurrentContext()
    val res = context.resources
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        savedImages = withContext(Dispatchers.IO) { UploadedImageStore.list(context) }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var failed = false
            val added = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    val bitmap = getBitmapFromURI(context, uri)?.toDrawable(res)?.shrinkIfBiggerThan(maxSize)
                    if (bitmap == null) {
                        failed = true
                        null
                    } else {
                        UploadedImageStore.save(context, bitmap)
                    }
                }
            }
            savedImages = withContext(Dispatchers.IO) { UploadedImageStore.list(context) }
            if (added.isNotEmpty()) selectedImagePath = added.first().absolutePath
            if (failed) uploadError = true
        }
    }

    LaunchedEffect(selectedImagePath) {
        val path = selectedImagePath
        if (path == null) {
            uploadedImage = null
            modifiedImage = null
            onChange(null)
            return@LaunchedEffect
        }
        val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
        if (bitmap != null) {
            val squared = squareBitmap(bitmap)
            uploadedImage = squared
            mask = createMask(squared)
        } else {
            uploadError = true
        }
    }

    // The bottom-bar Modifier tab drives image edits for the uploaded image too
    LaunchedEffect(uploadedImage, imageEdit, iconColor) {
        val image = uploadedImage ?: return@LaunchedEffect
        val generatingOptions = GenerationOptions(Source.ICON_PACK, imageEdit, TextType.FULL_NAME, "", iconColor.toInt(), 0, false, false, false, true)
        modifiedImage = activity.appProvider.getIcon(app, generatingOptions, ResourceDrawable(0, image.toDrawable(res)))?.toBitmap()
    }

    if (uploadError) {
        ShowToast(stringResource(R.string.uploadImageError))
        uploadError = false
    }

    BackHandler(enabled = selectionMode) {
        selectionMode = false
        markedForDelete = emptySet()
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "add", span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Button(onClick = { launcher.launch(MIME_TYPE_IMAGE) }) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Text(
                        text = stringResource(R.string.addImages),
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }

        val editedImage = modifiedImage
        if (editedImage != null) {
            item(key = "editor", span = { GridItemSpan(maxLineSpan) }) {
                val zoomedImage = zoomBitmap(editedImage, zoomLevel)

                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        Image(
                            painter = BitmapPainter(editedImage.asImageBitmap()),
                            contentDescription = null,
                            modifier = Modifier
                                .padding(2.dp)
                                .size(108.dp, 108.dp)
                        )

                        if (asAdaptiveIcon) {
                            Image(
                                painter = BitmapPainter(zoomedImage.asImageBitmap()),
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(2.dp)
                                    .size(108.dp, 108.dp)
                                    .drawWithContent {
                                        drawContent()
                                        drawImage(
                                            mask!!.asImageBitmap(),
                                            srcSize = IntSize(mask!!.width, mask!!.height),
                                            dstSize = IntSize(
                                                this.size.width.toInt(),
                                                this.size.height.toInt()
                                            ),
                                            blendMode = BlendMode.Overlay
                                        )
                                    }
                            )
                        }
                    }

                    if (asAdaptiveIcon) {
                        Text(stringResource(R.string.deadZone), color = Red)
                    }

                    AdaptiveIconSwitch(asAdaptiveIcon, onChange = { asAdaptiveIcon = it; zoomLevel = 1f })

                    if (asAdaptiveIcon) {
                        ZoomSlider(zoomLevel, onChange = { zoomLevel = it })
                    }

                    onChange(BitmapIconDrawable(zoomedImage, asAdaptiveIcon))
                }
            }
        }

        item(key = "gallery_header", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (selectionMode) {
                            "${markedForDelete.size}"
                        } else {
                            stringResource(R.string.yourImages)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (selectionMode) {
                        IconButton(onClick = {
                            selectionMode = false
                            markedForDelete = emptySet()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.dismiss),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { showDeleteConfirm = true },
                            enabled = markedForDelete.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.deleteImage),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }

        if (savedImages.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(R.string.noImagesYet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        } else {
            items(savedImages, key = { it.absolutePath }) { file ->
                val path = file.absolutePath
                UploadedImageThumbnail(
                    file = file,
                    selected = !selectionMode && path == selectedImagePath,
                    marked = selectionMode && path in markedForDelete,
                    onClick = {
                        if (selectionMode) {
                            markedForDelete = if (path in markedForDelete) {
                                markedForDelete - path
                            } else {
                                markedForDelete + path
                            }
                        } else {
                            // Tapping the selected image again deselects it and the header
                            // falls back to the previously chosen icon
                            selectedImagePath = if (path == selectedImagePath) null else path
                        }
                    },
                    onLongClick = {
                        if (!selectionMode) {
                            selectionMode = true
                            markedForDelete = setOf(path)
                        }
                    }
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.outline,
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.deleteImage)) },
            text = { Text(stringResource(R.string.deleteImageText)) },
            confirmButton = {
                IconButton(onClick = {
                    showDeleteConfirm = false
                    val toDelete = savedImages.filter { it.absolutePath in markedForDelete }
                    selectionMode = false
                    markedForDelete = emptySet()
                    scope.launch {
                        withContext(Dispatchers.IO) { toDelete.forEach { UploadedImageStore.delete(it) } }
                        savedImages = withContext(Dispatchers.IO) { UploadedImageStore.list(context) }
                        if (toDelete.any { it.absolutePath == selectedImagePath }) selectedImagePath = null
                    }
                }) {
                    Icon(
                        imageVector = Icons.Filled.Done,
                        contentDescription = stringResource(R.string.confirm),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            },
            dismissButton = {
                IconButton(onClick = { showDeleteConfirm = false }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.dismiss),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        )
    }
}

@Composable
private fun UploadedImageThumbnail(
    file: File,
    selected: Boolean,
    marked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var thumbnail by remember(file.absolutePath) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(file.absolutePath) {
        thumbnail = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
    }

    val borderColor = when {
        marked -> MaterialTheme.colorScheme.error
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(if (marked || selected) 2.dp else 1.dp, borderColor),
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(Modifier.fillMaxSize()) {
            val bmp = thumbnail
            if (bmp != null) {
                Image(
                    painter = BitmapPainter(bmp.asImageBitmap()),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                )
            }
            if (marked) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(20.dp)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Done,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun getBitmapFromURI(context: Context, uri: Uri): Bitmap? {
    val contentResolver = context.contentResolver

    var bitmap = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }

    if (bitmap == null) {
        val svg = contentResolver.openInputStream(uri).use { decodeSVGSteam(it) }

        if (svg != null) {
            if (svg.documentWidth > 0 && svg.documentHeight > 0) {
                bitmap = Bitmap.createBitmap(svg.documentWidth.toInt(), svg.documentHeight.toInt(), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                svg.renderToCanvas(canvas)
            }
        }
    }

    return bitmap ?: null
}

private fun decodeSVGSteam(stream: InputStream?): SVG? {
    if (stream == null)
        return null

    return try {
        SVG.getFromInputStream(stream)
    } catch (_: SVGParseException) {
        null
    }
}

@Composable
private fun zoomBitmap(image: Bitmap, zoomLevel: Float): Bitmap {
    if (zoomLevel == 1f) {
        return image
    }

    val x = (image.width - (image.width * zoomLevel)) / 2
    val y = (image.height - (image.height * zoomLevel)) / 2

    val zoomedImage = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
    val mtx = Matrix()
    mtx.postScale(zoomLevel, zoomLevel)
    mtx.postTranslate(x, y)

    val canvas = Canvas(zoomedImage)
    canvas.drawBitmap(image, mtx, Paint())

    return zoomedImage
}

private fun squareBitmap(image: Bitmap): Bitmap {
    if (image.width == image.height) {
        return image
    }

    val size = max(image.width, image.height)
    val squaredImage = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    val x = (size - image.width) / 2f
    val y = (size - image.height) / 2f

    val mtx = Matrix()
    mtx.postTranslate(x, y)

    val canvas = Canvas(squaredImage)
    canvas.drawBitmap(image, mtx, Paint())

    return squaredImage
}

private fun createMask(image: Bitmap): Bitmap {
    val startActiveZone = image.width / 6f
    val topActiveZone = image.height / 6f
    val endActiveZone = image.width - startActiveZone
    val bottomActiveZone = image.height - topActiveZone

    val path = Path()
    path.moveTo(0f, 0f)
    path.lineTo(image.width.toFloat(), 0f)
    path.lineTo(image.width.toFloat(), image.height.toFloat())
    path.lineTo(0f, image.height.toFloat())
    path.close()

    path.moveTo(startActiveZone, topActiveZone)
    path.lineTo(startActiveZone, bottomActiveZone)
    path.lineTo(endActiveZone, bottomActiveZone)
    path.lineTo(endActiveZone, topActiveZone)
    path.close()

    val paint = Paint()
    paint.color = Red.toArgb()
    paint.style = Paint.Style.FILL

    val mask = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
    val maskCanvas = Canvas(mask)
    maskCanvas.drawPath(path.asAndroidPath(), paint)

    return mask
}

@Composable
fun ZoomSlider(value: Float, onChange: (newValue: Float) -> Unit) {
    var sliderPosition by remember { mutableFloatStateOf(value) }

    Slider(
        value = sliderPosition,
        onValueChange = {
            sliderPosition = it
            onChange(it)},
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.secondary,
            activeTrackColor = MaterialTheme.colorScheme.secondary,
            inactiveTrackColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        steps = 0,
        valueRange = 0f..2f
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = (sliderPosition * 100).toInt().toString() + "%")
        IconButton(onClick = {
            sliderPosition = 1f
            onChange(sliderPosition)
        }) {
            Icon(
                imageVector = Icons.Filled.Clear,
                contentDescription = "Clear",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun AdaptiveIconSwitch(asAdaptiveIcon: Boolean, onChange: (newValue: Boolean) -> Unit) {
    var checked by rememberSaveable { mutableStateOf(false) }

    checked = asAdaptiveIcon

    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.asAdaptiveIcon))
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                onChange(it)
            },
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
fun PrepareEditVector(app: PackageInfoStruct, onChange: (icon: IconPackDrawable?) -> Unit) {
    val editedVector = when (app.createdIcon) {
        is ImageVectorDrawable -> app.createdIcon.applyAndRemoveGroup().toImageVector()
        is InsetIconDrawable -> {
            if (app.createdIcon.drawable is ImageVectorDrawable)
                app.createdIcon.drawable.applyAndRemoveGroup().toImageVector()
            else
                ImageVector.createEmptyVector()
        }
        else -> ImageVector.createEmptyVector()
    }

    EditVectorColumn(editedVector) {
        if (app.createdIcon is InsetIconDrawable && it != null) {
            onChange(InsetIconDrawable(it, app.createdIcon.dimensions, app.createdIcon.fractions))
        } else {
            onChange(it)
        }
    }
}

@Composable
fun EditVectorColumn(vector: ImageVector, onChange: (icon: IconPackDrawable?) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        var paths: List<VectorPath> by remember { mutableStateOf(listOf()) }
        var automaticallyCenter by rememberSaveable { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            for (path in vector.root) {
                if (path is VectorPath && path.pathData != EmptyPath) {
                    val mutableList = paths.toMutableList()
                    mutableList.add(path)
                    paths = mutableList.toList()
                }
            }
        }

        val editedVector = vector.toImageVectorDrawable()

        editedVector.root.children.clear()
        for (path in paths) {
            editedVector.root.children.add(MutableVectorPath(path))
        }
        if (automaticallyCenter)
            editedVector.center()

        val painter = rememberVectorPainter(editedVector.toImageVector())
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Image(painter, null, Modifier
                .padding(16.dp)
                .size(96.dp, 96.dp))
        }

        CenterSwitch {
            automaticallyCenter = it
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.paths),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            NewPath {
                if (it.trim() == "") {
                    return@NewPath
                }

                var stroke = SolidColor(Color.White) as Brush?
                var strokeWidth = 1F

                val lastPath = editedVector.root.children.lastOrNull() as MutableVectorPath?
                if (lastPath != null) {
                    stroke = lastPath.stroke
                    strokeWidth = lastPath.strokeLineWidth
                }

                val parser = PathParser().parsePathString(it)

                val builder = editedVector.toImageVector().getBuilder()
                builder.addPath(parser.toNodes(), stroke = stroke, strokeLineWidth = strokeWidth)
                val newVector = builder.build()

                val newPath = newVector.root.last() as VectorPath

                val mutableList = paths.toMutableList()
                mutableList.add(newPath)
                paths = mutableList.toList()
            }
        }

        if (paths.isEmpty()) {
            Text(
                text = stringResource(R.string.noPathsYet),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            itemsIndexed(paths) { index, path ->
                VectorPathItem(editedVector.toImageVector(), path, {
                    val mutableList = paths.toMutableList()
                    mutableList.removeAt(index)
                    paths = mutableList.toList()
                }) {
                    val parser = PathParser().parsePathString(it)

                    val mutablePath = editedVector.root.children[index] as MutableVectorPath
                    mutablePath.pathData.clear()
                    mutablePath.pathData.addAll(parser.toNodes())

                    val newPath = editedVector.toImageVector().root[index] as VectorPath

                    val mutableList = paths.toMutableList()
                    mutableList[index] = newPath
                    paths = mutableList.toList()
                }
            }
        }

        onChange(editedVector)
    }
}

@Composable
fun VectorPathItem(
    vector: ImageVector,
    path: VectorPath,
    onDelete: () -> Unit,
    onChange: (newPath: String) -> Unit
) {
    var showPathEditor by remember { mutableStateOf(false) }

    val newVector = vector.toImageVectorDrawable()
    newVector.root.children.clear()
    newVector.root.children.add(MutableVectorPath(path))

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                val painter = rememberVectorPainter(newVector.toImageVector())
                Image(painter, null, Modifier
                    .padding(6.dp)
                    .size(48.dp, 48.dp))
            }

            Text(
                text = path.pathData.toStringPath(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = { showPathEditor = true }, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.editPath),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = { onDelete() }, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.clearIcons),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    if (showPathEditor) {
        EditPathDialog(path.pathData.toStringPath(), { showPathEditor = false }) {
            onChange(it)
            showPathEditor = false
        }
    }
}

@Composable
fun NewPath(onChange: (newPath: String) -> Unit) {
    var showPathEditor by remember { mutableStateOf(false) }

    FilledTonalButton(onClick = { showPathEditor = true }) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = stringResource(R.string.addPath),
            modifier = Modifier.padding(start = 6.dp)
        )
    }

    if (showPathEditor) {
        EditPathDialog("", { showPathEditor = false }) {
            onChange(it)
            showPathEditor = false
        }
    }
}

@Composable
fun EditPathDialog(path: String, onDismiss: () -> Unit, onChange: (newPath: String) -> Unit) {
    var newPath by rememberSaveable { mutableStateOf(path) }
    var badFormatting by rememberSaveable { mutableStateOf(false) }
    var formatError by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        onDismissRequest = { onDismiss() },
        title = { Text(stringResource(R.string.editPath)) },
        text = {
            Column {
                OutlinedTextField(
                    value = newPath,
                    onValueChange = {
                        newPath = it
                        badFormatting = false
                    },
                    label = { Text(stringResource(R.string.pathData)) },
                    placeholder = { Text("M 12 2 L 22 22 H 2 Z") },
                    minLines = 5,
                    maxLines = 10,
                    isError = badFormatting,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )

                if (badFormatting) {
                    Text(
                        text = stringResource(id = R.string.badPathFormat),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        text = formatError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            val emptyPathText = stringResource(id = R.string.emptyPath)
            IconButton(onClick = {
                try {
                    val nodes = PathParser().parsePathString(newPath).toNodes()

                    if (nodes == EmptyPath) {
                        badFormatting = true
                        formatError = emptyPathText
                    }
                } catch (e: IllegalArgumentException) {
                    badFormatting = true
                    formatError = e.localizedMessage!!
                }

                if (!badFormatting) {
                    onChange(newPath)
                }
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
fun CenterSwitch(onChange: (newValue: Boolean) -> Unit) {
    var checked by rememberSaveable { mutableStateOf(true) }

    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.automaticallyCenter))
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                onChange(it)
            },
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

enum class IconSortOrder { NAME_ASC, NAME_DESC }


@Composable
fun PackSectionHeader(iconPack: IconPack, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PackIcon(iconPack.packageName, 24.dp)
        Text(
            text = iconPack.applicationName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (onClick != null) {
            // Tapping anywhere on the row opens the full grid for just this pack
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun PackIcon(packageName: String, size: androidx.compose.ui.unit.Dp) {
    val context = getCurrentContext()
    val packIcon = remember(packageName) {
        try {
            context.packageManager.getApplicationIcon(packageName).toSafeBitmapOrNull()
        } catch (_: Exception) {
            null
        }
    }

    if (packIcon != null) {
        Image(
            painter = BitmapPainter(packIcon.asImageBitmap()),
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size / 4))
        )
    } else {
        Surface(
            modifier = Modifier.size(4.dp, size - 4.dp),
            shape = RoundedCornerShape(2.dp),
            color = MaterialTheme.colorScheme.primary
        ) {}
    }
}

const val PACK_ROW_LIMIT = 30
// Hard cap for the full-pack grid — huge packs (e.g. Arcticons) have thousands of icons
// and eagerly generated previews for all of them would run out of memory.
const val PACK_DETAIL_LIMIT = 400

@Composable
fun PackIconsRow(
    iconPack: IconPack,
    options: GenerationOptions,
    sortOrder: IconSortOrder,
    query: String = "",
    onMore: (() -> Unit)? = null,
    onResult: (hasMatches: Boolean) -> Unit = {},
    onSelect: (ResourceDrawable, IconPackDrawable) -> Unit
) {
    val context = getCurrentContext()
    val activity = getCurrentMainActivity()
    var iconPairs by remember { mutableStateOf<List<Pair<ResourceDrawable, IconPackDrawable>>>(emptyList()) }
    var moreCount by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(iconPack.packageName, sortOrder, query) {
        isLoading = true
        val loaded = withContext(Dispatchers.Default) {
            try {
                val appMan = ApplicationManager(context)
                val allNames = appMan.getIconPackDrawableNames(iconPack.packageName)
                val formattedQuery = query.lowercase().trim().replace(' ', '_')
                val matchingNames = if (formattedQuery.isEmpty()) {
                    allNames
                } else {
                    allNames.filter { it.contains(formattedQuery) }
                }
                val sortedNames = when (sortOrder) {
                    IconSortOrder.NAME_ASC -> matchingNames.sortedBy { it }
                    IconSortOrder.NAME_DESC -> matchingNames.sortedByDescending { it }
                }
                moreCount = (sortedNames.size - PACK_ROW_LIMIT).coerceAtLeast(0)
                val ids = appMan.getIconPackDrawableIds(iconPack.packageName, sortedNames.take(PACK_ROW_LIMIT))
                val drawables = appMan.getIconPackDrawables(iconPack.packageName, ids)
                val exportDrawables = activity.appProvider.getIconPackIcons(iconPack.packageName, options, drawables)
                exportDrawables.entries
                    .filter { it.value != null }
                    .map { Pair(it.key, it.value!!) }
                    .distinctBy { it.first.resourceId }
            } catch (_: Exception) {
                // A malformed icon pack must not crash the browser
                moreCount = 0
                emptyList()
            }
        }
        iconPairs = loaded
        isLoading = false
        onResult(loaded.isNotEmpty())
    }

    if (isLoading) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            contentAlignment = Alignment.Center
        ) {
            LinearWavyProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    } else if (iconPairs.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.noIconsFound),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    } else {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(iconPairs, key = { _, pair -> pair.first.resourceId }) { _, pair ->
                Image(
                    painter = pair.second.getPainter(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .padding(4.dp)
                        .clickable { onSelect(pair.first, pair.second) }
                )
            }
            if (moreCount > 0 && onMore != null) {
                item(key = "more") {
                    Box(modifier = Modifier.size(64.dp).padding(4.dp), contentAlignment = Alignment.Center) {
                        Surface(
                            onClick = onMore,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "+$moreCount",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PackDetailGrid(
    iconPack: IconPack,
    options: GenerationOptions,
    sortOrder: IconSortOrder,
    query: String,
    onBack: () -> Unit,
    onCollapsedChange: (Boolean) -> Unit = {},
    onSelect: (ResourceDrawable, IconPackDrawable) -> Unit
) {
    val context = getCurrentContext()
    val activity = getCurrentMainActivity()
    var iconPairs by remember { mutableStateOf<List<Pair<ResourceDrawable, IconPackDrawable>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    val gridState = rememberLazyGridState()
    val gridScrolled by remember {
        derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 60 }
    }
    LaunchedEffect(gridScrolled) {
        onCollapsedChange(gridScrolled)
    }

    LaunchedEffect(iconPack.packageName, sortOrder, query) {
        isLoading = true
        iconPairs = emptyList()
        withContext(Dispatchers.Default) {
            try {
                val appMan = ApplicationManager(context)
                val allNames = appMan.getIconPackDrawableNames(iconPack.packageName)
                val formattedQuery = query.lowercase().trim().replace(' ', '_')
                val matchingNames = if (formattedQuery.isEmpty()) {
                    allNames
                } else {
                    allNames.filter { it.contains(formattedQuery) }
                }
                val sortedNames = when (sortOrder) {
                    IconSortOrder.NAME_ASC -> matchingNames.sortedBy { it }
                    IconSortOrder.NAME_DESC -> matchingNames.sortedByDescending { it }
                }
                // Load in chunks so the grid fills progressively instead of blocking
                for (chunk in sortedNames.take(PACK_DETAIL_LIMIT).chunked(40)) {
                    coroutineContext.ensureActive()
                    val ids = appMan.getIconPackDrawableIds(iconPack.packageName, chunk)
                    val drawables = appMan.getIconPackDrawables(iconPack.packageName, ids)
                    val exportDrawables = activity.appProvider.getIconPackIcons(iconPack.packageName, options, drawables)
                    val pairs = exportDrawables.entries
                        .filter { it.value != null }
                        .map { Pair(it.key, it.value!!) }
                    iconPairs = (iconPairs + pairs).distinctBy { it.first.resourceId }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // A malformed icon pack must not crash the browser
            }
        }
        isLoading = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.dismiss))
            }
            PackIcon(iconPack.packageName, 28.dp)
            Text(
                text = iconPack.applicationName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!isLoading) {
                Text(
                    text = iconPairs.size.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        if (isLoading) {
            LinearWavyProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
        if (!isLoading && iconPairs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.noIconsFound),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 16.dp)
            ) {
                items(iconPairs, key = { it.first.resourceId }) { pair ->
                    Image(
                        painter = pair.second.getPainter(),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(4.dp)
                            .size(56.dp)
                            .clickable { onSelect(pair.first, pair.second) }
                    )
                }
            }
        }
    }
}