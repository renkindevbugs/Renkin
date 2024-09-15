package com.kaanelloed.iconeration.vector

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SweepGradientShader

class ReferenceBrush(val reference: String): ShaderBrush() {
    override fun createShader(size: Size): Shader {
        //Dummy shader
        return SweepGradientShader(Offset(0f, 0f), listOf(Color.Unspecified))
    }
}