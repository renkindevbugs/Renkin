package com.kaanelloed.iconeration.icon.creator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.VectorDrawable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.core.graphics.drawable.toBitmap
import com.kaanelloed.iconeration.MainActivity
import com.kaanelloed.iconeration.data.GenerationType
import com.kaanelloed.iconeration.data.IconPackApplication
import com.kaanelloed.iconeration.drawable.ForegroundIconDrawable
import com.kaanelloed.iconeration.icon.AdaptiveIcon
import com.kaanelloed.iconeration.icon.parser.AdaptiveIconParser
import com.kaanelloed.iconeration.icon.BaseIcon
import com.kaanelloed.iconeration.icon.BitmapIcon
import com.kaanelloed.iconeration.icon.parser.IconParser
import com.kaanelloed.iconeration.icon.InsetIcon
import com.kaanelloed.iconeration.icon.VectorIcon
import com.kaanelloed.iconeration.image.edge.CannyEdgeDetector
import com.kaanelloed.iconeration.image.tracer.ImageTracer
import com.kaanelloed.iconeration.packages.ApplicationManager
import com.kaanelloed.iconeration.packages.PackageInfoStruct
import com.kaanelloed.iconeration.packages.PackageVersion
import com.kaanelloed.iconeration.vector.MutableImageVector.Companion.toMutableImageVector
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.applyAndRemoveGroup
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.editPaths
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.resizeAndCenter
import com.kaanelloed.iconeration.vector.VectorEditor.Companion.scaleAtCenter
import com.kaanelloed.iconeration.xml.XmlParser.Companion.toXmlNode

