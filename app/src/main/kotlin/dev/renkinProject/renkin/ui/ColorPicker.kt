package dev.renkinProject.renkin.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.renkinProject.renkin.ui.theme.FieldShape
import dev.renkinProject.renkin.extension.alphaInt
import dev.renkinProject.renkin.extension.blueInt
import dev.renkinProject.renkin.extension.greenInt
import dev.renkinProject.renkin.extension.redInt
import dev.renkinProject.renkin.extension.toColor
import dev.renkinProject.renkin.extension.toHexString
import dev.renkinProject.renkin.extension.toNullableColor
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import kotlin.math.min
import kotlin.math.roundToInt
import com.github.skydoves.colorpicker.compose.AlphaSlider
import com.github.skydoves.colorpicker.compose.AlphaTile
import com.github.skydoves.colorpicker.compose.BrightnessSlider
import com.github.skydoves.colorpicker.compose.ColorEnvelope
import com.github.skydoves.colorpicker.compose.ColorPickerController
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController

@Composable
fun ColorButton(caption: String, initialColor: Color, onColorSelected: (Color) -> Unit) {
    var colorPickerOpen by rememberSaveable { mutableStateOf(false) }
    var currentlySelected by rememberSaveable(saver = colorSaver()) { mutableStateOf(initialColor) }

    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(FieldShape)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline,
                FieldShape
            )
            .clickable {
                colorPickerOpen = true
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = caption,
            )

            Canvas(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(20))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                        RoundedCornerShape(20)
                    )
                    .background(currentlySelected)
                    .clickable {
                        colorPickerOpen = true
                    }
            ) {}
        }

    }

    if (colorPickerOpen) {
        ColorDialog(
            onDismiss = { colorPickerOpen = false },
            currentlySelected = currentlySelected,
            onColorSelected = {
                currentlySelected = it
                onColorSelected(it)
            }
        )
    }
}

@Composable
fun ColorDialog(
    onDismiss: (() -> Unit),
    currentlySelected: Color,
    onColorSelected: ((Color) -> Unit), // when a colour is picked
    // Optional image to sample colours from (eyedropper). Null hides the picker section.
    sampleBitmap: Bitmap? = null
) {
    val controller = rememberColorPickerController()
    // What was set when the dialog opened. Picks apply live (the icon previews behind the
    // dialog), so cancelling means actively putting this colour back.
    val originalColor = remember { currentlySelected }
    val cancel = {
        onColorSelected(originalColor)
        onDismiss()
    }

    RenkinAlertDialog(
        onDismissRequest = cancel,
        title = { Text(stringResource(R.string.pickColorTitle)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HsvColorPicker(modifier = Modifier.height(200.dp)
                    , controller = controller
                    , onColorChanged = {
                            colorEnvelope: ColorEnvelope ->
                        if (colorEnvelope.fromUser)
                            onColorSelected(colorEnvelope.color)
                    }
                    , initialColor = currentlySelected
                )

                AlphaSlider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                        .height(26.dp),
                    controller = controller,
                    initialColor = currentlySelected
                )

                BrightnessSlider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp, 0.dp, 10.dp, 10.dp)
                        .height(26.dp),
                    controller = controller,
                    initialColor = currentlySelected
                )

                // Before → after: the colour that was set when the dialog opened next to the
                // live pick. Tapping the old swatch is an undo — it re-selects the original.
                Row(
                    modifier = Modifier.align(CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(horizontalAlignment = CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(originalColor)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                .clickable {
                                    controller.selectByColor(originalColor, true)
                                    onColorSelected(originalColor)
                                }
                        )
                        Text(
                            text = stringResource(R.string.iconCurrent),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(horizontalAlignment = CenterHorizontally) {
                        AlphaTile(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                            controller = controller
                        )
                        Text(
                            text = stringResource(R.string.iconNew),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                RGBFields(
                    modifier = Modifier.padding(10.dp, 10.dp, 10.dp, 0.dp)
                    , controller = controller
                )

                HexField(
                    modifier = Modifier.padding(10.dp)
                    , controller = controller
                )

                // Eyedropper: sample a colour straight from the app's icon instead of
                // guessing its RGB. Only shown when the caller hands us a bitmap.
                sampleBitmap?.takeIf { !it.isRecycled }?.let { bitmap ->
                    Text(
                        text = stringResource(R.string.eyedropperHint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp, 4.dp, 10.dp, 6.dp)
                    )
                    IconEyedropper(
                        bitmap = bitmap,
                        onPick = { picked ->
                            controller.selectByColor(picked, true)
                            onColorSelected(picked)
                        }
                    )
                }
            }
        },
        // Same chrome as the watch-apply modal: ✕ throws the pick away (the original colour
        // comes back), ✓ keeps it. Back gesture / tapping outside cancel too.
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FilledTonalIconButton(
                    onClick = cancel,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Filled.Close, stringResource(R.string.dismiss))
                }
                FilledIconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Done, stringResource(R.string.confirm))
                }
            }
        }
    )
}

/**
 * Draws [bitmap] fitted into a square and lets the user tap or drag to sample the colour
 * under their finger, feeding it back through [onPick]. Fully transparent pixels are ignored
 * so an accidental tap on empty space doesn't wipe the colour to transparent.
 */
