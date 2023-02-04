package com.kaanelloed.iconeration

import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ZipHandler {
    fun zip(directory: File, dest: File) {
        val outStream = ZipOutputStream(BufferedOutputStream(dest.outputStream()))

        for (file in directory.walkTopDown()) {
            var zipName = file.absolutePath.removePrefix(directory.absolutePath).removePrefix("/")
            if (file.isDirectory) zipName += "/"

            val entry = ZipEntry(zipName)
            outStream.putNextEntry(entry)

            if (file.isFile) {
                val inStream = file.inputStream()
                inStream.copyTo(outStream)
                inStream.close()
            }
        }

        outStream.close()
    }

    fun zip(directories: Array<File>, dest: File, notCompress: Array<String>) {
        val outStream = ZipOutputStream(BufferedOutputStream(dest.outputStream()))

        for (directory in directories) {
            zip(outStream, directory, notCompress)
        }
        outStream.close()
    }

    fun zip(directory: File, dest: File, notCompress: Array<String>) {
        val outStream = ZipOutputStream(BufferedOutputStream(dest.outputStream()))

        zip(outStream, directory, notCompress)
        outStream.close()
    }

    fun unzip(zipFile: File, dest: File) {
        val zip = ZipFile(zipFile)

        for (entry in zip.entries()) {
            val file = File(dest, entry.name)

            if (entry.isDirectory) {
                file.mkdirs()
            } else {
                file.parentFile?.mkdirs()

                copyAndCloseStream(zip.getInputStream(entry), file)
            }
        }
    }

    fun unzip(zipFile: File, fileName: String, dest: File) {
        val zip = ZipFile(zipFile)

        val entry = zip.getEntry(fileName)
        copyAndCloseStream(zip.getInputStream(entry), dest)
    }

    private fun zip(outStream: ZipOutputStream, directory: File, notCompress: Array<String>) {
        for (file in directory.walkTopDown()) {
            var zipName = file.absolutePath.removePrefix(directory.absolutePath).removePrefix("/")
            if (file.isDirectory) zipName += "/"

            val entry = ZipEntry(zipName)

            if (file.isFile) {
                if (notCompress.contains(file.name) || notCompress.contains(file.extension)) {
                    entry.method = ZipEntry.STORED
                    entry.size = file.length()
                    entry.crc = crcFromFile(file).value
                } else {
                    entry.method = ZipEntry.DEFLATED
                }

                outStream.putNextEntry(entry)
                val inStream = file.inputStream()
                inStream.copyTo(outStream)
                inStream.close()
            }
        }
    }

    private fun crcFromFile(file: File): CRC32 {
        val crc = CRC32()
        crc.update(file.readBytes())
        return crc
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