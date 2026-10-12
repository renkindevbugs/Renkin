package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.icon.creator.BackgroundBrushOperation
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.withModifiers
import dev.renkinProject.renkin.icon.creator.withExport
import dev.renkinProject.renkin.icon.creator.withEffect
import dev.renkinProject.renkin.icon.creator.IconShape
import dev.renkinProject.renkin.icon.creator.OutlineMode
import dev.renkinProject.renkin.icon.creator.ModifierPresetPayload
import dev.renkinProject.renkin.icon.creator.withModifierPreset
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.renkinProject.renkin.icon.creator.ColorFill
import dev.renkinProject.renkin.icon.creator.OutlineModifier
import dev.renkinProject.renkin.icon.creator.TransformModifier

/**
 * Everything the Modifier tab needs to preview a colour before it is applied, plus the artwork
 * its segment pickers cluster. Both hosts of the tab (the edit dialog and Global options' per-icon
 * editor) build these the same way, so the wiring lives here instead of twice.
 */
internal data class ModifierPreviews(
    /** The icon as the colourize step sees it: no scale, offset, shape, outline or shadow yet. */
    val colorizeBase: Bitmap?,
    /** Current modifier stack rendered from an unclipped zero-offset canvas for auto-centering. */
    val positionBase: suspend () -> Bitmap?,
    /** Remove-background output before later geometry and silhouette treatments alter it. */
    val backgroundBrush: suspend () -> Bitmap?,
    val colorize: suspend (ColorizerStyle) -> Bitmap?,
    val shape: suspend (ColorizerStyle) -> Bitmap?,
    val outline: suspend (ColorizerStyle) -> Bitmap?,
    val shadow: suspend (ColorizerStyle) -> Bitmap?,
    /** Current icon rendered with a reusable preset substituted into its source-specific options. */
    val preset: suspend (ModifierPresetPayload) -> Bitmap?,
    /** Invalidates row previews when the current icon/options change. */
    val presetKey: Any?,
    /** Resets editor-session selection when the host starts editing another source icon. */
    val presetSourceKey: Any?,
    val layers: suspend (index: Int, draft: ColorizerStyle) -> Bitmap?,
    /** A pack's own Material You icon under a draft colour — its layers take the first stop. */
    val materialYouPackForeground: suspend (ColorizerStyle) -> Bitmap?,
    val materialYouPackBackground: suspend (ColorizerStyle) -> Bitmap?
)

/**
 * Wires [render] — the host's own "generate with these options" call — into the previews the tab
 * expects. [options] must be the options the host is currently previewing with.
 */
