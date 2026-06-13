package dev.alembiconsProject.alembicons.packages

import android.os.Build

class PackageVersion {
    companion object {
        fun is22OrMore(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1
        }

        fun is23OrMore(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        }

        fun is24OrMore(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        }

        fun is25OrMore(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1
        }

        fun is26OrMore(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        }

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

        fun is35OrMore(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
        }
    }
}