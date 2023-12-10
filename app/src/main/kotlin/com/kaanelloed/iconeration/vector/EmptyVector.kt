package com.kaanelloed.iconeration.vector

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp

class EmptyVector {
    companion object {
        fun createEmptyVector(): ImageVector {
            val builder = ImageVector.Builder(
                defaultWidth = 108F.dp,
                defaultHeight = 108F.dp,
                viewportWidth = 108F,
                viewportHeight = 108F
            )

            val path = PathNode.MoveTo(0F, 0F)

            builder.addPath(listOf())

            return builder.build()
        }
    }
}