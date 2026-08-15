@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.renkinProject.renkin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.GlobalOptionsViewModel
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.data.GlobalApplyCustomKey
import dev.renkinProject.renkin.data.GlobalApplyExistingKey
import dev.renkinProject.renkin.data.GlobalApplyGeneratedKey
import dev.renkinProject.renkin.data.GlobalColorizeFlatKey
import dev.renkinProject.renkin.data.GlobalColorizeInverseKey
import dev.renkinProject.renkin.data.GlobalColorizeKey
import dev.renkinProject.renkin.data.GlobalColorizeMonochromeKey
import dev.renkinProject.renkin.data.GlobalColorizerStyleKeys
import dev.renkinProject.renkin.data.GlobalIconScaleKey
import dev.renkinProject.renkin.data.GlobalIncludeEmptyKey
import dev.renkinProject.renkin.data.GlobalShapeCropKey
import dev.renkinProject.renkin.data.GlobalShapeKey
import dev.renkinProject.renkin.data.GlobalShapeScaleKey
import dev.renkinProject.renkin.data.GlobalShapeStyleKeys
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.OUTLINE_WIDTH_DEFAULT
import dev.renkinProject.renkin.data.OutlineAddKey
import dev.renkinProject.renkin.data.OutlineWidthKey
import dev.renkinProject.renkin.data.OutlineStyleKeys
import dev.renkinProject.renkin.data.ColorStyleKeys
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TEXT_TYPE_DEFAULT
import dev.renkinProject.renkin.data.getBooleanValue
import dev.renkinProject.renkin.data.getIntValue
import dev.renkinProject.renkin.data.getPreferencesValue
import dev.renkinProject.renkin.data.normalizeGlobalScalePercent
import dev.renkinProject.renkin.data.normalizeOutlineWidth
import dev.renkinProject.renkin.data.setColorStyle
import dev.renkinProject.renkin.drawable.BitmapIconDrawable
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.ColorizerMode
import android.graphics.Bitmap
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.GradientType
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.OutlineMode
import dev.renkinProject.renkin.icon.creator.colorStyle
import dev.renkinProject.renkin.icon.creator.decodeColorizerStyle
import dev.renkinProject.renkin.icon.creator.encodeColorizerStyle
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.ui.theme.AddedGreen
import dev.renkinProject.renkin.ui.theme.CardShape
import dev.renkinProject.renkin.ui.theme.SwatchShape
import dev.renkinProject.renkin.ui.theme.IconShape as IconTileShape
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Global options screen's staged modifier values. Nothing here touches DataStore until
 * Save — the grid below previews these values live, and Back discards them (after a prompt).
 */
internal data class GlobalModifierSnapshot(
    val shape: IconShape,
    val shapeCrop: Boolean,
    val shapeScalePercent: Int,
    val shapeStyle: ColorizerStyle,
    val iconScalePercent: Int,
    val outlineAdd: Boolean,
    val outlineWidth: Int,
    val outlineStyle: ColorizerStyle,
    val colorize: Boolean,
    val colorizerStyle: ColorizerStyle,
    val applyGenerated: Boolean,
    val applyExisting: Boolean,
    val applyCustom: Boolean,
    val includeEmpty: Boolean
)

@Stable
internal class GlobalModifierState {
    var initialized by mutableStateOf(false)
        private set
    var shape by mutableStateOf(IconShape.NONE)
    var shapeCrop by mutableStateOf(true)
    var shapeScale by mutableFloatStateOf(1f)
    // Whole styles stay together: saver, preferences and controls cannot update only half a
    // gradient and leave its colours, positions or angle describing different states.
    var shapeStyle by mutableStateOf(ColorizerStyle(firstColor = Color.White.toArgb()))
    var iconScale by mutableFloatStateOf(1f)
    var outlineAdd by mutableStateOf(false)
    var outlineWidth by mutableFloatStateOf(OUTLINE_WIDTH_DEFAULT.toFloat())
    var outlineStyle by mutableStateOf(ColorizerStyle(firstColor = Color.Black.toArgb()))
    var colorize by mutableStateOf(false)
    var colorizerStyle by mutableStateOf(ColorizerStyle(firstColor = Color.White.toArgb()))
    // Which icon categories the modifiers apply to (the toggle-button row).
    var applyGenerated by mutableStateOf(true)
    var applyExisting by mutableStateOf(false)
    var applyCustom by mutableStateOf(false)
    var includeEmpty by mutableStateOf(false)

    /** True when the staged values would visibly change an icon at all. */
    val hasAnyEffect: Boolean
        get() = shape != IconShape.NONE || iconScale != 1f || outlineAdd || colorize

    fun seedFrom(preferences: Preferences) {
        shape = IconShape.entries.getOrElse(
            preferences.getIntValue(GlobalShapeKey, IconShape.NONE.ordinal)
        ) { IconShape.NONE }
        shapeCrop = preferences.getBooleanValue(GlobalShapeCropKey, true)
        shapeScale = normalizeGlobalScalePercent(preferences.getIntValue(GlobalShapeScaleKey, 100)) / 100f
        shapeStyle = preferences.colorStyle(GlobalShapeStyleKeys, Color.White)
        iconScale = normalizeGlobalScalePercent(preferences.getIntValue(GlobalIconScaleKey, 100)) / 100f
        outlineAdd = preferences.getBooleanValue(OutlineAddKey)
        outlineWidth = normalizeOutlineWidth(
            preferences.getIntValue(OutlineWidthKey, OUTLINE_WIDTH_DEFAULT)).toFloat()
        outlineStyle = preferences.colorStyle(OutlineStyleKeys, Color.Black)
        colorize = preferences.getBooleanValue(GlobalColorizeKey)
        val monochrome = preferences.getBooleanValue(GlobalColorizeMonochromeKey)
        colorizerStyle = preferences.colorStyle(GlobalColorizerStyleKeys, Color.White).copy(
            flat = preferences.getBooleanValue(GlobalColorizeFlatKey) && !monochrome,
            monochrome = monochrome,
            inverse = preferences.getBooleanValue(GlobalColorizeInverseKey)
        )
        applyGenerated = preferences.getBooleanValue(GlobalApplyGeneratedKey, true)
        applyExisting = preferences.getBooleanValue(GlobalApplyExistingKey)
        applyCustom = preferences.getBooleanValue(GlobalApplyCustomKey)
        includeEmpty = preferences.getBooleanValue(GlobalIncludeEmptyKey)
        initialized = true
    }

