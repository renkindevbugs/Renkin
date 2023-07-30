package com.kaanelloed.iconeration

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.content.FileProvider
import app.revanced.manager.compose.util.signing.Signer
import app.revanced.manager.compose.util.signing.SigningOptions
import com.kaanelloed.iconeration.xml.AdaptiveIconXml
import com.kaanelloed.iconeration.xml.AppFilterXml
import com.kaanelloed.iconeration.xml.DrawableXml
import com.kaanelloed.iconeration.xml.XMLEncoder
import com.kaanelloed.iconeration.xml.XmlMemoryFile
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.coder.ValueCoder
import java.io.ByteArrayOutputStream
import java.io.File

class IconPackGenerator(private val ctx: Context, private val apps: Array<PackageInfoStruct>) {
    private val apkDir = ctx.cacheDir.resolve("apk")
    private val unsignedApk = apkDir.resolve("app-release-unsigned.apk")
    private val signedApk = apkDir.resolve("app-release.apk")
    private val keyStoreFile = ctx.dataDir.resolve("iconeration.keystore")

    private val iconPackName = "com.kaanelloed.iconerationiconpack"
    private val newVersionCode = 1

    fun create(textMethod: (text: String) -> Unit) {
        val themed = PreferencesHelper(ctx).getExportThemed()

        val currentVersionCode = getCurrentVersionCode()
        if (currentVersionCode != 0L) {
            if (!keyStoreFile.exists() || newVersionCode > currentVersionCode) {
                textMethod("Old and new icon pack are not compatible")
                textMethod("Please uninstall it and try again ...")
                return
            }
        }

        val apkModule = ApkModule()
        val tableBlock = TableBlock()
        val manifest = AndroidManifestBlock()

        apkModule.tableBlock = tableBlock
        apkModule.setManifest(manifest)

        textMethod("Initializing framework ...")
        val framework = apkModule.initializeAndroidFramework(33)
        val packageBlock = tableBlock.newPackage(0x7f, iconPackName)

        textMethod("Generating manifest ...")
        manifest.packageName = iconPackName
        manifest.versionCode = newVersionCode
        manifest.versionName = "0.1.0"
        manifest.compileSdkVersion = framework.versionCode
        manifest.compileSdkVersionCodename = framework.versionName
        manifest.platformBuildVersionCode = framework.versionCode
        manifest.platformBuildVersionName = framework.versionName
        manifest.setApplicationLabel("Iconeration Icon Pack")

        createMainActivity(manifest)

        createColorResource(packageBlock, "icon_color", "#FFFFFFFF")
        createColorResource(packageBlock, "icon_background_color", "#FF000000")

        createRefColor31Resource(packageBlock, "icon_color", "@android:color/system_accent1_100")
        createRefColor31Resource(packageBlock, "icon_background_color", "@android:color/system_accent1_800")

        textMethod("Writing icons, drawable.xml and appfilter.xml ...")
        val drawableXml = DrawableXml()
        val appfilterXml = AppFilterXml()

        for (app in apps) {
            val appFileName = app.getFileName()

            if (themed) {
                val adaptive = AdaptiveIconXml()
                adaptive.foreground(appFileName)
                adaptive.background("@color/icon_background_color")

                if (app.exportType == PackageInfoStruct.ExportType.XML)
                    createXmlDrawableResource(apkModule, packageBlock, app.vector.toXMLFile(), appFileName + "_foreground")
                else
                    createPngResource(apkModule, packageBlock, app.genIcon, appFileName + "_foreground")

                createXmlDrawableResource(apkModule, packageBlock, adaptive, appFileName)
            }
            else
                createPngResource(apkModule, packageBlock, app.genIcon, appFileName)

            drawableXml.item(appFileName)
            appfilterXml.item(app.packageName, app.activityName, appFileName)
        }

        apkModule.add(ByteInputSource(drawableXml.getBytes(), "assets/drawable.xml"))
        apkModule.add(ByteInputSource(appfilterXml.getBytes(), "assets/appfilter.xml"))

        createXmlResource(apkModule, packageBlock, drawableXml, "drawable")
        createXmlResource(apkModule, packageBlock, appfilterXml, "appfilter")

        textMethod("Building apk ...")
        apkModule.add(ByteInputSource(ByteArray(0), "classes.dex"))
        apkModule.uncompressedFiles.addCommonExtensions()
        apkModule.writeApk(unsignedApk)

        textMethod("Signing apk ...")
        signApk(unsignedApk, signedApk)
        textMethod("Installing apk ...")
        installApk(signedApk)

        textMethod("Done")
    }

