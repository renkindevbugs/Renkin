package com.kaanelloed.iconeration.vector.brush

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SweepGradientShader

class SolidColorShader(val color: Color): ShaderBrush() {
    override fun createShader(size: Size): Shader {
        return SweepGradientShader(Offset(0f, 0f), listOf(color, color))
    }
}