    fun snapshot() = GlobalModifierSnapshot(
        shape = shape,
        shapeCrop = shapeCrop,
        shapeScalePercent = (shapeScale * 100).roundToInt(),
        shapeStyle = shapeStyle.roundedForSnapshot(),
        iconScalePercent = (iconScale * 100).roundToInt(),
        outlineAdd = outlineAdd,
        outlineWidth = outlineWidth.roundToInt(),
        outlineStyle = outlineStyle.roundedForSnapshot(),
        colorize = colorize,
        colorizerStyle = colorizerStyle.roundedForSnapshot(),
        applyGenerated = applyGenerated,
        applyExisting = applyExisting,
        applyCustom = applyCustom,
        includeEmpty = includeEmpty
    )

    private fun restore(snapshot: GlobalModifierSnapshot) {
        shape = snapshot.shape
        shapeCrop = snapshot.shapeCrop
        shapeScale = snapshot.shapeScalePercent / 100f
        shapeStyle = snapshot.shapeStyle
        iconScale = snapshot.iconScalePercent / 100f
        outlineAdd = snapshot.outlineAdd
        outlineWidth = snapshot.outlineWidth.toFloat()
        outlineStyle = snapshot.outlineStyle
        colorize = snapshot.colorize
        colorizerStyle = snapshot.colorizerStyle
        applyGenerated = snapshot.applyGenerated
        applyExisting = snapshot.applyExisting
        applyCustom = snapshot.applyCustom
        includeEmpty = snapshot.includeEmpty
        initialized = true
    }

    private fun restoreLegacy(snapshot: String) {
        val values = snapshot.split('|')
        if (values.size !in intArrayOf(17, 20, 21, 25, 27, 32)) return
        runCatching {
            shape = IconShape.entries[values[0].toInt()]
            shapeCrop = values[1].toBooleanStrict()
            shapeScale = values[2].toInt() / 100f
            shapeStyle = shapeStyle.copy(firstColor = values[3].toInt())
            iconScale = values[4].toInt() / 100f
            outlineAdd = values[5].toBooleanStrict()
            outlineWidth = values[6].toInt().toFloat()
            outlineStyle = outlineStyle.copy(firstColor = values[7].toInt())
            colorize = values[8].toBooleanStrict()
            colorizerStyle = colorizerStyle.copy(
                firstColor = values[9].toInt(),
                flat = values[10].toBooleanStrict(),
                monochrome = values[15].toBooleanStrict(),
                inverse = values[16].toBooleanStrict()
            )
            applyGenerated = values[11].toBooleanStrict()
            applyExisting = values[12].toBooleanStrict()
            applyCustom = values[13].toBooleanStrict()
            includeEmpty = values[14].toBooleanStrict()
            if (values.size >= 20) {
                colorizerStyle = colorizerStyle.copy(
                    mode = ColorizerMode.entries.getOrElse(values[17].toInt()) {
                        ColorizerMode.SINGLE_COLOR
                    },
                    gradientStops = values[18].split(',').mapNotNull { it.toIntOrNull() }
                        .ifEmpty { listOf(android.graphics.Color.BLACK) },
                    gradientAngle = values[19].toInt().coerceIn(0, 360).toFloat()
                )
            }
            if (values.size >= 21) {
                colorizerStyle = colorizerStyle.copy(
                    gradientType = GradientType.entries.getOrElse(values[20].toInt()) {
                        GradientType.LINEAR
                    }
                )
            }
            if (values.size >= 25) {
                outlineStyle = outlineStyle.copy(
                    mode = ColorizerMode.entries.getOrElse(values[21].toInt()) {
                        ColorizerMode.SINGLE_COLOR
                    },
                    gradientType = GradientType.entries.getOrElse(values[22].toInt()) {
                        GradientType.LINEAR
                    },
                    gradientStops = values[23].split(',').mapNotNull { it.toIntOrNull() }
                        .ifEmpty { listOf(android.graphics.Color.BLACK) },
                    gradientAngle = values[24].toInt().coerceIn(0, 360).toFloat()
                )
            }
            if (values.size >= 27) {
                colorizerStyle = colorizerStyle.copy(
                    gradientPositions = values[25].split(',').mapNotNull { it.toFloatOrNull() }
                )
                outlineStyle = outlineStyle.copy(
                    gradientPositions = values[26].split(',').mapNotNull { it.toFloatOrNull() }
                )
            }
            if (values.size >= 32) {
                shapeStyle = shapeStyle.copy(
                    mode = ColorizerMode.entries.getOrElse(values[27].toInt()) {
                        ColorizerMode.SINGLE_COLOR
                    },
                    gradientType = GradientType.entries.getOrElse(values[28].toInt()) {
                        GradientType.LINEAR
                    },
                    gradientStops = values[29].split(',').mapNotNull { it.toIntOrNull() }
                        .ifEmpty { listOf(android.graphics.Color.BLACK) },
                    gradientPositions = values[30].split(',').mapNotNull { it.toFloatOrNull() },
                    gradientAngle = values[31].toInt().coerceIn(0, 360).toFloat()
                )
            }
            initialized = true
        }
    }

    companion object {
        val Saver = Saver<GlobalModifierState, Any>(
            save = { it.snapshot().toSaveableList() },
            restore = { saved -> restoreSnapshot(saved)?.let { snapshot ->
                GlobalModifierState().apply { restore(snapshot) }
            } }
        )

        val BaselineSaver = Saver<androidx.compose.runtime.MutableState<GlobalModifierSnapshot?>, Any>(
            save = { it.value?.toSaveableList() },
            restore = { saved -> mutableStateOf(restoreSnapshot(saved)) }
        )

        private fun restoreSnapshot(saved: Any): GlobalModifierSnapshot? = when (saved) {
            is List<*> -> saved.toGlobalModifierSnapshot()
            is String -> GlobalModifierState().apply { restoreLegacy(saved) }
                .takeIf { it.initialized }
                ?.snapshot()
            else -> null
        }
    }