    private fun createMainActivity(manifest: AndroidManifestBlock) {
        val application = manifest.orCreateApplicationElement
        val activity = application.createChildElement(AndroidManifestBlock.TAG_activity)

        createIntentFilter(activity, arrayOf(AndroidManifestBlock.VALUE_android_intent_action_MAIN), emptyArray())
        createIntentFilter(activity, arrayOf("org.adw.launcher.THEMES"), arrayOf("android.intent.category.DEFAULT")) //ADW Launcher
        createIntentFilter(activity, arrayOf("org.adw.launcher.icons.ACTION_PICK_ICON"), arrayOf("android.intent.category.DEFAULT")) //ADW Launcher Custom Icon Picker
        createIntentFilter(activity, arrayOf(AndroidManifestBlock.VALUE_android_intent_action_MAIN), arrayOf("com.anddoes.launcher.THEME")) //Apex Launcher
        createIntentFilter(activity, arrayOf(AndroidManifestBlock.VALUE_android_intent_action_MAIN, "com.gau.go.launcherex.theme"), arrayOf("android.intent.category.DEFAULT")) //GO Launcher
        createIntentFilter(activity, arrayOf("com.dlto.atom.launcher.THEME"), emptyArray()) //Atom Launcher
        createIntentFilter(activity, arrayOf("com.phonemetra.turbo.launcher.icons.ACTION_PICK_ICON"), arrayOf("android.intent.category.DEFAULT")) //Turbo Launcher Custom Icon Picker
        createIntentFilter(activity, arrayOf("com.gridappsinc.launcher.theme.apk_action"), arrayOf("android.intent.category.DEFAULT")) //Nine Launcher
        createIntentFilter(activity, arrayOf("ch.deletescape.lawnchair.ICONPACK"), arrayOf("ch.deletescape.lawnchair.PICK_ICON")) //Lawnchair
        createIntentFilter(activity, arrayOf("com.novalauncher.THEME"), arrayOf("com.novalauncher.category.CUSTOM_ICON_PICKER")) //Nova Launcher Custom Icon Picker
        createIntentFilter(activity, arrayOf(AndroidManifestBlock.VALUE_android_intent_action_MAIN, "home.solo.launcher.free.THEMES", "home.solo.launcher.free.ACTION_ICON"), emptyArray()) //Solo Launcher
        createIntentFilter(activity, arrayOf(AndroidManifestBlock.VALUE_android_intent_action_MAIN, "com.lge.launcher2.THEME"), arrayOf("android.intent.category.DEFAULT")) //LG Home
        createIntentFilter(activity, arrayOf("net.oneplus.launcher.icons.ACTION_PICK_ICON"), arrayOf("android.intent.category.DEFAULT")) //OnePlus Launcher
        createIntentFilter(activity, arrayOf("com.tsf.shell.themes"), arrayOf("android.intent.category.DEFAULT")) //TSF Shell
        createIntentFilter(activity, arrayOf("ginlemon.smartlauncher.THEMES"), arrayOf("android.intent.category.DEFAULT")) //Smart Launcher
        createIntentFilter(activity, arrayOf("com.sonymobile.home.ICON_PACK"), arrayOf("android.intent.category.DEFAULT")) //Sony Launcher
        createIntentFilter(activity, arrayOf(AndroidManifestBlock.VALUE_android_intent_action_MAIN, "com.gau.go.launcherex.theme", "com.zeroteam.zerolauncher.theme"), emptyArray()) //GO Launcher & Zero Launcher
        createIntentFilter(activity, arrayOf("jp.co.a_tm.android.launcher.icons.ACTION_PICK_ICON"), arrayOf("android.intent.category.DEFAULT")) //+HOME Icon Picker
        createIntentFilter(activity, arrayOf(AndroidManifestBlock.VALUE_android_intent_action_MAIN, "com.vivid.launcher.theme"), arrayOf("android.intent.category.DEFAULT")) //V Launcher

        val attribute = activity.getOrCreateAndroidAttribute(
            AndroidManifestBlock.NAME_name,
            AndroidManifestBlock.ID_name
        )
        attribute.valueAsString = "com.kaanelloed.iconerationiconpack.MainActivity"
    }

