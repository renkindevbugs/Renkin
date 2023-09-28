package com.kaanelloed.iconeration.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ColorButton(colors: List<Color>, onColorSelected: (Color) -> Unit) {
    var colorPickerOpen by rememberSaveable { mutableStateOf(false) }
    var currentlySelected by rememberSaveable(saver = colourSaver()) { mutableStateOf(colors[0]) }

    Box(
        modifier = Modifier
            .padding(top = 16.dp, start = 5.dp, end = 5.dp, bottom = 5.dp)
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
                text = "Select color",
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
            colorList = colors,
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
    colorList: List<Color>,
    onDismiss: (() -> Unit),
    currentlySelected: Color,
    onColorSelected: ((Color) -> Unit) // when a colour is picked
) {
    val gridState = rememberLazyGridState()

    AlertDialog(
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.outline,
        onDismissRequest = onDismiss,
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState
            ) {
                items(colorList) { color ->
                    // Add a border around the selected colour only
                    var borderWidth = 0.dp
                    if (currentlySelected == color) {
                        borderWidth = 2.dp
                    }

                    Canvas(modifier = Modifier
                        .padding(16.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .border(
                            borderWidth,
                            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                            RoundedCornerShape(20.dp)
                        )
                        .background(color)
                        .requiredSize(70.dp)
                        .clickable {
                            onColorSelected(color)
                            onDismiss()
                        }
                    ) {
                    }
                }
            }
        },
        confirmButton = {}
    )
}


fun colourSaver() = Saver<MutableState<Color>, String>(
    save = { state -> state.value.toHexString() },
    restore = { value -> mutableStateOf(value.toColor()) }
)

fun Color.toHexString(): String {
    return String.format(
        "#%02x%02x%02x%02x", (this.alpha * 255).toInt(),
        (this.red * 255).toInt(), (this.green * 255).toInt(), (this.blue * 255).toInt()
    )
}

fun String.toColor(): Color {
    return Color(android.graphics.Color.parseColor(this))
}