class IconGenerator(
    private val ctx: Context,
    private val activity: MainActivity,
    private val options: GenerationOptions,
    private val iconPackApplications: Map<IconPackApplication, Pair<Int, Drawable>>
) {
    private lateinit var apps: List<PackageInfoStruct>

    fun generateIcons(application: PackageInfoStruct, type: GenerationType) {
        generateIcons(listOf(application), type)
    }

    fun generateIcons(applications: List<PackageInfoStruct>, type: GenerationType) {
        apps = applications

        when (type) {
            GenerationType.PATH -> generatePathDetection()
            GenerationType.EDGE -> generateCannyEdgeDetection()
            GenerationType.ONE_LETTER -> generateFirstLetter()
            GenerationType.TWO_LETTERS -> generateTwoLetter()
            GenerationType.APP_NAME -> generateAppName()
        }
    }

    fun colorizeFromIconPack(application: PackageInfoStruct, icon: Drawable) {
        if (isVectorDrawable(icon)) {
            if (!exportIconPackXML(application)) {
                changeIconPackColor(application, icon)
            }
        } else {
            changeIconPackColor(application, icon)
        }
    }

    private fun generateCannyEdgeDetection() {
        var edgeDetector: CannyEdgeDetector
        for (app in apps) {
            val iconPack = iconPackApplicationIcon(app.packageName)

            if (iconPack == null) {
                edgeDetector = CannyEdgeDetector()
                edgeDetector.process(
                    getAppIconBitmap(app),
                    options.color
                )
                activity.editApplication(app, app.changeExport(BitmapIcon(edgeDetector.edgesImage)))
            } else changeIconPackColor(app, iconPack)
        }
    }

    private fun generatePathDetection() {
        val appMan = ApplicationManager(ctx)

        for (app in apps) {
            val iconPack = iconPackApplicationIcon(app.packageName)

            if (iconPack == null) {
                if (isVectorDrawable(app.icon) && options.vector) {
                    generatePathFromXML(appMan, app)
                } else {
                    generateColorQuantizationDetection(app)
                }
            } else colorizeFromIconPack(app, iconPack)
        }
    }

    private fun generatePathFromXML(appMan: ApplicationManager, app: PackageInfoStruct) {
        val res = appMan.getResources(app.packageName)

        if (res == null) {
            generateColorQuantizationDetection(app)
            return
        }

        val appIcon = IconParser.parseDrawable(res, app.icon, app.iconID)
        var vectorIcon: BaseIcon = appIcon

        if (appIcon is AdaptiveIcon) {
            if (appIcon.foreground is VectorIcon) {
                vectorIcon = appIcon.foreground
            }

            if (appIcon.monochrome is VectorIcon && options.monochrome) {
                vectorIcon = appIcon.monochrome
            }
        }

        if (vectorIcon !is VectorIcon) {
            generateColorQuantizationDetection(app)
            return
        }

        val mutableVector = vectorIcon.vector.toMutableImageVector()

        val stroke = mutableVector.viewportHeight / 48 //1F at 48
        mutableVector.root.editPaths(stroke, SolidColor(Color.Unspecified), SolidColor(Color(options.color)))
        mutableVector.resizeAndCenter().applyAndRemoveGroup().scaleAtCenter(6F / 4F)
        mutableVector.tintColor = Color.Unspecified

        if (options.themed) {
            mutableVector.scaleAtCenter(0.5F)
        }

        activity.editApplication(app, app.changeExport(VectorIcon(mutableVector)))
    }

    private fun generateColorQuantizationDetection(app: PackageInfoStruct) {
        val imageVector = ImageTracer.imageToVector(getAppIconBitmap(app), ImageTracer.TracingOptions())

        val vector = imageVector.toMutableImageVector()
        val stroke = imageVector.viewportHeight / 48 //1F at 48
        vector.root.editPaths(stroke, SolidColor(Color.Unspecified), SolidColor(Color(options.color)))
        vector.resizeAndCenter()

        if (options.themed) {
            vector.scaleAtCenter(0.5F)
        }

        activity.editApplication(app, app.changeExport(VectorIcon(vector)))
    }

    private fun getAppIconBitmap(app: PackageInfoStruct, maxSize: Int = 500): Bitmap {
        var newIcon = app.icon

        if (newIcon is AdaptiveIconDrawable) {
            val adaptiveIcon = newIcon
            if (adaptiveIcon.foreground is BitmapDrawable || adaptiveIcon.foreground is VectorDrawable) {
                newIcon = ForegroundIconDrawable(adaptiveIcon.foreground)
            }

            if (PackageVersion.is33OrMore() && adaptiveIcon.monochrome != null && options.monochrome) {
                if (adaptiveIcon.monochrome is BitmapDrawable || adaptiveIcon.monochrome is VectorDrawable) {
                    newIcon = ForegroundIconDrawable(adaptiveIcon.monochrome!!)
                }
            }
        }

        val maxWidthOrHeight = kotlin.math.max(newIcon.intrinsicWidth, newIcon.intrinsicHeight)
        if (maxWidthOrHeight > maxSize) {
            val multi = maxSize / maxWidthOrHeight.toFloat()
            val newWidth = (newIcon.intrinsicWidth * multi).toInt()
            val newHeight = (newIcon.intrinsicHeight * multi).toInt()

            return newIcon.toBitmap(newWidth, newHeight)
        }

        return newIcon.toBitmap()
    }

    private fun isVectorDrawable(image: Drawable): Boolean {
        if (image is VectorDrawable)
            return true

        if (image is AdaptiveIconDrawable) {
            if (image.foreground is VectorDrawable) {
                return true
            }

            if (image.foreground is InsetDrawable) {
                val inset = image.foreground as InsetDrawable
                if (inset.drawable is VectorDrawable) {
                    return true
                }
            }

            if (PackageVersion.is33OrMore() && options.monochrome) {
                if (image.monochrome is VectorDrawable) {
                    return true
                }
            }
        }

        return false
    }

    private fun generateFirstLetter() {
        val gen = LetterGenerator(ctx)

        for (app in apps) {
            val iconPack = iconPackApplicationIcon(app.packageName)

            if (iconPack == null) {
                val draw = gen.generateFirstLetter(app.appName, options.color)
                val newIcon = draw.toBitmap(256, 256)
                activity.editApplication(app, app.changeExport(BitmapIcon(addBackground(newIcon))))
            } else changeIconPackColor(app, iconPack)
        }
    }

    private fun generateTwoLetter() {
        val gen = LetterGenerator(ctx)

        for (app in apps) {
            val iconPack = iconPackApplicationIcon(app.packageName)

            if (iconPack == null) {
                val draw = gen.generateTwoLetters(app.appName, options.color)
                val newIcon = draw.toBitmap(256, 256)
                activity.editApplication(app, app.changeExport(BitmapIcon(addBackground(newIcon))))
            } else changeIconPackColor(app, iconPack)
        }
    }

    private fun generateAppName() {
        val gen = LetterGenerator(ctx)

        for (app in apps) {
            val iconPack = iconPackApplicationIcon(app.packageName)

            if (iconPack == null) {
                val draw = gen.generateAppName(app.appName, options.color)
                val newIcon = draw.toBitmap(256, 256)
                activity.editApplication(app, app.changeExport(BitmapIcon(addBackground(newIcon))))
            } else changeIconPackColor(app, iconPack)
        }
    }

    private fun changeIconPackColor(app: PackageInfoStruct, icon: Drawable) {
        val coloredIcon = colorIcon(icon)
        activity.editApplication(app, app.changeExport(BitmapIcon(coloredIcon)))
    }

    private fun colorIcon(icon: Drawable): Bitmap {
        val oldIcon = if (icon is AdaptiveIconDrawable) {
            ForegroundIconDrawable(icon.foreground).toBitmap()
        } else {
            icon.toBitmap()
        }

        val coloredIcon = oldIcon.copy(oldIcon.config, true)
        val paint = Paint()

        paint.colorFilter = PorterDuffColorFilter(options.color, PorterDuff.Mode.SRC_IN)
        Canvas(coloredIcon).drawBitmap(coloredIcon, 0F, 0F, paint)

        return addBackground(coloredIcon)
    }

    private fun addBackground(image: Bitmap): Bitmap {
        return if (options.themed) image.changeBackgroundColor(options.bgColor) else image
    }

    private fun exportIconPackXML(app: PackageInfoStruct): Boolean {
        val iconPackApp = iconPackApplication(app.packageName)!!

        val res = ApplicationManager(ctx).getResources(iconPackApp.iconPackName) ?: return false

        val iconID = iconPackApplicationIconID(app.packageName)
        val parser = ApplicationManager(ctx).getPackageResourceXml(iconPackApp.iconPackName, iconID) ?: return false

        val adaptiveIcon = AdaptiveIconParser.parse(res, parser.toXmlNode()) ?: return false
        var vectorIcon: VectorIcon? = null

        if (adaptiveIcon.foreground is InsetIcon) {
            val inset = adaptiveIcon.foreground
            if (inset.innerIcon is VectorIcon) {
                vectorIcon = inset.innerIcon
            }
        }

        if (adaptiveIcon.foreground is VectorIcon) {
            vectorIcon = adaptiveIcon.foreground
        }

        if (vectorIcon == null) {
            return false
        }

        val mutableVector = vectorIcon.vector.toMutableImageVector().resizeAndCenter().scaleAtCenter(0.5f)

        val stroke = mutableVector.viewportHeight / 48 //1F at 48
        mutableVector.root.editPaths(stroke, SolidColor(Color.Unspecified), SolidColor(Color(options.color)))
        mutableVector.tintColor = Color.Unspecified

        activity.editApplication(app, app.changeExport(VectorIcon(mutableVector)))
        return true
    }

    private fun iconPackApplication(packageName: String): IconPackApplication? {
        return iconPackApplications.keys.find { it.packageName == packageName }
    }

    private fun iconPackApplicationIcon(packageName: String): Drawable? {
        val app = iconPackApplication(packageName)

        if (app != null) {
            return iconPackApplications[app]!!.second
        }

        return null
    }

    private fun iconPackApplicationIconID(packageName: String): Int {
        val app = iconPackApplication(packageName)

        if (app != null) {
            return iconPackApplications[app]!!.first
        }

        return -1
    }

    class GenerationOptions(
        val color: Int,
        val monochrome: Boolean,
        val vector: Boolean,
        val themed: Boolean = false,
        val bgColor: Int = 0
    )

    private fun Bitmap.changeBackgroundColor(color: Int): Bitmap {
        val newBitmap = Bitmap.createBitmap(width, height, config)
        val canvas = Canvas(newBitmap)
        canvas.drawColor(color)
        canvas.drawBitmap(this, 0F, 0F, null)
        recycle()
        return newBitmap
    }
}