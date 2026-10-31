package dev.renkinProject.renkin.feature.launch

import android.content.Context
import android.provider.Settings

enum class LaunchFirePlayback {
    /** Cold start of a new process: the full fire animation. */
    Play,
    /** Plum launch window shown again (new Activity, process restore): fade it out only. */
    FadeOnly,
    None
}

object LaunchFireGate {
    @Volatile
    private var claimedInProcess = false

    /** True exactly once per process. */
    @Synchronized
    fun claimFirstLaunch(): Boolean {
        if (claimedInProcess) return false
        claimedInProcess = true
        return true
    }

    fun decide(firstInProcess: Boolean, restoredAfterProcessDeath: Boolean, animatorScale: Float): LaunchFirePlayback =
        when {
            animatorScale == 0f -> LaunchFirePlayback.None
            firstInProcess && !restoredAfterProcessDeath -> LaunchFirePlayback.Play
            else -> LaunchFirePlayback.FadeOnly
        }

    /** A hand-rolled frame loop ignores "Remove animations", so the scale is read explicitly. */
    fun animatorScale(context: Context): Float =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
}