    /** Writes staged values into [mutable] for the preview and ViewModel commit snapshot. */
    private fun writeInto(mutable: androidx.datastore.preferences.core.MutablePreferences) {
        mutable[GlobalShapeKey] = shape.ordinal
        mutable[GlobalShapeCropKey] = shapeCrop
        mutable[GlobalShapeScaleKey] = (shapeScale * 100).roundToInt()
        mutable.writeColorStyle(GlobalShapeStyleKeys, shapeStyle)
        mutable[GlobalIconScaleKey] = (iconScale * 100).roundToInt()
        mutable[OutlineAddKey] = outlineAdd
        mutable[OutlineWidthKey] = outlineWidth.roundToInt()
        mutable.writeColorStyle(OutlineStyleKeys, outlineStyle)
        mutable[GlobalColorizeKey] = colorize
        mutable.writeColorStyle(GlobalColorizerStyleKeys, colorizerStyle)
        mutable[GlobalColorizeFlatKey] = colorizerStyle.flat
        mutable[GlobalColorizeMonochromeKey] = colorizerStyle.monochrome
        mutable[GlobalColorizeInverseKey] = colorizerStyle.inverse
        mutable[GlobalApplyGeneratedKey] = applyGenerated
        mutable[GlobalApplyExistingKey] = applyExisting
        mutable[GlobalApplyCustomKey] = applyCustom
        mutable[GlobalIncludeEmptyKey] = includeEmpty
    }

    /**
     * A preferences snapshot with the STAGED values overlaid, so the empty-slot preview and
     * a real refresh after Save produce exactly the same icons ([GenerationOptions
     * .fromPreferences] reads the global keys).
     */
    fun overlay(preferences: Preferences): Preferences =
        preferences.toMutablePreferences().also { writeInto(it) }

    /**
     * Modifier-only options for previewing/baking over an EXISTING icon: colorize as the image
     * edit (when on), plus scale, shape and outline. Sources are irrelevant to applyModifier.
     */
    fun toModifierOptions(): GenerationOptions = GenerationOptions(
        primarySource = Source.NONE,
        primaryImageEdit = if (colorize) ImageEdit.COLORIZE else ImageEdit.NONE,
        primaryTextType = TEXT_TYPE_DEFAULT,
        primaryIconPack = "",
        color = colorizerStyle.firstColor,
        bgColor = if (shape != IconShape.NONE && !shapeCrop) shapeStyle.firstColor
            else android.graphics.Color.TRANSPARENT,
        vector = false,
        materialYou = false,
        themed = false,
        override = true,
        colorizeFlat = colorizerStyle.flat,
        colorizeMonochrome = colorizerStyle.monochrome,
        colorizeInverse = colorizerStyle.inverse,
        colorizerMode = colorizerStyle.mode,
        colorizerGradientType = colorizerStyle.gradientType,
        colorizerGradientColors = colorizerStyle.gradientStops,
        colorizerGradientPositions = colorizerStyle.gradientPositions,
        colorizerGradientAngle = colorizerStyle.gradientAngle,
        // Only the plate shows the shape colour, so the style rides along with it.
        backgroundStyle = if (shape != IconShape.NONE && !shapeCrop) {
            shapeStyle
        } else null,
        iconScale = iconScale,
        iconShape = shape,
        iconShapeCrop = shapeCrop,
        iconShapeScale = shapeScale,
        outlineMode = if (outlineAdd) OutlineMode.ADD else OutlineMode.NONE,
        outlineWidth = outlineWidth,
        outlineColor = outlineStyle.firstColor,
        outlineStyle = outlineStyle
    )
}

private fun ColorizerStyle.roundedForSnapshot(): ColorizerStyle =
    copy(gradientAngle = gradientAngle.roundToInt().toFloat())

private fun MutablePreferences.writeColorStyle(keys: ColorStyleKeys, style: ColorizerStyle) {
    setColorStyle(
        keys = keys,
        mode = style.mode.ordinal,
        gradientType = style.gradientType.ordinal,
        gradientAngle = style.gradientAngle.roundToInt(),
        firstColor = Color(style.firstColor),
        gradientStops = style.gradientStops,
        gradientPositions = style.gradientPositions
    )
}

private fun GlobalModifierSnapshot.toSaveableList(): ArrayList<Any> = arrayListOf(
    "shape", shape.ordinal,
    "shapeCrop", shapeCrop,
    "shapeScalePercent", shapeScalePercent,
    "shapeStyle", encodeColorizerStyle(shapeStyle),
    "iconScalePercent", iconScalePercent,
    "outlineAdd", outlineAdd,
    "outlineWidth", outlineWidth,
    "outlineStyle", encodeColorizerStyle(outlineStyle),
    "colorize", colorize,
    "colorizerStyle", encodeColorizerStyle(colorizerStyle),
    "applyGenerated", applyGenerated,
    "applyExisting", applyExisting,
    "applyCustom", applyCustom,
    "includeEmpty", includeEmpty
)

private fun List<*>.toGlobalModifierSnapshot(): GlobalModifierSnapshot? {
    if (firstOrNull() !is String) return null
    val values = buildMap<String, Any?> {
        var index = 0
        while (index + 1 < this@toGlobalModifierSnapshot.size) {
            val key = this@toGlobalModifierSnapshot[index] as? String ?: break
            put(key, this@toGlobalModifierSnapshot[index + 1])
            index += 2
        }
    }
    val shapeStyle = (values["shapeStyle"] as? String)?.let(::decodeColorizerStyle) ?: return null
    val outlineStyle = (values["outlineStyle"] as? String)?.let(::decodeColorizerStyle) ?: return null
    val colorizerStyle = (values["colorizerStyle"] as? String)?.let(::decodeColorizerStyle)
        ?: return null
    return GlobalModifierSnapshot(
        shape = IconShape.entries.getOrElse(values["shape"] as? Int ?: 0) { IconShape.NONE },
        shapeCrop = values["shapeCrop"] as? Boolean ?: true,
        shapeScalePercent = values["shapeScalePercent"] as? Int ?: 100,
        shapeStyle = shapeStyle,
        iconScalePercent = values["iconScalePercent"] as? Int ?: 100,
        outlineAdd = values["outlineAdd"] as? Boolean ?: false,
        outlineWidth = values["outlineWidth"] as? Int ?: OUTLINE_WIDTH_DEFAULT,
        outlineStyle = outlineStyle,
        colorize = values["colorize"] as? Boolean ?: false,
        colorizerStyle = colorizerStyle,
        applyGenerated = values["applyGenerated"] as? Boolean ?: true,
        applyExisting = values["applyExisting"] as? Boolean ?: false,
        applyCustom = values["applyCustom"] as? Boolean ?: false,
        includeEmpty = values["includeEmpty"] as? Boolean ?: false
    )
}

