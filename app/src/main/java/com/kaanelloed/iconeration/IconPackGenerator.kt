package com.kaanelloed.iconeration

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipFile

class IconPackGenerator(private val ctx: Context, private val apps: Array<PackageInfoStruct>) {
    private val apkDir = ctx.cacheDir.resolve("apk")
    private val extractedDir = apkDir.resolve("apkExtracted")
    private val assetsDir = extractedDir.resolve("assets")
    private val resourcesDir = extractedDir.resolve("res")
    private val drawableDir = resourcesDir.resolve("drawable")
    private val unsignedApk = apkDir.resolve("app-release-unsigned.apk")
    private val signedApk = apkDir.resolve("app-release.apk")
    private val frameworkFile = ctx.cacheDir.resolve("1.apk")
    private val keyStoreFile = ctx.cacheDir.resolve("iconeration.keystore")

    fun create(textMethod: (text: String) -> Unit) {
        clearCache()

        textMethod("Extracting apk ...")
        if (!frameworkFile.exists()) assetToFile(frameworkFile.name, frameworkFile)

        drawableDir.mkdirs()
        assetsDir.mkdirs()

        val zipFile = extractedDir.resolve("apkFiles.zip")
        assetToFile(zipFile.name, zipFile)
        unzip(zipFile, extractedDir)

        textMethod("Writing icons ...")
        writeIcons()
        textMethod("Writing drawable.xml ...")
        writeDrawable()
        textMethod("Writing appfilter.xml ...")
        writeAppFilter()

        textMethod("Building apk ...")
        buildApk(unsignedApk)

        textMethod("Signing apk ...")
        signApk(unsignedApk, signedApk)
        textMethod("Installing apk ...")
        installApk(signedApk)

        textMethod("Done")
    }

    private fun unzip(zipFile: File, dest: File) {
        val zip = ZipFile(zipFile)

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
            val file = drawableDir.resolve(app.packageName.replace('.', '_') + ".png")

            val outStream = file.outputStream()
            app.genIcon.compress(Bitmap.CompressFormat.PNG, 100, outStream)
            outStream.close()
        }
    }

    private fun writeDrawable() {
        val file = assetsDir.resolve("drawable.xml")
        if (file.exists()) file.delete()
        val fileContent = mutableListOf<String>()

        fileContent.add("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        fileContent.add("<resources>")
        fileContent.add("    <version>1</version>")
        fileContent.add("    <category title=\"All Apps\" />")

        for (app in apps) {
            fileContent.add("    <item drawable=\"${app.packageName.replace('.', '_')}\" />")
        }

        fileContent.add("</resources>")
        file.appendText(fileContent.joinToString("\n"))
    }

    private fun writeAppFilter() {
        val file = assetsDir.resolve("appfilter.xml")
        if (file.exists()) file.delete()
        val fileContent = mutableListOf<String>()

        fileContent.add("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        fileContent.add("<resources>")
        for (app in apps) {
            fileContent.add("    <item component=\"ComponentInfo{${app.packageName}/${app.activityName}}\" drawable=\"${app.packageName.replace('.', '_')}\" />")
        }

        fileContent.add("</resources>")
        file.appendText(fileContent.joinToString("\n"))
    }

    private fun buildApk(dest: File) {
        val opts = ResourcesBuilder.BuildOptions("127", "21", "28", "1", "0.1.0")
        val builder = ResourcesBuilder(ctx, frameworkFile)

        builder.buildApk(opts, extractedDir.resolve("AndroidManifest.xml"), resourcesDir, assetsDir, extractedDir.resolve("classes.dex"), arrayOf("resources.arsc", "png"), dest)
    }

    private fun signApk(file: File, outFile: File) {
        if (!keyStoreFile.exists()) assetToFile(keyStoreFile.name, keyStoreFile)
        Signer("Iconeration", "s3cur3p@ssw0rd").signApk(file, outFile, keyStoreFile)
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
            intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        intent.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, "com.android.vending")
        ctx.startActivity(intent)
        //TODO: use PackageInstaller instead
    }

    private fun assetToFile(assetName: String, file: File): File {
        val inStream = ctx.assets.open(assetName)
        val outStream = file.outputStream()

        copyAndCloseStream(inStream, outStream)

        return file
    }

    private fun copyAndCloseStream(input: InputStream, output: OutputStream) {
        input.copyTo(output)
        input.close()
        output.close()
    }

    private fun clearCache() {
        apkDir.deleteRecursively()
        apkDir.mkdir()
    }
}