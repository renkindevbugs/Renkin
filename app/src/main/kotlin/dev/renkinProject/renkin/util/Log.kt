package dev.renkinProject.renkin.util

typealias androidLog = android.util.Log

class Log {
    companion object {
        fun debug(tag: String, message: String) {
            androidLog.d(tag, message)
        }

        fun info(tag: String, message: String) {
            androidLog.i(tag, message)
        }

        fun error(tag: String, message: String, throwable: Throwable? = null) {
            androidLog.e(tag, message, throwable)
        }
    }
}
