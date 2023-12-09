package com.kaanelloed.iconeration.apk

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import app.revanced.manager.compose.util.signing.Signer
import app.revanced.manager.compose.util.signing.SigningOptions
import com.kaanelloed.iconeration.packages.ApplicationManager
import com.kaanelloed.iconeration.packages.PackageInfoStruct
import com.kaanelloed.iconeration.R
import com.kaanelloed.iconeration.icon.EmptyIcon
import com.kaanelloed.iconeration.icon.VectorIcon
import com.kaanelloed.iconeration.vector.VectorExporter.Companion.toXmlFile
import com.kaanelloed.iconeration.xml.file.AdaptiveIconXml
import com.kaanelloed.iconeration.xml.file.AppFilterXml
import com.kaanelloed.iconeration.xml.file.DrawableXml
import com.kaanelloed.iconeration.xml.XmlEncoder
import com.kaanelloed.iconeration.xml.file.XmlMemoryFile
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.coder.ValueCoder
import com.reandroid.arsc.value.ValueType
import java.io.ByteArrayOutputStream
import java.io.File

class IconPackBuilder(private val ctx: Context, private val apps: List<PackageInfoStruct>) {
    private val apkDir = ctx.cacheDir.resolve("apk")
    private val unsignedApk = apkDir.resolve("app-release-unsigned.apk")
    private val signedApk = apkDir.resolve("app-release.apk")
    private val keyStoreFile = ctx.dataDir.resolve("iconeration.keystore")

    private val iconPackName = "com.kaanelloed.iconerationiconpack"
    private val newVersionCode = 1
    private val newVersionName = "0.1.0"
    private val frameworkVersion = 33
    private val minSdkVersion = 26

    fun canBeInstalled(): Boolean {
        val currentVersionCode = getCurrentVersionCode()
        if (currentVersionCode != 0L) {
            if (!keyStoreFile.exists() || newVersionCode > currentVersionCode) {
                return false
            }
        }

        return true
    }

    fun getIconPackName(): String {
        return iconPackName
    }

    fun buildAndSign(themed: Boolean, iconColor: String, backgroundColor: String, textMethod: (text: String) -> Unit): Uri {
        val apkModule = ApkModule()
        val tableBlock = TableBlock()
        val manifest = AndroidManifestBlock()

        apkModule.tableBlock = tableBlock
        apkModule.setManifest(manifest)

        textMethod(ctx.resources.getString(R.string.initializeFramework))
        val framework = apkModule.initializeAndroidFramework(frameworkVersion)
        val packageBlock = tableBlock.newPackage(0x7f, iconPackName)

        textMethod(ctx.resources.getString(R.string.generateManifest))
        manifest.packageName = iconPackName
        manifest.versionCode = newVersionCode
        manifest.versionName = newVersionName
        manifest.compileSdkVersion = framework.versionCode
        manifest.compileSdkVersionCodename = framework.versionName
        manifest.platformBuildVersionCode = framework.versionCode
        manifest.platformBuildVersionName = framework.versionName

        setSdkVersions(manifest.manifestElement, minSdkVersion, framework.versionCode)
        manifest.setApplicationLabel("Iconeration Icon Pack")

        createMainActivity(manifest)

        createColorResource(packageBlock, "icon_color", iconColor)
        createColorResource(packageBlock, "icon_background_color", backgroundColor)

        createRefColor31Resource(packageBlock, "icon_color", "@android:color/system_accent1_100")
        createRefColor31Resource(packageBlock, "icon_background_color", "@android:color/system_accent1_800")

        textMethod(ctx.resources.getString(R.string.writingElement))
        val drawableXml = DrawableXml()
        val appfilterXml = AppFilterXml()

        for (app in apps) {
            if (app.createdIcon !is EmptyIcon) {
                val appFileName = app.getFileName()

                if (themed) {
                    val adaptive = AdaptiveIconXml()
                    adaptive.foreground(appFileName)
                    adaptive.background("@color/icon_background_color")

                    if (app.createdIcon is VectorIcon)
                        createXmlDrawableResource(apkModule, packageBlock, app.createdIcon.vector.toXmlFile(), appFileName + "_foreground")
                    else
                        createPngResource(apkModule, packageBlock, app.createdIcon.toBitmap(), appFileName + "_foreground")

                    createXmlDrawableResource(apkModule, packageBlock, adaptive, appFileName)
                }
                else
                    createPngResource(apkModule, packageBlock, app.createdIcon.toBitmap(), appFileName)

                drawableXml.item(appFileName)
                appfilterXml.item(app.packageName, app.activityName, appFileName)
            }
        }

        apkModule.add(ByteInputSource(drawableXml.getBytes(), "assets/drawable.xml"))
        apkModule.add(ByteInputSource(appfilterXml.getBytes(), "assets/appfilter.xml"))

        createXmlResource(apkModule, packageBlock, drawableXml, "drawable")
        createXmlResource(apkModule, packageBlock, appfilterXml, "appfilter")

        textMethod(ctx.resources.getString(R.string.buildingApk))
        apkModule.add(ByteInputSource(ByteArray(0), "classes.dex"))
        apkModule.uncompressedFiles.addCommonExtensions()
        apkModule.writeApk(unsignedApk)

        textMethod(ctx.resources.getString(R.string.signApk))
        signApk(unsignedApk, signedApk)

        textMethod(ctx.resources.getString(R.string.done))

        return signedApk.toUri()
    }

    private fun setSdkVersions(manifest: ResXmlElement, minSdkVersion: Int, targetSdkVersion: Int) {
        val useSdk = manifest.createChildElement(AndroidManifestBlock.TAG_uses_sdk)

        val minSdk = useSdk.getOrCreateAndroidAttribute(
            AndroidManifestBlock.NAME_minSdkVersion,
            AndroidManifestBlock.ID_minSdkVersion
        )
        minSdk.setTypeAndData(ValueType.DEC, minSdkVersion)

        val targetSdk = useSdk.getOrCreateAndroidAttribute(
            AndroidManifestBlock.NAME_targetSdkVersion,
            AndroidManifestBlock.ID_targetSdkVersion
        )
        targetSdk.setTypeAndData(ValueType.DEC, targetSdkVersion)
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

        val activityName = activity.getOrCreateAndroidAttribute(
            AndroidManifestBlock.NAME_name,
            AndroidManifestBlock.ID_name
        )
        activityName.valueAsString = "com.kaanelloed.iconerationiconpack.MainActivity"

        val exported = activity.getOrCreateAndroidAttribute(
            NAME_exported,
            ID_exported
        )
        exported.valueAsBoolean = true
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
        val xmlEncoder = XmlEncoder(packageBlock)

        val res = packageBlock.getOrCreate("", "xml", name)
        res.setValueAsString(resPath)

        apkModule.add(xmlEncoder.encodeToSource(xmlFile, resPath))
    }

    private fun createXmlDrawableResource(apkModule: ApkModule, packageBlock: PackageBlock, xmlFile: XmlMemoryFile, name: String) {
        val resPath = "res/${name}.xml"
        val xmlEncoder = XmlEncoder(packageBlock)

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
        val appMan = ApplicationManager(ctx)

        val iconPack = appMan.getPackage(iconPackName)
            ?: return 0L

        return appMan.getVersionCode(iconPack)
    }

    private fun signApk(file: File, outFile: File) {
        val opt = SigningOptions("Iconeration", "s3cur3p@ssw0rd", keyStoreFile.path)
        val signer = Signer(opt)
        signer.signApk(file, outFile)
    }

    private val NAME_exported = "exported"
    private val ID_exported = 0x01010010
}