internal data class GlobalIconCategories(
    val generated: List<PackageInfoStruct>,
    val custom: List<PackageInfoStruct>,
    val existing: List<PackageInfoStruct>,
    val iconless: List<PackageInfoStruct>
)

internal fun categorizeGlobalIcons(
    applications: List<PackageInfoStruct>,
    lockedKeys: Set<String>
): GlobalIconCategories {
    val generated = ArrayList<PackageInfoStruct>()
    val custom = ArrayList<PackageInfoStruct>()
    val existing = ArrayList<PackageInfoStruct>()
    val iconless = ArrayList<PackageInfoStruct>()

    for (app in applications) {
        if (app.createdIcon == null) {
            if (app.key !in lockedKeys) iconless += app
        } else if (app.isCustom) {
            custom += app
        } else if (app.isRefreshMade) {
            generated += app
        } else {
            existing += app
        }
    }

    return GlobalIconCategories(generated, custom, existing, iconless)
}

/**
 * Fullscreen Global options, hosted by [dev.renkinProject.renkin.GlobalOptionsActivity] whose
 * windowShowWallpaper theme puts the REAL wallpaper behind the transparent icon grid: the
 * refresh-wide generation options (immediate, like the old Advanced options card), the staged
 * global modifiers, and a live preview grid of every icon split into generated / custom /
 * existing / iconless apps. Save re-renders the global layer from immutable icon bases and
 * persists the profile; tapping a tile opens a per-app editor. [onClose] reports the per-icon
 * edits and whether a Save happened, so MainViewModel can update its session bookkeeping.
 */