    private fun createIntentFilter(activity: ResXmlElement, actions: Array<String>, categories: Array<String>) {
        val intentFilter = activity.createChildElement(AndroidManifestBlock.TAG_intent_filter)

        for (actionValue in actions) {
            val action = intentFilter.createChildElement(AndroidManifestBlock.TAG_action)
            val attribute = action.getOrCreateAndroidAttribute(
                AndroidManifestBlock.NAME_name,
                AndroidManifestBlock.ID_name
            )
            attribute.valueAsString = actionValue
        }

        for (categoryValue in categories) {
            val category = intentFilter.createChildElement(AndroidManifestBlock.TAG_category)
            val attribute = category.getOrCreateAndroidAttribute(
                AndroidManifestBlock.NAME_name,
                AndroidManifestBlock.ID_name
            )
            attribute.valueAsString = categoryValue
        }
    }

    private fun createXmlResource(apkModule: ApkModule, packageBlock: PackageBlock, xmlFile: XmlMemoryFile, name: String) {
        val resPath = "res/${name}.xml"
        val xmlEncoder = XMLEncoder(packageBlock)

        val res = packageBlock.getOrCreate("", "xml", name)
        res.setValueAsString(resPath)

        apkModule.add(xmlEncoder.encodeToSource(xmlFile, resPath))
    }

    private fun createXmlDrawableResource(apkModule: ApkModule, packageBlock: PackageBlock, xmlFile: XmlMemoryFile, name: String) {
        val resPath = "res/${name}.xml"
        val xmlEncoder = XMLEncoder(packageBlock)

        val res = packageBlock.getOrCreate("", "drawable", name)
        res.setValueAsString(resPath)

        apkModule.add(xmlEncoder.encodeToSource(xmlFile, resPath))
    }

    private fun createPngResource(apkModule: ApkModule, packageBlock: PackageBlock, bitmap: Bitmap, name: String) {
        val resPath = "res/${name}.png"

        val icon = packageBlock.getOrCreate("", "drawable", name)
        icon.setValueAsString(resPath)

        apkModule.add(generatePng(bitmap, resPath))
    }

    private fun generatePng(image: Bitmap, name: String): ByteInputSource {
        val outStream = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.PNG, 100, outStream)
        val src = ByteInputSource(outStream.toByteArray(), name)
        outStream.close()

        return src
    }

    private fun createColorResource(packageBlock: PackageBlock, name: String, color: String) {
        val res = packageBlock.getOrCreate("", "color", name)
        val coder = ValueCoder.encode(color)
        res.setValueAsRaw(coder.valueType, coder.value)
    }

    private fun createRefColor31Resource(packageBlock: PackageBlock, name: String, reference: String) {
        val res = packageBlock.getOrCreate("v31", "color", name)
        val coder = ValueCoder.encodeReference(packageBlock, reference)
        res.setValueAsRaw(coder.valueType, coder.value)
    }

    @SuppressWarnings("deprecation")
    private fun getCurrentVersionCode(): Long {
        val iconPack = ApplicationManager(ctx).getPackage(iconPackName)
            ?: return 0L

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            iconPack.longVersionCode
        else
            iconPack.versionCode.toLong()
    }

    private fun signApk(file: File, outFile: File) {
        val opt = SigningOptions("Iconeration", "s3cur3p@ssw0rd", keyStoreFile.path)
        val signer = Signer(opt)
        signer.signApk(file, outFile)
    }

    private fun installApk(file: File) {
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
        intent.data = FileProvider.getUriForFile(
            ctx,
            "${BuildConfig.APPLICATION_ID}.fileProvider",
            file
        )
        intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK

        intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        intent.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, "com.android.vending")
        ctx.startActivity(intent)
        //TODO: use PackageInstaller instead
    }
}