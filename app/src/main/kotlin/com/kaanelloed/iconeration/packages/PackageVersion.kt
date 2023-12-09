package com.kaanelloed.iconeration.packages

import android.os.Build

class PackageVersion {
    companion object {
        fun is27OrMore(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
        }

        fun is28OrMore(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        }

        fun is29OrMore(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        }

        fun is30OrMore(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        }

        fun is31OrMore(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        }

        fun is32OrMore(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2
        }

        fun is33OrMore(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        }

        fun is34OrMore(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        }
    }
}