@Composable
fun GlobalOptionsScreen(onClose: (editedKeys: Set<String>, applied: Boolean) -> Unit) {
    val viewModel: GlobalOptionsViewModel = hiltViewModel()
    val iconPacks = viewModel.iconPacks
    val prefs = getPreferences()
    val prefsValue = prefs.getPreferencesValue()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val toaster = LocalToaster.current
    val appliedMessage = stringResource(R.string.globalOptionsApplied)
    val applyFailedMessage = stringResource(R.string.globalOptionsApplyFailed)

    val state = rememberSaveable(saver = GlobalModifierState.Saver) { GlobalModifierState() }
    // The baseline snapshot the dirty check compares against; null until seeding completes.
    var baseline by rememberSaveable(saver = GlobalModifierState.BaselineSaver) {
        mutableStateOf<GlobalModifierSnapshot?>(null)
    }
    LaunchedEffect(Unit) {
        if (!state.initialized) {
            state.seedFrom(prefs.data.first())
            baseline = state.snapshot()
        }
    }

    val applying = viewModel.globalApplyProgress != null
    val previewJobs by viewModel.previewJobs.collectAsState()
    val dirty by remember(state) {
        derivedStateOf { baseline != null && baseline != state.snapshot() }
    }
    var showExperimentalNotice by rememberSaveable { mutableStateOf(true) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (applying) Unit
        else if (dirty) confirmDiscard = true
        else onClose(viewModel.editedKeys, viewModel.appliedGlobal)
    }
    androidx.activity.compose.BackHandler(enabled = true) { close() }

    // Staged modifiers → the preview options. null = nothing to apply (tiles show the icon
    // as-is without spinning up a generation per tile).
    val tileOptions by remember(state) {
        derivedStateOf {
            if (state.hasAnyEffect) state.toModifierOptions() else null
        }
    }
    // Empty slots preview a full generation with the staged globals overlaid, so what's shown
    // matches what Save (and a later refresh) actually produces.
    val emptySourceOptions by remember(state, prefsValue, context) {
        derivedStateOf {
            if (state.includeEmpty) {
                GenerationOptions.fromPreferences(state.overlay(prefsValue), context, override = true)
            } else null
        }
    }
    LaunchedEffect(emptySourceOptions, tileOptions) {
        viewModel.updatePreviewConfiguration(emptySourceOptions, tileOptions)
    }

    val categories by remember(viewModel) {
        derivedStateOf {
            categorizeGlobalIcons(viewModel.applicationList, viewModel.lockedIconKeys)
        }
    }
    val generated = categories.generated
    val custom = categories.custom
    val existing = categories.existing
    val iconless = categories.iconless

    var editApp by remember { mutableStateOf<PackageInfoStruct?>(null) }

    // Which section the top visible tile belongs to ('g'/'c'/'e'), read from the item keys —
    // drives the pinned "you are here" bar while the grid scrolls.
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val currentSection by remember {
        androidx.compose.runtime.derivedStateOf {
            gridState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { info ->
                (info.key as? String)?.takeIf { it.length > 2 && it[1] == '/' }?.get(0)
            }
        }
    }

    // No Surface around everything: the activity window is transparent over the wallpaper, so
    // each non-grid area paints its own opaque background and the grid area paints none.
    // Without a Surface, LocalContentColor stays at its Black default — provide the theme's
    // onSurface explicitly so the top bar (and any other default-coloured content) is
    // readable in dark mode too.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .statusBarsPadding()
                ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = close, enabled = !applying) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.dismiss))
                    }
                    Text(
                        text = stringResource(R.string.globalOptionsTitle),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            view.performConfirmHaptic()
                            scope.launch {
                                val applied = viewModel.applyGlobalModifiers(
                                    state.overlay(prefsValue), state.toModifierOptions(),
                                    state.applyGenerated, state.applyExisting,
                                    state.applyCustom, state.includeEmpty
                                )
                                toaster.show(if (applied) appliedMessage else applyFailedMessage)
                                if (applied) {
                                    baseline = state.snapshot()
                                }
                            }
                        },
                        enabled = dirty && !applying,
                        shape = dev.renkinProject.renkin.ui.theme.FieldShape
                    ) {
                        Text(stringResource(if (applying) R.string.globalApplying else R.string.save))
                    }
                }
                HorizontalDivider()
                val progress = viewModel.globalApplyProgress
                when {
                    progress != null && progress.second > 0 -> LinearProgressIndicator(
                        progress = { progress.first / progress.second.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                    progress != null || previewJobs > 0 || viewModel.initialLoadRunning -> {
                        WavyLoadingBar(Modifier.fillMaxWidth())
                    }
                }
                }

                // While the pinned options panel is open the tiles shrink so more of the
                // preview stays visible; the user collapses/expands it with the arrow button
                // under the panel (no auto-hide — scrolling mid-tune felt like losing it).
                var panelVisible by remember { mutableStateOf(true) }

                // The icon grid, shared by both layouts below (single-pane phones and the
                // side-by-side pane on wide screens). [compact] = smaller tiles while the
                // options panel is expanded.
                val gridContent: @Composable (compact: Boolean) -> Unit = { compact ->
                val tileSize = androidx.compose.animation.core.animateDpAsState(
                    targetValue = if (compact) 40.dp else 56.dp,
                    animationSpec = androidx.compose.animation.core.spring(
                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
                        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                    ),
                    label = "tileSize"
                ).value
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(if (compact) 62.dp else 84.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (generated.isEmpty() && custom.isEmpty() && existing.isEmpty() && iconless.isEmpty()) {
                        item(key = "noIcons", span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(
                                icon = Icons.Filled.ImageSearch,
                                text = stringResource(R.string.globalNoIcons),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp)
                            )
                        }
                    }

                    if (generated.isNotEmpty()) {
                        item(key = "genHeader", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(
                                title = stringResource(R.string.globalGeneratedSection, generated.size),
                                applies = state.applyGenerated,
                                hint = androidx.compose.ui.text.AnnotatedString(stringResource(R.string.globalGridHint))
                            )
                        }
                        items(generated, key = { "g/${it.key}" }) { app ->
                            IconPreviewTile(
                                app,
                                if (state.applyGenerated) tileOptions else null,
                                viewModel,
                                iconSize = tileSize,
                                modifier = Modifier.animateItem()
                            ) { editApp = app }
                        }
                    }

                    if (custom.isNotEmpty()) {
                        item(key = "customHeader", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(
                                title = stringResource(R.string.globalCustomSection, custom.size),
                                applies = state.applyCustom
                            )
                        }
                        items(custom, key = { "c/${it.key}" }) { app ->
                            IconPreviewTile(
                                app,
                                if (state.applyCustom) tileOptions else null,
                                viewModel,
                                showEditBadge = true,
                                iconSize = tileSize,
                                modifier = Modifier.animateItem()
                            ) { editApp = app }
                        }
                    }

                    if (existing.isNotEmpty()) {
                        item(key = "existingHeader", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(
                                title = stringResource(R.string.globalExistingSection, existing.size),
                                applies = state.applyExisting,
                                hint = androidx.compose.ui.text.AnnotatedString(
                                    stringResource(R.string.globalExistingHint)
                                )
                            )
                        }
                        items(existing, key = { "s/${it.key}" }) { app ->
                            IconPreviewTile(
                                app,
                                if (state.applyExisting) tileOptions else null,
                                viewModel,
                                iconSize = tileSize,
                                modifier = Modifier.animateItem()
                            ) { editApp = app }
                        }
                    }

                    if (iconless.isNotEmpty()) {
                        item(key = "emptyHeader", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(
                                title = stringResource(R.string.globalEmptySection, iconless.size),
                                applies = state.includeEmpty
                            )
                        }
                        items(iconless, key = { "e/${it.key}" }) { app ->
                            GeneratedPreviewTile(
                                app, emptySourceOptions, tileOptions, viewModel,
                                iconSize = tileSize,
                                modifier = Modifier.animateItem()
                            ) { editApp = app }
                        }
                    }
                }
                }

                val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
                if (wide) {
                    // Foldables/tablets/desktop: options pane on the left, the icon grid on
                    // the right (transparent, over the real wallpaper) — no collapsing
                    // needed, both scroll independently.
                    Row(Modifier.fillMaxSize()) {
                        Column(
                            Modifier
                                .width(340.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 12.dp)
                        ) {
                            CategoryToggleRow(state)
                            Column(
                                Modifier.padding(horizontal = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                GlobalModifierControls(state)
                                AdvancedOptionsPanel(iconPacks)
                            }
                        }
                        VerticalDivider()
                        Column(Modifier.weight(1f)) {
                            currentSection?.let { section ->
                                CurrentSectionBar(section, generated.size, custom.size, existing.size, iconless.size, state)
                            }
                            HorizontalDivider()
                            // Transparent: the real wallpaper shows behind the tiles.
                            Box(Modifier.fillMaxSize()) { gridContent(false) }
                        }
                    }
                } else {
                    // Phones: the pinned block (category toggles + arrow handle + expandable
                    // options panel) is capped a bit under half the screen; in exchange the
                    // tiles below shrink while it is open. The toggles and handle are fixed,
                    // the panel gets the remaining height and scrolls internally.
                    val pinnedMax = (androidx.compose.ui.platform.LocalConfiguration
                        .current.screenHeightDp * 0.45f).dp
                    Column(
                        Modifier
                            .heightIn(max = pinnedMax)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        CategoryToggleRow(state)
                        AnimatedVisibility(
                            visible = panelVisible,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Column(
                                Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                GlobalModifierControls(state)
                                AdvancedOptionsPanel(iconPacks)
                            }
                        }
                        // The expand/collapse control sits UNDER the panel, right where the
                        // grid starts, so the thumb doesn't travel to the top to reach it.
                        PanelHandle(panelVisible) { panelVisible = !panelVisible }
                    }
                    currentSection?.let { section ->
                        CurrentSectionBar(section, generated.size, custom.size, existing.size, iconless.size, state)
                    }
                    HorizontalDivider()
                    // Transparent: the real wallpaper shows behind the tiles.
                    Box(Modifier.fillMaxSize()) { gridContent(panelVisible) }
                }
    }
    }

    if (showExperimentalNotice) {
        RenkinAlertDialog(
            onDismissRequest = { showExperimentalNotice = false },
            title = { Text(stringResource(R.string.globalExperimentalTitle)) },
            text = { Text(stringResource(R.string.globalExperimentalText)) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showExperimentalNotice = false }
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.globalDiscardTitle),
            text = stringResource(R.string.globalDiscardText),
            onConfirm = {
                confirmDiscard = false
                onClose(viewModel.editedKeys, viewModel.appliedGlobal)
            },
            onDismiss = { confirmDiscard = false }
        )
    }

    editApp?.let { app ->
        GlobalIconEditDialog(app = app, onDismiss = { editApp = null })
    }
}

/**
 * Advanced options inside the pinned panel: always expanded (the panel itself already
 * collapses and scrolls), so no nested chevron to hunt for.
 */
@Composable
private fun AdvancedOptionsPanel(iconPacks: List<IconPack>) {
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Text(
                text = stringResource(R.string.advancedOptions),
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
            )
            // No refresh hint here: the live grid below already shows what the options do.
            AdvancedOptionsContent(iconPacks, showHint = false)
        }
    }
}

