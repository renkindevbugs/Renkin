package dev.renkinProject.renkin.util

import android.os.StrictMode

/**
 * Debug builds log, never crash on, disk or network access from the main thread and leaked
 * closeables (logcat tag `StrictMode`), so freezes such as a stalled export show where they block.
 */
internal fun enableDebugStrictMode() {
    StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .detectCustomSlowCalls()
            .penaltyLog()
            .build()
    )
    StrictMode.setVmPolicy(
        StrictMode.VmPolicy.Builder()
            .detectLeakedClosableObjects()
            .detectLeakedSqlLiteObjects()
            .penaltyLog()
            .build()
    )
}
