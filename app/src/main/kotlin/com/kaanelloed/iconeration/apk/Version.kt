package com.kaanelloed.iconeration.apk

import androidx.core.text.isDigitsOnly

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
            val elements = versionName.split('.')

            if (elements.isEmpty())
                return -1

            if (!elements[0].isDigitsOnly())
                return -1

            return elements[0].toInt()
        }

        private fun createVersionName(versionCode: Long, internalVersionCode: Int): String {
            return "$internalVersionCode.$versionCode.0"
        }
    }
}