/**
 * The staged global modifier controls: shape, icon scale, outline and colorize. Plain card —
 * the pinned panel wrapping it (see GlobalOptionsScreen) owns collapsing and the height cap.
 */
@Composable
private fun GlobalModifierControls(state: GlobalModifierState) {
    var colorizeSheetOpen by rememberSaveable { mutableStateOf(false) }
    var shapeColorPickerOpen by remember { mutableStateOf(false) }
    var outlineSheetOpen by remember { mutableStateOf(false) }

    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.globalModifiersTitle),
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Shape — same swatches and controls as the per-app Modifier tab.
            Text(
                text = stringResource(R.string.iconShapeTitle),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconShape.entries.forEach { shape ->
                    ShapeSwatch(
                        shape = shape,
                        selected = state.shape == shape,
                        onClick = { state.shape = shape }
                    )
                }
            }
            AnimatedVisibility(visible = state.shape != IconShape.NONE) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.shapeCrop,
                            onClick = { state.shapeCrop = true },
                            label = { Text(stringResource(R.string.shapeCrop)) }
                        )
                        FilterChip(
                            selected = !state.shapeCrop,
                            onClick = { state.shapeCrop = false },
                            label = { Text(stringResource(R.string.shapePlate)) }
                        )
                    }
                    LabeledSlider(
                        label = stringResource(R.string.shapeIconScale),
                        value = state.shapeScale,
                        onValueChange = { state.shapeScale = it },
                        valueRange = 0.5f..1.5f,
                        centered = true,
                        ruler = percentRuler()
                    )
                    if (!state.shapeCrop) {
                        val shapeStyle = state.shapeStyle
                        ColorStyleCard(
                            label = stringResource(R.string.shapeColor),
                            style = shapeStyle,
                            onClick = { shapeColorPickerOpen = true }
                        )
                        if (shapeColorPickerOpen) {
                            ColorStyleSheet(
                                title = stringResource(R.string.shapeColor),
                                initialStyle = shapeStyle,
                                sampleBitmap = null,
                                // The plate is a fill, not artwork: there is nothing for solid /
                                // monochrome / inverse to act on.
                                showSingleColorEffects = false,
                                onDismiss = { shapeColorPickerOpen = false },
                                onApply = { style ->
                                    state.shapeStyle = style
                                    shapeColorPickerOpen = false
                                }
                            )
                        }
                    }
                }
            }

            LabeledSlider(
                label = stringResource(R.string.iconScale),
                value = state.iconScale,
                onValueChange = { state.iconScale = it },
                valueRange = 0.5f..1.5f,
                centered = true,
                ruler = percentRuler()
            )

            ControlledSwitchRow(
                label = stringResource(R.string.outlineGlobal),
                checked = state.outlineAdd,
                horizontalPadding = 4.dp
            ) { state.outlineAdd = it }
            AnimatedVisibility(visible = state.outlineAdd) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabeledSlider(
                        label = stringResource(R.string.outlineThickness),
                        value = state.outlineWidth,
                        onValueChange = { state.outlineWidth = it },
                        valueRange = 1f..16f,
                        ruler = pixelRuler()
                    )
                    val outlineStyle = state.outlineStyle
                    ColorStyleCard(
                        label = stringResource(R.string.outlineColor),
                        style = outlineStyle,
                        onClick = { outlineSheetOpen = true }
                    )
                    if (outlineSheetOpen) {
                        ColorStyleSheet(
                            title = stringResource(R.string.outlineColor),
                            initialStyle = outlineStyle,
                            sampleBitmap = null,
                            showSingleColorEffects = false,
                            onDismiss = { outlineSheetOpen = false },
                            onApply = { style ->
                                state.outlineStyle = style
                                outlineSheetOpen = false
                            }
                        )
                    }
                }
            }

            ControlledSwitchRow(
                label = stringResource(R.string.globalColorize),
                checked = state.colorize,
                horizontalPadding = 4.dp
            ) { state.colorize = it }
            AnimatedVisibility(visible = state.colorize) {
                val colorizerStyle = state.colorizerStyle
                ColorStyleCard(
                    label = stringResource(R.string.colorize),
                    style = colorizerStyle,
                    onClick = { colorizeSheetOpen = true }
                )
                if (colorizeSheetOpen) {
                    ColorStyleSheet(
                        title = stringResource(R.string.colorize),
                        initialStyle = colorizerStyle,
                        sampleBitmap = null,
                        onDismiss = { colorizeSheetOpen = false },
                        onApply = { style ->
                            state.colorizerStyle = style
                            colorizeSheetOpen = false
                        }
                    )
                }
            }
        }
    }

}

/** A switch row bound directly to caller state (unlike DefaultSwitchLayout's remembered copy). */
@Composable
private fun ControlledSwitchRow(
    label: String,
    checked: Boolean,
    horizontalPadding: androidx.compose.ui.unit.Dp = 16.dp,
    hint: String? = null,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * The pinned category picker: M3 toggle buttons (round → square when checked) for which icon
 * groups the global modifiers apply to. Generated is on by default; the grid marks each
 * section green/red to match.
 */
@Composable
private fun CategoryToggleRow(state: GlobalModifierState) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(
            text = stringResource(R.string.globalApplyToLabel),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        // Scrollable so the labels are never clipped on narrow screens — each button sizes
        // to its own text.
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ToggleButton(
                checked = state.applyGenerated,
                onCheckedChange = { state.applyGenerated = it }
            ) {
                Text(stringResource(R.string.globalToggleGenerated), maxLines = 1)
            }
            ToggleButton(
                checked = state.applyExisting,
                onCheckedChange = { state.applyExisting = it }
            ) {
                Text(stringResource(R.string.globalToggleExisting), maxLines = 1)
            }
            ToggleButton(
                checked = state.applyCustom,
                onCheckedChange = { state.applyCustom = it }
            ) {
                Text(stringResource(R.string.globalToggleCustom), maxLines = 1)
            }
            ToggleButton(
                checked = state.includeEmpty,
                onCheckedChange = { state.includeEmpty = it }
            ) {
                Text(stringResource(R.string.globalToggleEmpty), maxLines = 1)
            }
        }
    }
}

/**
 * The always-visible handle UNDER the options panel: a proper tonal icon button (a bare
 * chevron was easy to miss) that collapses or re-opens the panel. Up = collapse, down =
 * expand.
 */
