package com.kaanelloed.iconeration.extension

import android.content.res.AssetManager
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

fun AssetManager.toByteArray(fileName: String): ByteArray {
    val input = this.open(fileName)
    val output = ByteArrayOutputStream()
    input.copyTo(output)
    val bytes = output.toByteArray()

    output.close()
    input.close()

    return bytes
}

fun AssetManager.toString(fileName: String): String {
    return toByteArray(fileName).toString(Charset.defaultCharset())
}