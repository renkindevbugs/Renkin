package dev.renkinProject.renkin.util

import android.os.SystemClock
import dev.renkinProject.renkin.BuildConfig

internal suspend fun <T> timeProfilePhase(phase: String, operation: suspend () -> T): T {
    val started = SystemClock.elapsedRealtime()
    try {
        return operation()
    } finally {
        if (BuildConfig.DEBUG) {
            Log.debug("ProfilePerformance", "$phase elapsedMs=${SystemClock.elapsedRealtime() - started}")
        }
    }
}
