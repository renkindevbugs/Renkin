package com.kaanelloed.iconeration

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.reandroid.archive.ZipAlign
import com.reandroid.apk.ApkJsonDecoder
import com.reandroid.apk.ApkJsonEncoder
import com.reandroid.apk.ApkModule
import com.reandroid.json.JSONObject
import java.io.File


class IconPackGenerator(private val ctx: Context, private val apps: Array<PackageInfoStruct>) {
    private val apkDir = ctx.cacheDir.resolve("apk")
    private val extractedDir = apkDir.resolve("apkExtracted")
    private val baseDir = extractedDir.resolve("base")
    private val rootDir = baseDir.resolve("root")
    private val assetsDir = rootDir.resolve("assets")
    private val resourcesDir = rootDir.resolve("res")
    private val unsignedApk = apkDir.resolve("app-release-unsigned.apk")
    private val signedApk = apkDir.resolve("app-release.apk")
    private val packFile = ctx.cacheDir.resolve("iconpack.apk")
    private val keyStoreFile = ctx.cacheDir.resolve("iconeration.keystore")

    fun create(textMethod: (text: String) -> Unit) {
        val assets = AssetHandler(ctx)
        clearCache()

        textMethod("Extracting apk ...")
        extractedDir.mkdirs()
        assets.assetToFile(packFile.name, packFile, false)
        decodeApk(packFile, extractedDir)

        textMethod("Writing icons ...")
        writeIcons()
        textMethod("Writing drawable.xml ...")
        writeDrawable()
        textMethod("Writing appfilter.xml ...")
        writeAppFilter()

        textMethod("Building apk ...")
        updateARSC()
        buildApk(unsignedApk)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            textMethod("Signing apk ...")
            signApk(unsignedApk, signedApk)
            textMethod("Installing apk ...")
            installApk(signedApk)

            textMethod("Done")
        } else {
            textMethod("Apk cannot be signed, you must be at least in the Android Oreo version ...")
            textMethod("Apk cannot be installed")
        }
    }

    private fun writeIcons() {
        for (app in apps) {
            val file = resourcesDir.resolve(app.packageName.replace('.', '_') + ".png")

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

    private fun updateARSC() {
        //TODO: make it better
        val arscFile = baseDir.resolve("resources.arsc.json")
        val json = JSONObject(arscFile.readText())

        val pack = json.getJSONArray("packages")[0] as JSONObject
        val spec = pack.getJSONArray("specs")[0] as JSONObject
        val type = spec.getJSONArray("types")[0] as JSONObject
        val entries = type.getJSONArray("entries")

        var id = 1

        for (app in apps) {
            val appName = app.packageName.replace('.', '_')
            val entryObj = JSONObject()
            entryObj.put("entry_name", appName)

            val valueObj = JSONObject()
            valueObj.put("value_type", "STRING")
            valueObj.put("data", "res/${appName}.png")

            entryObj.put("value", valueObj)
            entryObj.put("id", id++)

            entries.put(entryObj)
        }

        arscFile.writeText(json.toString())
    }

    private fun decodeApk(src: File, dest: File) {
        val apkModule = ApkModule.loadApkFile(src)
        val decoder = ApkJsonDecoder(apkModule)
        decoder.sanitizeFilePaths()
        decoder.writeToDirectory(dest)
    }

    private fun buildApk(dest: File) {
        val encoder = ApkJsonEncoder()
        val loadedModule: ApkModule = encoder.scanDirectory(baseDir)

        loadedModule.apkArchive.sortApkFiles()
        loadedModule.writeApk(dest)

        ZipAlign.align4(dest)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun signApk(file: File, outFile: File) {
        AssetHandler(ctx).assetToFile(keyStoreFile.name, keyStoreFile, false)
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

    private fun clearCache() {
        apkDir.deleteRecursively()
        apkDir.mkdir()
    }
}