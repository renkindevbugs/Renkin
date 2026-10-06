@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.renkinProject.renkin.ui.modifier

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.LabeledSlider
import dev.renkinProject.renkin.ui.MaterialYouColorControls
import dev.renkinProject.renkin.ui.MaterialYouPackAdjustmentState
import dev.renkinProject.renkin.ui.ModifierPreviews
import dev.renkinProject.renkin.ui.centeredSliderToLineWeight
import dev.renkinProject.renkin.ui.lineWeightToCenteredSlider
import dev.renkinProject.renkin.ui.percentRuler
import dev.renkinProject.renkin.ui.theme.CardShape

@Composable
internal fun MaterialYouPackSection(
    adjustments: MaterialYouPackAdjustmentState,
    schemes: List<Pair<Color, Color>>,
    previews: ModifierPreviews?,
    sampleBitmap: Bitmap?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.variantMaterialYou),
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (adjustments.selectedScheme >= 0 || adjustments.strokeScale != 1f) {
            TextButton(onClick = adjustments::reset) {
                Text(stringResource(R.string.resetToDefault))
            }
        }
    }
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MaterialYouColorControls(
                schemes = schemes,
                selectedScheme = adjustments.selectedScheme,
                onSchemeChange = { adjustments.selectedScheme = it },
                customForeground = adjustments.customForeground,
                customBackground = adjustments.customBackground,
                onCustomForegroundChange = { adjustments.customForeground = it },
                onCustomBackgroundChange = { adjustments.customBackground = it },
                renderForeground = previews?.materialYouPackForeground,
                renderBackground = previews?.materialYouPackBackground,
                allowOriginal = true,
                sampleBitmap = sampleBitmap
            )
            LabeledSlider(
                label = stringResource(R.string.lineThickness),
                value = lineWeightToCenteredSlider(adjustments.strokeScale),
                onValueChange = {
                    adjustments.strokeScale = centeredSliderToLineWeight(it)
                },
                valueRange = 0f..2f,
                centered = true,
                ruler = percentRuler(
                    valueRange = 0.5f..2f,
                    toRulerValue = ::centeredSliderToLineWeight,
                    fromRulerValue = ::lineWeightToCenteredSlider
                )
            )
        }
    }
}
