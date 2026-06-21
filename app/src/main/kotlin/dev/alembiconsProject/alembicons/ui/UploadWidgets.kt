package dev.alembiconsProject.alembicons.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.alembiconsProject.alembicons.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun UploadedImageThumbnail(
    file: File,
    selected: Boolean,
    marked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var thumbnail by remember(file.absolutePath) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(file.absolutePath) {
        // Grid cells are small, so decode a downsampled bitmap — decoding the full
        // ~500px image for every thumbnail is what made bulk uploads stutter
        thumbnail = withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }
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
