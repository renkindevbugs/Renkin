package com.kaanelloed.iconeration.icon

import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build

class AdaptiveIcon(val foreground: BaseIcon, val background: BaseIcon, val monochrome: BaseIcon?): BaseIcon() {
    fun toDrawable(): AdaptiveIconDrawable {
        //TODO()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AdaptiveIconDrawable(null, null, null)
        } else {
            AdaptiveIconDrawable(null, null)
        }
    }
}