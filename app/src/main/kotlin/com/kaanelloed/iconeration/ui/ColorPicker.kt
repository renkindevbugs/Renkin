package com.kaanelloed.iconeration.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
            .padding(8.dp)
            .fillMaxWidth(0.8f)
            .clip(RoundedCornerShape(20))
            .border(
                2.dp,
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                RoundedCornerShape(20)
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
private fun ColorDialog(
    onDismiss: (() -> Unit),
    currentlySelected: Color,
    onColorSelected: ((Color) -> Unit) // when a colour is picked
) {
    val controller = rememberColorPickerController()

    AlertDialog(
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.outline,
        onDismissRequest = onDismiss,
        text = {
            Column {
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
                        .height(35.dp),
                    controller = controller,
                    initialColor = currentlySelected
                )

                BrightnessSlider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp, 0.dp, 10.dp, 10.dp)
                        .height(35.dp),
                    controller = controller,
                    initialColor = currentlySelected
                )

                AlphaTile(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .align(CenterHorizontally),
                    controller = controller
                )

                RGBFields(
                    modifier = Modifier.padding(10.dp, 10.dp, 10.dp, 0.dp)
                    , controller = controller
                )

                HexField(
                    modifier = Modifier.padding(10.dp)
                    , controller = controller
                )
            }
        },
        confirmButton = {}
    )
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

fun Color.toHexString(): String {
    return String.format(
        "#%02x%02x%02x%02x", this.alphaInt, this.redInt, this.greenInt, this.blueInt
    )
}

val Color.alphaInt: Int
    get() = floatTo255Component(this.alpha)

val Color.redInt: Int
    get() = floatTo255Component(this.red)

val Color.greenInt: Int
    get() = floatTo255Component(this.green)

val Color.blueInt: Int
    get() = floatTo255Component(this.blue)

private fun floatTo255Component(component: Float): Int {
    return (component * 255).toInt()
}

fun String.toColor(): Color {
    return Color(AndroidColor.parseColor(this))
}

fun String.toNullableColor(): Color? {
    return try {
        this.toColor()
    } catch (ex: IllegalArgumentException) {
        null
    }
}

fun Color.toInt(): Int {
    return AndroidColor.parseColor(this.toHexString())
}

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