@Composable
private fun PanelHandle(panelVisible: Boolean, onToggle: () -> Unit) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (panelVisible) 180f else 0f,
        label = "panelChevron"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        androidx.compose.material3.FilledTonalIconButton(
            onClick = onToggle,
            modifier = Modifier.size(34.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.globalModifiersTitle),
                modifier = Modifier
                    .size(22.dp)
                    .rotate(chevronRotation)
            )
        }
    }
}

/**
 * The pinned "you are here" bar: names the section the top visible tiles belong to while the
 * grid scrolls, with the same green/red applies-marker as the section headers.
 */
@Composable
private fun CurrentSectionBar(
    section: Char,
    generatedCount: Int,
    customCount: Int,
    existingCount: Int,
    iconlessCount: Int,
    state: GlobalModifierState
) {
    val (title, applies) = when (section) {
        'g' -> stringResource(R.string.globalGeneratedSection, generatedCount) to state.applyGenerated
        'c' -> stringResource(R.string.globalCustomSection, customCount) to state.applyCustom
        's' -> stringResource(R.string.globalExistingSection, existingCount) to state.applyExisting
        else -> stringResource(R.string.globalEmptySection, iconlessCount) to state.includeEmpty
    }
    val marker = if (applies) AddedGreen else MaterialTheme.colorScheme.error
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(marker)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f)
            )
            Text(
                text = stringResource(
                    if (applies) R.string.globalSectionApplies else R.string.globalSectionSkipped
                ),
                style = MaterialTheme.typography.labelMedium,
                color = marker
            )
        }
    }
}

/**
 * A grid section title under its coloured rule: green when the global modifiers apply to the
 * section, red when it is left untouched.
 */
@Composable
private fun SectionHeader(
    title: String,
    applies: Boolean,
    hint: androidx.compose.ui.text.AnnotatedString? = null
) {
    val marker = if (applies) AddedGreen else MaterialTheme.colorScheme.error
    // Sits directly on the wallpaper — white text with a shadow, like the tile labels.
    val onWallpaperShadow = androidx.compose.ui.graphics.Shadow(
        color = Color.Black.copy(alpha = 0.85f), blurRadius = 6f
    )
    Column(Modifier.padding(top = 6.dp)) {
        HorizontalDivider(thickness = 2.dp, color = marker)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(shadow = onWallpaperShadow),
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(
                    if (applies) R.string.globalSectionApplies else R.string.globalSectionSkipped
                ),
                style = MaterialTheme.typography.labelMedium.copy(shadow = onWallpaperShadow),
                color = marker
            )
        }
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall.copy(shadow = onWallpaperShadow),
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * One preview tile for an app that already has an icon: shows the icon with [options]
 * applied (or as-is when null), the app name, and an edit badge for custom icons.
 */
@Composable
private fun IconPreviewTile(
    app: PackageInfoStruct,
    options: GenerationOptions?,
    viewModel: GlobalOptionsViewModel,
    showEditBadge: Boolean = false,
    iconSize: androidx.compose.ui.unit.Dp = 56.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val base = app.baseIcon ?: app.createdIcon
    // Cache at the largest tile size. The animated 40→56 dp resize must not create a new
    // raster and cache entry for every animation frame.
    val targetPx = with(LocalDensity.current) { 56.dp.roundToPx() }
    val cached = remember(app.key, app.internalVersion, options, targetPx) {
        options?.let { viewModel.cachedModifiedPreview(app, it, targetPx) }
    }
    // ViewModel-scoped work survives LazyGrid disposing the tile; returning to it reuses the
    // memory-bounded raster cache instead of applying the same modifier again.
    val preview by produceState(cached, app.key, app.internalVersion, options, targetPx) {
        if (value == null && base != null && options != null) {
            delay(120)
            value = viewModel.modifiedPreview(app, options, targetPx)
        }
    }
    PreviewTileFrame(app, showEditBadge, iconSize, modifier, onClick) {
        if (preview != null) {
            Image(
                painter = BitmapPainter(preview!!.asImageBitmap()),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(IconTileShape)
            )
        } else if (base != null) {
            Image(
                painter = base.getPainter(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(IconTileShape)
            )
        }
    }
}

/**
 * A tile for an app with no created icon yet. With the "Without icon" toggle on it previews
 * the icon a generation with the staged globals would produce (the app's launcher icon shows
 * dimmed until that arrives); toggled off it just shows the launcher icon as-is.
 */
@Composable
private fun GeneratedPreviewTile(
    app: PackageInfoStruct,
    sourceOptions: GenerationOptions?,
    modifierOptions: GenerationOptions?,
    viewModel: GlobalOptionsViewModel,
    iconSize: androidx.compose.ui.unit.Dp = 56.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val targetPx = with(LocalDensity.current) { 56.dp.roundToPx() }
    val cached = remember(app.key, app.internalVersion, sourceOptions, modifierOptions, targetPx) {
        sourceOptions?.let {
            viewModel.cachedGeneratedPreview(app, it, modifierOptions, targetPx)
        }
    }
    val preview by produceState(cached, app.key, app.internalVersion, sourceOptions, modifierOptions, targetPx) {
        if (value == null && sourceOptions != null) {
            delay(120)
            value = viewModel.generatedPreview(app, sourceOptions, modifierOptions, targetPx)
        }
    }
    PreviewTileFrame(app, showEditBadge = false, iconSize = iconSize, modifier = modifier, onClick = onClick) {
        if (preview != null) {
            Image(
                painter = BitmapPainter(preview!!.asImageBitmap()),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(IconTileShape)
            )
        } else {
            val fallback = rememberAppBitmap(app)
            if (fallback != null) {
                Image(
                    painter = BitmapPainter(fallback),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(IconTileShape)
                        // Dim only while an actual preview is on its way; with the category
                        // toggled off the launcher icon IS the content.
                        .alpha(if (sourceOptions == null) 1f else 0.35f)
                )
            }
        }
    }
}

/** The shared tile chrome: icon slot on top, single-line app name below, optional edit badge. */
@Composable
private fun PreviewTileFrame(
    app: PackageInfoStruct,
    showEditBadge: Boolean,
    iconSize: androidx.compose.ui.unit.Dp = 56.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    iconContent: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(CardShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp)
    ) {
        Box(Modifier.size(iconSize)) {
            iconContent()
            if (showEditBadge) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp),
                    shape = SwatchShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Create,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(3.dp)
                    )
                }
            }
        }
        // Launcher-style label: white with a soft shadow, readable on any wallpaper (the
        // grid sits directly on the transparent, wallpaper-showing area).
        Text(
            text = app.appName,
            style = MaterialTheme.typography.labelSmall.copy(
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = Color.Black.copy(alpha = 0.85f), blurRadius = 6f
                )
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = Color.White,
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
        )
    }
}