@Composable
internal fun rememberModifierPreviews(
    options: GenerationOptions,
    adjustments: AdjustmentState,
    // Options do not identify an explicitly picked drawable. Keep the source separate so choosing
    // another icon in the same pack refreshes the segment/background picker base as well.
    sourceKey: Any?,
    render: suspend (GenerationOptions) -> Bitmap?
): ModifierPreviews {
    val currentRender by rememberUpdatedState(render)
    val currentOptions by rememberUpdatedState(options)
    val presetRenderMutex = remember { Mutex() }
    val presetPreviewCache = remember(options, sourceKey) {
        object : LruCache<ModifierPresetPayload, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: ModifierPresetPayload, value: Bitmap): Int =
                value.allocationByteCount
        }
    }
    var colorizeBase by remember { mutableStateOf<Bitmap?>(null) }

    // Colourize runs before every modifier below. Sanitising the key also prevents background
    // strokes from needlessly regenerating this untouched source after every brush gesture.
    val colorizeBaseOptions = options.withModifiers {
        copy(
            effect = effect.copy(
                imageEdit = ImageEdit.NONE,
                backgroundRemoval = effect.backgroundRemoval.copy(brushOperations = emptyList())
            ),
            transform = TransformModifier(), shape = null, outline = null, shadow = null
        )
    }
    LaunchedEffect(colorizeBaseOptions, sourceKey) {
        colorizeBase = currentRender(colorizeBaseOptions)
    }

    return remember(colorizeBase, options, sourceKey) {
        ModifierPreviews(
            colorizeBase = colorizeBase,
            positionBase = {
                currentRender(
                    currentOptions.withModifiers {
                        copy(transform = transform.copy(offsetX = 0f, offsetY = 0f))
                    }
                )
            },
            backgroundBrush = {
                currentRender(
                    currentOptions.withModifiers {
                        copy(
                            transform = TransformModifier(), shape = null, outline = null, shadow = null
                        )
                    }
                )
            },
            colorize = { style ->
                currentRender(currentOptions.asColorizePreview(style))
            },
            shape = { style ->
                currentRender(currentOptions.withShapeStyle(style))
            },
            outline = { style ->
                currentRender(
                    currentOptions.withModifiers {
                        copy(outline = OutlineModifier(
                            adjustments.outlineMode.takeIf { it != OutlineMode.NONE } ?: OutlineMode.ADD,
                            outline?.width ?: 6f,
                            ColorFill(style.firstColor, style),
                            outline?.eraseMask
                        ))
                    }
                )
            },
            shadow = { style ->
                currentRender(
                    currentOptions.withModifiers {
                        copy(shadow =
                            adjustments.shadowModifier().copy(style = style, enabled = true)
                        )
                    }
                )
            },
            // A visible library can request several heavyweight renders at once. Serialising them
            // keeps slider interaction and the main preview responsive while thumbnails fill in.
            preset = { payload ->
                presetRenderMutex.withLock {
                    presetPreviewCache.get(payload)
                        ?: currentRender(currentOptions.withModifierPreset(payload))?.also {
                            presetPreviewCache.put(payload, it)
                        }
                }
            },
            presetKey = options to sourceKey,
            presetSourceKey = sourceKey,
            materialYouPackForeground = { style ->
                currentRender(
                    currentOptions.withModifiers {
                        copy(materialYouPack = materialYouPack.copy(foreground = style.firstColor, customForeground = style))
                    }
                )
            },
            materialYouPackBackground = { style ->
                currentRender(
                    currentOptions.withModifiers {
                        copy(materialYouPack = materialYouPack.copy(background = style.firstColor, customBackground = style))
                    }
                )
            },
            layers = { index, draft ->
                currentRender(
                    currentOptions.withModifiers {
                        withEffect {
                            copy(
                                imageEdit = ImageEdit.COLORIZE_SEGMENTS,
                                segments = adjustments.colorizeLayers.mapIndexed { i, layer ->
                                    if (i == index) layer.copy(style = draft) else layer
                                }
                            )
                        }
                    }
                )
            }
        )
    }
}

/**
 * Applies the Modifier tab's shared state to source-specific [GenerationOptions]. Keeping this
 * mapping in one place prevents the regular and Global per-icon editors from silently supporting
 * different controls when a new adjustment is added.
 */
internal fun GenerationOptions.withModifierAdjustments(
    adjustments: AdjustmentState,
    imageEdit: ImageEdit,
    outlineEraseMask: Bitmap?,
    // Already rasterised and memoised by the caller, so options only change after a finished stroke.
    backgroundBrushOperations: List<BackgroundBrushOperation> = emptyList()
): GenerationOptions {
    // The generated Material You background remains source-owned unless a shape plate replaces it.
    val plateStyle = if (adjustments.iconShape != IconShape.NONE && !adjustments.shapeCrop) {
        adjustments.shapeStyle
    } else export.background.style
    return withExport { copy(background = background.copy(style = plateStyle)) }
        .withModifiers {
            adjustments.applyToModifierStack(
                this, imageEdit, effect.colorizer.firstColor, outlineEraseMask, backgroundBrushOperations
            )
        }
}

/** Previews plain Colorize even when the editor currently selects a different image edit. */
internal fun GenerationOptions.asColorizePreview(style: ColorizerStyle): GenerationOptions =
    withModifiers {
        withEffect { copy(imageEdit = ImageEdit.COLORIZE, colorizer = style, segments = emptyList()) }
    }

internal fun GenerationOptions.withShapeStyle(style: ColorizerStyle): GenerationOptions {
    val configuredShape = modifiers.shape
    return if (configuredShape?.fill != null) {
        withModifiers { copy(shape = configuredShape.copy(fill = ColorFill(style.firstColor, style))) }
    } else {
        withExport { copy(background = ColorFill(style.firstColor, style)) }
    }
}
