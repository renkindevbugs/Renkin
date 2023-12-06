package com.kaanelloed.iconeration.icon

import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

class AdaptiveIcon(val foreground: BaseIcon, val background: BaseIcon, val monochrome: BaseIcon?): BaseIcon()