/**
 * The modifiers-only per-app editor opened from the grid: the full Modifier tab over the
 * app's current icon with a before/after preview. Apply stores the result as a custom icon
 * (a refresh won't replace it), exactly like the main edit dialog's Apply.
 */
@Composable
private fun GlobalIconEditDialog(app: PackageInfoStruct, onDismiss: () -> Unit) {
    val viewModel: GlobalOptionsViewModel = hiltViewModel()
    val view = LocalView.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val externalEditorError = stringResource(R.string.noImageEditorAvailable)
    val originLockedMessage = stringResource(R.string.iconOriginLocked)

    var imageEdit by remember { mutableStateOf(ImageEdit.NONE) }
    var iconColor by remember { mutableStateOf(Color.White) }
    var useVector by remember { mutableStateOf(false) }
    val adjustments = remember { AdjustmentState() }

    // The icon the modifiers act on: the stored icon, or the app's own launcher icon for
    // apps that don't have one yet.
    val heroBitmap = remember(app) {
        runCatching { app.icon.toSafeBitmapOrNull() }.getOrNull()
    }
    val base = remember(app) {
        app.baseIcon ?: app.createdIcon ?: heroBitmap?.let { BitmapIconDrawable(it, false) }
    }

    val outlineEraseMask = remember(adjustments.eraseStrokes) {
        if (adjustments.eraseStrokes.isEmpty()) null else buildEraseMask(adjustments.eraseStrokes)
    }

    val backgroundBrushOperations = remember(adjustments.backgroundBrushStrokes) {
        buildBackgroundBrushOperations(adjustments.backgroundBrushStrokes)
    }
    val options = GenerationOptions(
        primarySource = Source.APPLICATION_ICON,
        primaryImageEdit = imageEdit,
        primaryTextType = TEXT_TYPE_DEFAULT,
        primaryIconPack = "",
        color = iconColor.toArgb(),
        bgColor = if (adjustments.iconShape != IconShape.NONE && !adjustments.shapeCrop) {
            adjustments.shapeColor.toArgb()
        } else android.graphics.Color.TRANSPARENT,
        vector = useVector,
        materialYou = false,
        themed = false,
        override = true
    ).withModifierAdjustments(
        adjustments = adjustments,
        imageEdit = imageEdit,
        outlineEraseMask = outlineEraseMask,
        backgroundBrushOperations = backgroundBrushOperations
    )

    // The same pipeline the tile preview uses, so the colour sheets and segment pickers here
    // behave exactly like the edit dialog's.
    val renderWith: suspend (GenerationOptions) -> Bitmap? = { previewOptions ->
        base?.let {
            withContext(Dispatchers.Default) {
                runCatching { viewModel.applyModifier(it, previewOptions).toBitmap() }.getOrNull()
            }
        }
    }
    val modifierPreviews = rememberModifierPreviews(
        options = options,
        adjustments = adjustments,
        sourceKey = base,
        render = renderWith
    )

    var preview by remember { mutableStateOf(base) }
    var enlarged by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    // Skip the very first pass: an untouched run through applyModifier would needlessly
    // rasterise a stored vector icon, and Apply must stay disabled until an actual edit.
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(options) {
        val source = base ?: return@LaunchedEffect
        if (!touched) {
            touched = true
            preview = source
            return@LaunchedEffect
        }
        generating = true
        preview = withContext(Dispatchers.Default) {
            runCatching { viewModel.applyModifier(source, options) }.getOrDefault(source)
        }
        generating = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.dismiss))
                    }
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            view.performConfirmHaptic()
                            val edited = preview
                            if (edited != null) {
                                scope.launch {
                                    val stored = viewModel.applyEditedIcon(
                                        app, edited, sourcePackName = app.sourcePackName
                                    )
                                    if (!stored) toaster.show(originLockedMessage)
                                    onDismiss()
                                }
                            } else {
                                onDismiss()
                            }
                        },
                        enabled = preview != null && !generating && preview !== base,
                        shape = dev.renkinProject.renkin.ui.theme.FieldShape
                    ) {
                        Text(stringResource(R.string.apply))
                    }
                }
                HorizontalDivider()

                // Before/after strip so the modifier's effect is visible while scrolled deep
                // into the controls below.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(56.dp)) {
                        base?.let {
                            Image(
                                painter = it.getPainter(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(IconTileShape)
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    // Same affordance as the edit dialog's New slot: tap to judge the result
                    // at a size that actually shows the detail a launcher will.
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clickable(enabled = preview != null) { enlarged = true },
                        contentAlignment = Alignment.Center
                    ) {
                        preview?.let {
                            Image(
                                painter = it.getPainter(),
                                contentDescription = stringResource(R.string.iconNew),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(IconTileShape)
                            )
                        }
                        if (generating) {
                            LoadingIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                preview?.takeIf { enlarged }?.let { EnlargedIconDialog(it) { enlarged = false } }
                HorizontalDivider()

                ModifierTab(
                    source = Source.APPLICATION_ICON,
                    imageEdit = imageEdit,
                    iconColor = iconColor,
                    useVector = useVector,
                    useMaterialYou = false,
                    adjustments = adjustments,
                    centerPreview = remember(preview) { preview?.toModifierBitmap() },
                    previewGenerating = generating,
                    sampleBitmap = heroBitmap,
                    previews = modifierPreviews,
                    onImageEditChange = { imageEdit = it },
                    onColorChange = { iconColor = it },
                    onVectorChange = { useVector = it },
                    onMaterialYouChange = { },
                    onEditExternally = { toolbox ->
                        val icon = preview
                        if (icon != null) {
                            scope.launch {
                                val bitmap = withContext(Dispatchers.Default) {
                                    icon.toModifierBitmap()
                                }
                                val opened = if (toolbox) openInImageToolbox(context, bitmap)
                                    else editInAnotherApp(context, bitmap)
                                if (!opened) toaster.show(externalEditorError)
                            }
                        }
                    }
                )
            }
        }
    }
}
