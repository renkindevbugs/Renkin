package com.kaanelloed.iconeration.vector.brush

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush

class ReferenceBrush(val reference: String, val shaderBrush: ShaderBrush): ShaderBrush() {
    constructor(reference: String, color: Color = Color.White)
            : this(reference, SolidColorShader(color))

    override fun createShader(size: Size): Shader {
        return shaderBrush.createShader(size)
    }
}