@Composable
private fun IconEyedropper(bitmap: Bitmap, onPick: (Color) -> Unit) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    // The rect the image actually occupies inside the canvas (letterboxed for non-square icons),
    // shared from the draw pass to the gesture handlers so taps map to the right pixel.
    var drawnRect by remember(bitmap) { mutableStateOf(Rect.Zero) }
    var touch by remember(bitmap) { mutableStateOf<Offset?>(null) }

    val sample: (Offset) -> Unit = { pos ->
        val r = drawnRect
        if (r.width > 0f && r.height > 0f && r.contains(pos)) {
            val fx = ((pos.x - r.left) / r.width).coerceIn(0f, 1f)
            val fy = ((pos.y - r.top) / r.height).coerceIn(0f, 1f)
            val px = (fx * (bitmap.width - 1)).roundToInt()
            val py = (fy * (bitmap.height - 1)).roundToInt()
            val argb = bitmap.getPixel(px, py)
            if (argb ushr 24 != 0) {
                touch = pos
                onPick(Color(argb))
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(20))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20))
                .pointerInput(bitmap) { detectTapGestures { sample(it) } }
                .pointerInput(bitmap) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        sample(change.position)
                    }
                }
        ) {
            val scale = min(size.width / image.width, size.height / image.height)
            val w = image.width * scale
            val h = image.height * scale
            val left = (size.width - w) / 2f
            val top = (size.height - h) / 2f
            drawnRect = Rect(left, top, left + w, top + h)

            drawImage(
                image = image,
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                filterQuality = FilterQuality.None
            )

            // Loupe ring at the last sampled point: white core with a dark halo for contrast on any icon.
            touch?.let { p ->
                drawCircle(Color.Black, radius = 8.dp.toPx(), center = p, style = Stroke(width = 3.dp.toPx()))
                drawCircle(Color.White, radius = 8.dp.toPx(), center = p, style = Stroke(width = 1.5.dp.toPx()))
            }
        }
    }
}

@Composable
fun RGBFields(controller: ColorPickerController
              , modifier: Modifier = Modifier
              , internalModifier: Modifier = Modifier) {
    val alpha = controller.selectedColor.value.alphaInt
    val red = controller.selectedColor.value.redInt
    val green = controller.selectedColor.value.greenInt
    val blue = controller.selectedColor.value.blueInt

    Row(modifier.fillMaxWidth()) {
        RGBField(modifier = internalModifier.fillMaxWidth(0.33f)
            , value = red
            , prefix = { Text("R") }
            , onValueChange = {
                val newColor = Color(it, green, blue, alpha)
                controller.selectByColor(newColor, true)
        })

        RGBField(modifier = internalModifier.fillMaxWidth(0.5f)
            , value = green
            , prefix = { Text("G") }
            , onValueChange = {
                val newColor = Color(red, it, blue, alpha)
                controller.selectByColor(newColor, true)
            })

        RGBField(modifier = internalModifier.fillMaxWidth()
            , value = blue
            , prefix = { Text("B") }
            , onValueChange = {
                val newColor = Color(red, green, it, alpha)
                controller.selectByColor(newColor, true)
            })
    }
}

@Composable
fun RGBField(modifier: Modifier
             , value: Int
             , onValueChange: (Int) -> Unit
             , prefix: @Composable (() -> Unit)? = null) {
    val keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    val style = TextStyle.Default.copy(textAlign = TextAlign.Center)

    var lastValue by rememberSaveable { mutableIntStateOf(value) }
    var currentValue by rememberSaveable { mutableIntStateOf(value) }
    var textValue by rememberSaveable { mutableStateOf(value.toString()) }

    if (value != lastValue) {
        textValue = value.toString()
        currentValue = value
        lastValue = value
    }

    OutlinedTextField(modifier = modifier
        , value = textValue
        , isError = currentValue !in 0 .. 255
        , singleLine = true
        , keyboardOptions = keyboardOptions
        , prefix = prefix
        , textStyle = style
        , onValueChange = {
            textValue = it.getDigitsOnly().left(3)
            currentValue = textValue.ifEmpty { "-1" }.toInt()

            if (currentValue in 0 .. 255) {
                onValueChange(currentValue)
            }
        })
}

@Composable
fun HexField(modifier: Modifier
             , controller: ColorPickerController) {
    val controllerValue = controller.selectedColor.value.toHexString().trim('#')
    var lastControllerValue by rememberSaveable { mutableStateOf(controllerValue) }
    var value by rememberSaveable { mutableStateOf(controllerValue) }

    if (controllerValue != lastControllerValue) {
        value = controllerValue
        lastControllerValue = controllerValue
    }

    OutlinedTextField(modifier = modifier
        , value = value
        , isError = ("#$value").toNullableColor() == null
        , prefix = { Text("#") }
        , singleLine = true
        , onValueChange = {
            value = it.trim('#').left(8)

            val color = ("#$value").toNullableColor()
            if (color != null && value.length == 8) {
                controller.selectByColor(color, true)
            }
    })
}

fun colorSaver() = Saver<MutableState<Color>, String>(
    save = { state -> state.value.toHexString() },
    restore = { value -> mutableStateOf(value.toColor()) }
)

fun String.left(length: Int): String {
    if (this.isEmpty() || length < 0) {
        return ""
    }

    if (length > this.length) {
        return this
    }

    return this.substring(0 until length)
}

fun String.getDigitsOnly(): String {
    val builder = StringBuilder()

    for (c in this) {
        if (c.isDigit()) {
            builder.append(c)
        }
    }

    return builder.toString()
}