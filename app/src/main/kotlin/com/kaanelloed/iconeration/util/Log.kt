package com.kaanelloed.iconeration.util

import java.time.Duration
import java.time.Instant

typealias androidLog = android.util.Log

open class Log {
    companion object {
        fun debug(tag: String, message: String) {
            androidLog.d(tag, message)
        }

        fun duration(code: () -> Unit) {
            val perf = startDuration()
            code()
            perf.stop()
        }

        private fun startDuration(): DurationLog {
            return DurationLog().also { it.start() }
        }
    }
}

class DurationLog: Log() {
    private var startTime: Instant? = null
    private var stopTime: Instant? = null

    fun start() {
        startTime = Instant.now()
        debug("Duration", "Started at $startTime")
    }

    fun stop() {
        if (startTime == null)
            return

        stopTime = Instant.now()
        val duration = Duration.between(startTime, stopTime)
        debug("Duration", "Stopped at $stopTime ($duration)")
    }
}