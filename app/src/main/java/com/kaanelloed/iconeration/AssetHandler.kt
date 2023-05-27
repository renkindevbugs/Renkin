package com.kaanelloed.iconeration

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class AssetHandler(private val ctx: Context) {
    fun assetToFile(file: File, overwrite: Boolean = true): File {
        return assetToFile(file.name, file, overwrite)
    }

    fun assetToFile(assetName: String, file: File, overwrite: Boolean = true): File {
        if (file.exists() && !overwrite)
            return file

        return assetToFile(assetName, file)
    }

    private fun assetToFile(assetName: String, file: File): File {
        val inStream = ctx.assets.open(assetName)
        copyAndCloseStream(inStream, file)

        return file
    }

    private fun copyAndCloseStream(input: InputStream, file: File) {
        copyAndCloseStream(input, file.outputStream())
    }

    private fun copyAndCloseStream(input: InputStream, output: OutputStream) {
        input.copyTo(output)
        input.close()
        output.close()
    }
}