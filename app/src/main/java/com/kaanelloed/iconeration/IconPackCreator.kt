package com.kaanelloed.iconeration

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class IconPackCreator(private val ctx: Context, private val pm: PackageInstaller, private val apps: Array<PackageInfoStruct>) {
    private val cacheDir: String = ctx.cacheDir.absolutePath
    private val extractedDir = File(cacheDir).resolve("apkExtracted")
    private val assetDir = extractedDir.resolve("assets")
    private val assetFile = "app-release.apk"

    fun create() {
        clearCache()

        val apk = assetToFile(assetFile, cacheDir, assetFile)
        val zip = ZipFile(apk)

        extractApk(zip, extractedDir.absolutePath)
        apk.delete() //Delete after extracting

        writeIcons()
        writeDrawable()
        writeAppFilter()

        buildApk(apk)

        val apkSigned = File(cacheDir).resolve("app-release.apk")

        signApk(apk, apkSigned)
        installApk(apkSigned)
    }

    private fun extractApk(zip: ZipFile, dest: String) {
        for (entry in zip.entries()) {
            val file = File(dest, entry.name)

            if (entry.isDirectory) {
                file.mkdirs()
            } else {
                file.parentFile?.mkdirs()

                copyAndCloseStream(zip.getInputStream(entry), file.outputStream())
            }
        }
    }

    private fun writeIcons() {
        for (app in apps) {
            val icon = app.genIcon

            val file = assetDir.resolve(app.packageName + ".png")

            val outStream = file.outputStream()
            icon.compress(Bitmap.CompressFormat.PNG, 100, outStream)
            outStream.close()
        }
    }

    private fun writeDrawable() {
        val file = assetDir.resolve("drawable.xml")

        file.appendText("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        file.appendText("<resources>")
        file.appendText("    <version>1</version>")
        file.appendText("    <category title=\"All Apps\" />")

        for (app in apps) {
            file.appendText("    <item drawable=\"${app.packageName}\" />")
        }

        file.appendText("</resources>")
    }

    private fun writeAppFilter() {
        //TODO
    }

    private fun buildApk(dest: File) {
        val outStream = ZipOutputStream(BufferedOutputStream(dest.outputStream()))

        for (file in extractedDir.walkTopDown()) {
            var zipName = file.absolutePath.removePrefix(extractedDir.absolutePath).removePrefix("/")
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

    private fun signApk(file: File, outFile: File) {
        //TODO
    }

    private fun installApk(file: File) {
        val intent: Intent

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            intent.data = FileProvider.getUriForFile(
                ctx,
                "${BuildConfig.APPLICATION_ID}.fileProvider",
                file
            )
            intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        } else {
            intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        intent.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, "com.android.vending")
        ctx.startActivity(intent)
        //TODO: use PackageInstaller instead
    }

    private fun assetToFile(assetName: String, fileDir: String, fileName: String): File {
        val inStream = ctx.assets.open(assetName)
        val tmpFile = File(fileDir).resolve(fileName)
        val outStream = tmpFile.outputStream()

        copyAndCloseStream(inStream, outStream)

        return tmpFile
    }

    private fun copyAndCloseStream(input: InputStream, output: OutputStream) {
        input.copyTo(output)
        input.close()
        output.close()
    }

    private fun clearCache() {
        ctx.cacheDir.deleteRecursively()
        ctx.cacheDir.mkdir()
    }
}