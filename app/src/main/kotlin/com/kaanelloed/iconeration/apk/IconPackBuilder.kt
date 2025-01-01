package com.kaanelloed.iconeration.apk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import app.revanced.library.ApkUtils
import com.kaanelloed.iconeration.R
import com.kaanelloed.iconeration.constants.SuppressDeprecation
import com.kaanelloed.iconeration.constants.SuppressSameParameterValue
import com.kaanelloed.iconeration.data.InstalledApplication
import com.kaanelloed.iconeration.extension.getBytes
import com.kaanelloed.iconeration.icon.EmptyIcon
import com.kaanelloed.iconeration.icon.VectorIcon
import com.kaanelloed.iconeration.packages.ApplicationManager
import com.kaanelloed.iconeration.packages.PackageInfoStruct
import com.kaanelloed.iconeration.packages.PackageVersion
import com.kaanelloed.iconeration.vector.brush.ReferenceBrush
import com.kaanelloed.iconeration.vector.VectorExporter.Companion.toXmlFile
import com.kaanelloed.iconeration.xml.XmlEncoder
import com.kaanelloed.iconeration.xml.file.AdaptiveIconXml
import com.kaanelloed.iconeration.xml.file.AppFilterXml
import com.kaanelloed.iconeration.xml.file.DrawableXml
import com.kaanelloed.iconeration.xml.file.LayoutXml
import com.kaanelloed.iconeration.xml.file.XmlMemoryFile
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.coder.ValueCoder
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ValueType
import com.reandroid.dex.model.DexFile
import com.reandroid.dex.sections.SectionType
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class IconPackBuilder(
    private val ctx: Context,
    private val apps: List<PackageInfoStruct>,
    private val calendarIcons: Map<InstalledApplication, String>,
    private val calendarIconsDrawable: Map<String, Drawable>
) {
    private val apkDir = ctx.cacheDir.resolve("apk")
    private val unsignedApk = apkDir.resolve("app-release-unsigned.apk")
    private val signedApk = apkDir.resolve("app-release.apk")
    private val keyStoreFile = ctx.filesDir.resolve("iconeration.keystore")

    private val iconPackName = "com.kaanelloed.iconerationiconpack"
    private val newInternalVersionCode = 0
    private val frameworkVersion = 34
    private val minSdkVersion = 21

    fun canBeInstalled(): Boolean {
        val installedVersion = getInstalledVersion()
        if (installedVersion != null) {
            if (!keyStoreFile.exists() || newInternalVersionCode > installedVersion.internalVersionCode) {
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

        val installedVersion = getInstalledVersion()
        val installedVersionCode = installedVersion?.versionCode ?: 0L

        val newVersion = Version(installedVersionCode + 1, newInternalVersionCode)

        textMethod(ctx.resources.getString(R.string.generateManifest))
        manifest.packageName = iconPackName
        manifest.versionCode = newVersion.versionCode.toInt()
        manifest.versionName = newVersion.versionName
        manifest.compileSdkVersion = framework.versionCode
        manifest.compileSdkVersionCodename = framework.versionName
        manifest.platformBuildVersionCode = framework.versionCode
        manifest.platformBuildVersionName = framework.versionName

        setSdkVersions(manifest.manifestElement, minSdkVersion, framework.versionCode)
        manifest.setApplicationLabel("Alchemicon Pack")

        createMainActivity(manifest)

        insertIconPackAppIcons(apkModule, packageBlock, manifest)

        createColorResource(packageBlock, "icon_color", iconColor)
        createColorResource(packageBlock, "icon_background_color", backgroundColor)

        createRefColor31Resource(packageBlock, "icon_color", "@android:color/system_accent1_100")
        createRefColor31Resource(packageBlock, "icon_background_color", "@android:color/system_accent1_800")

        textMethod(ctx.resources.getString(R.string.writingElement))
        val drawableXml = DrawableXml()
        val appfilterXml = AppFilterXml()

        val vectorBrush = ReferenceBrush("@color/icon_color")

        for (app in apps) {
            if (app.createdIcon !is EmptyIcon) {
                val appFileName = app.getFileName()

                val exportAsAdaptive = themed || app.createdIcon.exportAsAdaptiveIcon
                if (exportAsAdaptive && PackageVersion.is26OrMore()) {
                    val adaptive = AdaptiveIconXml()
                    adaptive.foreground(appFileName)
                    adaptive.background("@color/icon_background_color")

                    //TODO Better handling of foreground icon size
                    if (app.createdIcon is VectorIcon) {
                        val vector = app.createdIcon.formatVector(vectorBrush)
                        createXmlDrawableResource(apkModule, packageBlock, vector.toXmlFile(), appFileName + "_foreground")
                    }
                    else {
                        createBitmapResource(apkModule, packageBlock, app.createdIcon.toBitmap(), appFileName + "_foreground")
                    }

                    createXmlDrawableResource(apkModule, packageBlock, adaptive, appFileName)
                }
                else
                    createBitmapResource(apkModule, packageBlock, app.createdIcon.toBitmap(), appFileName)

                drawableXml.item(appFileName)
                appfilterXml.item(app.packageName, app.activityName, appFileName)
            }
        }

        for (calendarIcon in calendarIcons) {
            appfilterXml.calendar(calendarIcon.key.packageName, calendarIcon.key.activityName, calendarIcon.value)
        }

        for (drawable in calendarIconsDrawable) {
            createBitmapResource(apkModule, packageBlock, drawable.value.toBitmap(), drawable.key)
            drawableXml.item(drawable.key)
        }

        apkModule.add(ByteInputSource(drawableXml.getBytes(), "assets/drawable.xml"))
        apkModule.add(ByteInputSource(appfilterXml.getBytes(), "assets/appfilter.xml"))

        createXmlResource(apkModule, packageBlock, drawableXml, "drawable")
        createXmlResource(apkModule, packageBlock, appfilterXml, "appfilter")

        val layout = createXmlLayoutResource(apkModule, packageBlock, createLayout(), "main_activity")

        textMethod(ctx.resources.getString(R.string.buildingApk))
        buildDex(apkModule, layout.resourceId)
        apkModule.uncompressedFiles.addCommonExtensions()
        apkModule.writeApk(unsignedApk)

        textMethod(ctx.resources.getString(R.string.signApk))
        signApk(unsignedApk, signedApk)

        textMethod(ctx.resources.getString(R.string.done))

        return signedApk.toUri()
    }

    @Suppress(SuppressSameParameterValue)
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

        createIntentFilter(activity, arrayOf(AndroidManifestBlock.VALUE_android_intent_action_MAIN), arrayOf("android.intent.category.LAUNCHER"))
        createIntentFilter(activity, arrayOf("org.adw.launcher.THEMES"), arrayOf("android.intent.category.DEFAULT")) //ADW Launcher
        createIntentFilter(activity, arrayOf("org.adw.launcher.icons.ACTION_PICK_ICON"), arrayOf("android.intent.category.DEFAULT")) //ADW Launcher Custom Icon Picker
        createIntentFilter(activity, arrayOf(AndroidManifestBlock.VALUE_android_intent_action_MAIN), arrayOf("com.anddoes.launcher.THEME")) //Apex Launcher
        createIntentFilter(activity, arrayOf(AndroidManifestBlock.VALUE_android_intent_action_MAIN, "com.gau.go.launcherex.theme"), arrayOf("android.intent.category.DEFAULT")) //GO Launcher
        createIntentFilter(activity, arrayOf("com.dlto.atom.launcher.THEME"), emptyArray()) //Atom Launcher
        createIntentFilter(activity, arrayOf("com.phonemetra.turbo.launcher.icons.ACTION_PICK_ICON"), arrayOf("android.intent.category.DEFAULT")) //Turbo Launcher Custom Icon Picker
        createIntentFilter(activity, arrayOf("com.gridappsinc.launcher.theme.apk_action"), arrayOf("android.intent.category.DEFAULT")) //Nine Launcher
        createIntentFilter(activity, arrayOf("com.motorola.launcher.ACTION_ICON_PACK", "com.motorola.launcher3.ICON_PACK_CHANGED"), arrayOf("android.intent.category.DEFAULT")) //Moto Launcher
        createIntentFilter(activity, arrayOf("ch.deletescape.lawnchair.ICONPACK"), arrayOf("ch.deletescape.lawnchair.PICK_ICON")) //Lawnchair
        createIntentFilter(activity, arrayOf("com.novalauncher.THEME"), arrayOf("com.novalauncher.category.CUSTOM_ICON_PICKER")) //Nova Launcher Custom Icon Picker
        createIntentFilter(activity, arrayOf(AndroidManifestBlock.VALUE_android_intent_action_MAIN, "home.solo.launcher.free.THEMES", "home.solo.launcher.free.ACTION_ICON"), emptyArray()) //Solo Launcher
        createIntentFilter(activity, arrayOf(AndroidManifestBlock.VALUE_android_intent_action_MAIN, "com.lge.launcher2.THEME"), arrayOf("android.intent.category.DEFAULT")) //LG Home
        createIntentFilter(activity, arrayOf("net.oneplus.launcher.icons.ACTION_PICK_ICON"), arrayOf("android.intent.category.DEFAULT")) //OnePlus Launcher
        createIntentFilter(activity, arrayOf("com.spocky.projengmenu.icons.ACTION_PICK_ICON"), arrayOf("android.intent.category.DEFAULT")) //Projectivy Launcher
        createIntentFilter(activity, arrayOf("com.tsf.shell.themes"), arrayOf("android.intent.category.DEFAULT")) //TSF Shell
        createIntentFilter(activity, arrayOf("ginlemon.smartlauncher.THEMES"), arrayOf("android.intent.category.DEFAULT")) //Smart Launcher
        createIntentFilter(activity, arrayOf("com.sonymobile.home.ICON_PACK"), arrayOf("android.intent.category.DEFAULT")) //Sony Launcher
        createIntentFilter(activity, arrayOf("com.gau.go.launcherex.theme", "com.zeroteam.zerolauncher.theme", AndroidManifestBlock.VALUE_android_intent_action_MAIN), emptyArray()) //GO Launcher & Zero Launcher
        createIntentFilter(activity, arrayOf("jp.co.a_tm.android.launcher.icons.ACTION_PICK_ICON"), arrayOf("android.intent.category.DEFAULT")) //+HOME Icon Picker
        createIntentFilter(activity, arrayOf(AndroidManifestBlock.VALUE_android_intent_action_MAIN, "com.vivid.launcher.theme"), arrayOf("android.intent.category.DEFAULT")) //V Launcher

        val activityName = activity.getOrCreateAndroidAttribute(
            AndroidManifestBlock.NAME_name,
            AndroidManifestBlock.ID_name
        )
        activityName.valueAsString = "com.kaanelloed.iconerationiconpack.MainActivity"

        val exported = activity.getOrCreateAndroidAttribute(
            AndroidManifestBlock.NAME_exported,
            AndroidManifestBlock.ID_exported
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

    @Suppress(SuppressSameParameterValue)
    private fun createXmlLayoutResource(apkModule: ApkModule, packageBlock: PackageBlock, xmlFile: XmlMemoryFile, name: String): Entry {
        val resPath = "res/${name}.xml"
        val xmlEncoder = XmlEncoder(packageBlock)

        val res = packageBlock.getOrCreate("", "layout", name)
        res.setValueAsString(resPath)

        apkModule.add(xmlEncoder.encodeToSource(xmlFile, resPath))
        return res
    }

    private fun createXmlResource(apkModule: ApkModule, packageBlock: PackageBlock, xmlFile: XmlMemoryFile, name: String) {
        val resPath = "res/${name}.xml"
        val xmlEncoder = XmlEncoder(packageBlock)

        val res = packageBlock.getOrCreate("", "xml", name)
        res.setValueAsString(resPath)

        apkModule.add(xmlEncoder.encodeToSource(xmlFile, resPath))
    }

    private fun createXmlDrawableResource(apkModule: ApkModule, packageBlock: PackageBlock, xmlFile: XmlMemoryFile, name: String, qualifier: String = "", type: String = "drawable"): Entry {
        val resPath = "res/${name}.xml"
        val xmlEncoder = XmlEncoder(packageBlock)

        val res = packageBlock.getOrCreate(qualifier, type, name)
        res.setValueAsString(resPath)

        apkModule.add(xmlEncoder.encodeToSource(xmlFile, resPath))
        return res
    }

    @Suppress(SuppressSameParameterValue)
    private fun createBitmapResource(apkModule: ApkModule, packageBlock: PackageBlock, @DrawableRes resId: Int, name: String, qualifier: String = "", type: String = "drawable"): Entry {
        val bitmap = ResourcesCompat.getDrawable(ctx.resources, resId, null)!!.toBitmap()
        return createBitmapResource(apkModule, packageBlock, bitmap, name, qualifier, type)
    }

    private fun createBitmapResource(apkModule: ApkModule, packageBlock: PackageBlock, bitmap: Bitmap, name: String, qualifier: String = "", type: String = "drawable"): Entry {
        val extension = if (PackageVersion.is29OrMore()) "webp" else "png" //Lossless webp since sdk 29
        val resPath = "res/$name$qualifier.$extension"

        val icon = packageBlock.getOrCreate(qualifier, type, name)
        icon.setValueAsString(resPath)

        apkModule.add(generateBitmap(bitmap, resPath))
        return icon
    }

    private fun generateBitmap(image: Bitmap, name: String): ByteInputSource {
        return if (PackageVersion.is30OrMore()) {
            generateLosslessWebp(image, name)
        } else if (PackageVersion.is29OrMore()) {
            generateWebp(image, name)
        } else {
            generatePng(image, name)
        }
    }

    private fun generatePng(image: Bitmap, name: String): ByteInputSource {
        return compressBitmap(image, name, CompressFormat.PNG)
    }

    @Suppress(SuppressDeprecation)
    private fun generateWebp(image: Bitmap, name: String): ByteInputSource {
        return compressBitmap(image, name, CompressFormat.WEBP) //Since sdk 29 webp with quality of 100 is lossless
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun generateLosslessWebp(image: Bitmap, name: String): ByteInputSource {
        return compressBitmap(image, name, CompressFormat.WEBP_LOSSLESS)
    }

    private fun compressBitmap(image: Bitmap, name: String, format: CompressFormat, quality: Int = 100): ByteInputSource {
        val bytes = image.getBytes(format, quality)
        return ByteInputSource(bytes, name)
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

    private fun getCurrentVersionCode(): Long {
        val appMan = ApplicationManager(ctx)

        val iconPack = appMan.getPackage(iconPackName)
            ?: return 0L

        return appMan.getVersionCode(iconPack)
    }

    private fun buildDex(apkModule: ApkModule, resourceId: Int) {
        buildClasses(apkModule, resourceId)
        buildClasses2(apkModule, resourceId)
    }

    private fun buildClasses(apkModule: ApkModule, resourceId: Int) {
        apkModule.add(ByteInputSource(buildClasses(resourceId), "classes.dex"))
    }

    private fun buildClasses(resourceId: Int): ByteArray {
        val dex = DexFile.createDefault()

        val dexBuilder = DexClassBuilder(dex.dexLayout.sectionList)

        dexBuilder.buildRClass()
        dexBuilder.buildRLayoutClass(resourceId)
        dex.refresh()
        dex.version = apiToDexVersion(minSdkVersion)
        dex.clearEmptySections()
        dex.sortSection(SectionType.getR8Order())
        dex.shrink()
        dex.refreshFull()
        val bytes = dex.bytes
        dex.close()

        return bytes
    }

    private fun buildClasses2(apkModule: ApkModule, resourceId: Int) {
        apkModule.add(ByteInputSource(buildClasses2(resourceId), "classes2.dex"))
    }

    private fun buildClasses2(resourceId: Int): ByteArray {
        val dex = DexFile.createDefault()

        val dexBuilder = DexClassBuilder(dex.dexLayout.sectionList)

        dexBuilder.buildMainActivityClass(resourceId)
        dexBuilder.buildBuildConfig()
        dex.refresh()
        dex.version = apiToDexVersion(minSdkVersion)
        dex.clearEmptySections()
        dex.sortSection(SectionType.getR8Order())
        dex.shrink()
        dex.refreshFull()
        val bytes = dex.bytes
        dex.close()

        return bytes
    }

    @Suppress(SuppressSameParameterValue)
    private fun apiToDexVersion(api: Int): Int {
        return when {
            api <= 23 -> 35
            api in 24 .. 25 -> 37
            api in 26 .. 27 -> 38
            api in 29 .. 34 -> 40
            api == 35 -> 41

            else -> 39
        }
    }

    private fun createLayout(): LayoutXml {
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val currentDateTime = LocalDateTime.now().format(dateFormatter)

        val xml = LayoutXml()
        xml.textView("Icon pack created: $currentDateTime")
        xml.readAndClose()
        return xml
    }

    private fun insertIconPackAppIcons(apkModule: ApkModule, packageBlock: PackageBlock, manifest: AndroidManifestBlock) {
        createBitmapResource(apkModule, packageBlock, R.drawable.mdpi_ic_launcher, "ic_launcher", "mdpi", "mipmap")
        createBitmapResource(apkModule, packageBlock, R.drawable.hdpi_ic_launcher, "ic_launcher", "hdpi", "mipmap")
        createBitmapResource(apkModule, packageBlock, R.drawable.xhdpi_ic_launcher, "ic_launcher", "xhdpi", "mipmap")
        createBitmapResource(apkModule, packageBlock, R.drawable.xxhdpi_ic_launcher, "ic_launcher", "xxhdpi", "mipmap")
        createBitmapResource(apkModule, packageBlock, R.drawable.xxxhdpi_ic_launcher, "ic_launcher", "xxxhdpi", "mipmap")

        createBitmapResource(apkModule, packageBlock, R.drawable.mdpi_ic_launcher_round, "ic_launcher_round", "mdpi", "mipmap")
        createBitmapResource(apkModule, packageBlock, R.drawable.hdpi_ic_launcher_round, "ic_launcher_round", "hdpi", "mipmap")
        createBitmapResource(apkModule, packageBlock, R.drawable.xhdpi_ic_launcher_round, "ic_launcher_round", "xhdpi", "mipmap")
        createBitmapResource(apkModule, packageBlock, R.drawable.xxhdpi_ic_launcher_round, "ic_launcher_round", "xxhdpi", "mipmap")
        createBitmapResource(apkModule, packageBlock, R.drawable.xxxhdpi_ic_launcher_round, "ic_launcher_round", "xxxhdpi", "mipmap")

        val foreground = ImageVector.vectorResource(null, ctx.resources, R.drawable.alchemiconpack_ic_launcher_foreground)
        createXmlDrawableResource(apkModule, packageBlock, foreground.toXmlFile(), "ic_launcher_foreground")

        val launcher = AdaptiveIconXml()
        launcher.foreground("ic_launcher")
        launcher.background("#340E7D")

        createXmlDrawableResource(apkModule, packageBlock, launcher, "ic_launcher", "anydpi-v26", "mipmap")
        createXmlDrawableResource(apkModule, packageBlock, launcher, "ic_launcher_round", "anydpi-v26", "mipmap")

        val appIcon = ValueCoder.encodeReference(packageBlock, "@mipmap/ic_launcher")
        val appIconRound = ValueCoder.encodeReference(packageBlock, "@mipmap/ic_launcher_round")

        manifest.iconResourceId = appIcon.value
        manifest.roundIconResourceId = appIconRound.value
    }

    private fun getInstalledVersion(): Version? {
        val appMan = ApplicationManager(ctx)

        val iconPack = appMan.getPackage(iconPackName)
            ?: return null

        val versionCode = appMan.getVersionCode(iconPack)
        val versionName = iconPack.versionName!!

        return Version(versionCode, versionName)
    }

    private fun signApk(file: File, outFile: File) {
        val pwd = "s3cur3p@ssw0rd"

        val dtl = ApkUtils.KeyStoreDetails(keyStoreFile, pwd, "alias", pwd)
        ApkUtils.signApk(file, outFile, "Iconeration", dtl)
    }
}