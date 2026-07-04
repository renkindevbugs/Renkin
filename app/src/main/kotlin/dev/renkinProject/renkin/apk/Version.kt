package dev.renkinProject.renkin.apk

class Version {
    val versionCode: Long
    val versionName: String
    val internalVersionCode: Int

    constructor(versionCode: Long, versionName: String) {
        this@Version.versionCode = versionCode
        this@Version.versionName = versionName
        this@Version.internalVersionCode = parseInternalVersionCode(versionName)
    }

    constructor(versionCode: Long, internalVersionCode: Int) {
        this@Version.versionCode = versionCode
        this@Version.versionName = createVersionName(versionCode, internalVersionCode)
        this@Version.internalVersionCode = internalVersionCode
    }

    companion object {
        private fun parseInternalVersionCode(versionName: String): Int {
            // toIntOrNull guards empty / non-numeric names (e.g. a malformed installed pack),
            // which would otherwise crash the build with NumberFormatException.
            return versionName.split('.').firstOrNull()?.toIntOrNull() ?: -1
        }

        private fun createVersionName(versionCode: Long, internalVersionCode: Int): String {
            return "$internalVersionCode.$versionCode.0"
        }
    }
}