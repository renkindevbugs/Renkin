@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.renkinProject.renkin.ui.modifier

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.ui.AdjustmentState
import dev.renkinProject.renkin.ui.LabeledSlider
import dev.renkinProject.renkin.ui.OptionCard
import dev.renkinProject.renkin.ui.OptionGroup
import dev.renkinProject.renkin.ui.percentRuler

@Composable
internal fun IconAdjustmentsSection(
    adjustments: AdjustmentState,
    onOpenPosition: () -> Unit
) {
    Text(
        text = stringResource(R.string.adjustments),
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.onSurface
    )
    OptionGroup {
        LabeledSlider(
            label = stringResource(R.string.iconScale),
            value = adjustments.iconScale,
            onValueChange = { adjustments.iconScale = it },
            valueRange = 0.5f..1.5f,
            centered = true,
            ruler = percentRuler()
        )
    }
    OptionCard(
        label = stringResource(R.string.position),
        onClick = onOpenPosition,
        trailing = {
            val adjusted = adjustments.iconOffsetX != 0f || adjustments.iconOffsetY != 0f
            Text(
                text = if (adjusted) {
                    stringResource(R.string.positionCustom)
                } else {
                    stringResource(R.string.positionDefault)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    )
}
