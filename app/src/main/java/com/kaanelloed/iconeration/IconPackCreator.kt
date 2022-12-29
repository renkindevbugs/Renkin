package com.kaanelloed.iconeration

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import brut.androlib.Androlib
import brut.androlib.ApkDecoder
import brut.androlib.options.BuildOptions
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipFile

class IconPackCreator(private val ctx: Context, private val apps: Array<PackageInfoStruct>) {
    private val cacheDirPath = ctx.cacheDir.absolutePath
    private val apkDir = ctx.cacheDir.resolve("apk")
    private val extractedDir = apkDir.resolve("apkExtracted")
    private val assetDir = extractedDir.resolve("assets")
    private val drawableDir = extractedDir.resolve("res").resolve("drawable")
    private val apkFile = "app-release-unsigned.apk"
    private val zipFile = "app-release-unsigned.zip"
    private val frameworkFile = "1.apk"

    fun create(textMethod: (text: String) -> Unit) {
        clearCache()

        textMethod("Extracting apk ...")
        /*val apk = assetToFile(apkFile, apkDir.absolutePath, apkFile)
        extractApk(apk)
        apk.delete()*/

        val apk = apkDir.resolve(apkFile)
        val zip = assetToFile(zipFile, apkDir.resolve(zipFile))
        assetToFile(frameworkFile, ctx.cacheDir.resolve(frameworkFile))
        unzipApk(zip)

        textMethod("Writing icons ...")
        writeIcons()
        textMethod("Writing drawable.xml ...")
        writeDrawable()
        textMethod("Writing appfilter.xml ...")
        writeAppFilter()

        textMethod("Building apk ...")
        buildApk(apk)

        val apkSigned = apkDir.resolve("app-release.apk")

        textMethod("Signing apk ...")
        signApk(apk, apkSigned)
        textMethod("Installing apk ...")
        installApk(apkSigned)

        clearTmpFiles()
        textMethod("Done")
    }

    private fun extractApk(apk: File) {
        val opt = BuildOptions()
        opt.frameworkFolderLocation = cacheDirPath
        val lib = Androlib(opt)

        val decoder = ApkDecoder(apk, lib)
        decoder.setOutDir(extractedDir)
        decoder.decode()
    }

    private fun unzipApk(apk: File) {
        val zip = ZipFile(apk)

        for (entry in zip.entries()) {
            val file = File(extractedDir, entry.name)

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
        val file = assetDir.resolve("drawable.xml")
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
        val file = assetDir.resolve("appfilter.xml")
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
        val opt = BuildOptions()
        opt.frameworkFolderLocation = cacheDirPath
        opt.aaptPath = File(ctx.applicationInfo.nativeLibraryDir).resolve("libaapt2.so").absolutePath
        opt.aaptVersion = 2
        opt.useAapt2 = true
        Androlib(opt).build(extractedDir, dest)
    }

    private fun signApk(file: File, outFile: File) {
        val keyStoreFile = ctx.cacheDir.resolve("iconeration.keystore")
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
            intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive");
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

    private fun clearTmpFiles() {
        val tmpFile = ctx.cacheDir.listFiles()!!

        for (file in tmpFile) {
            if (!file.isDirectory && file.path.endsWith(".tmp")) {
                file.delete()
            }
        }
    }
}