package com.kaanelloed.iconeration

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.*

class CannyEdgeDetector() {
    private val gaussianCutOff: Float = 0.005F
    private val magnitudeScale: Float = 100F
    private val magnitudeLimit: Float = 1000F
    private val magnitudeMax: Int = (magnitudeScale * magnitudeLimit).toInt()

    private var height: Int = 0
    private var width: Int = 0
    private var picSize: Int = 0
    private lateinit var data: IntArray
    private lateinit var magnitude: IntArray

    private lateinit var sourceImage: Bitmap
    lateinit var edgesImage: Bitmap
        private set

    var gaussianKernelRadius: Float = 0F
        set(value) {
            if (value < 0.1F) throw java.lang.IllegalArgumentException()
            field = value
        }
    var lowThreshold: Float = 0F
        set(value) {
            if (value < 0) throw java.lang.IllegalArgumentException()
            field = value
        }
    var highThreshold: Float = 0F
        set(value) {
            if (value < 0) throw java.lang.IllegalArgumentException()
            field = value
        }
    var gaussianKernelWidth: Int = 0
        set(value) {
            if (value < 2) throw java.lang.IllegalArgumentException()
            field = value
        }
    var contrastNormalized: Boolean = false

    private lateinit var xConv: FloatArray
    private lateinit var yConv: FloatArray
    private lateinit var xGradient: FloatArray
    private lateinit var yGradient: FloatArray

    init {
        lowThreshold = 2.5F
        highThreshold = 7.5F
        gaussianKernelRadius = 2F
        gaussianKernelWidth = 16
        contrastNormalized = false
    }

    fun process(image: Bitmap, edgeColor: Int) {
        sourceImage = image

        width = sourceImage.width
        height = sourceImage.height
        picSize = width * height

        initArrays()
        readLuminance()
        if (contrastNormalized) normalizeContrast()
        computeGradients(gaussianKernelRadius, gaussianKernelWidth)
        val low = (lowThreshold * magnitudeScale).roundToInt()
        val high = (highThreshold * magnitudeScale).roundToInt()
        performHysteresis(low, high)
        thresholdEdges(edgeColor)
        writeEdges(data)
    }

    private fun initArrays() {
        if (!this::data.isInitialized || picSize != data.size) {
            data = IntArray(picSize)
            magnitude = IntArray(picSize)

            xConv = FloatArray(picSize)
            yConv = FloatArray(picSize)
            xGradient = FloatArray(picSize)
            yGradient = FloatArray(picSize)
        }
    }

    private fun computeGradients(kernelRadius: Float, kernelWidth: Int) {
        val kernel = FloatArray(kernelWidth)
        val diffKernel = FloatArray(kernelWidth)
        var kWidth = 0

        for (i in 0 until kernelWidth) {
            val g1 = gaussian(i.toFloat(), kernelRadius)
            if (g1 <= gaussianCutOff && i >= 2) break
            val g2 = gaussian(i - 0.5F, kernelRadius)
            val g3 = gaussian(i + 0.5F, kernelRadius)

            kernel[i] = (g1 + g2 + g3) / 3F / (sqrt(2F * PI.toFloat()) * kernelRadius)
            diffKernel[i] = g3 - g2

            kWidth++
        }

        var initX = kWidth
        var maxX = width - kWidth
        var initY = width * kWidth
        var maxY = width * (height - kWidth)

        kWidth++

        //First convolution
        for (x in initX until maxX) {
            for (y in initY until maxY step width) {
                val index = x + y
                var sumX = data[index] * kernel[0]
                var xOffset = 1
                var yOffset = width

                for (i in xOffset until kWidth) {
                    sumX += kernel[xOffset] * (data[index - xOffset] + data[index + xOffset])
                    yOffset += width
                    xOffset++
                }

                xConv[index] = sumX
            }
        }

        //Second convolution
        for (x in initX until maxX) {
            for (y in initY until maxY step width) {
                val index = x + y
                var sumY = xConv[index] * kernel[0]
                var xOffset = 1
                var yOffset = width

                for (i in xOffset until kWidth) {
                    sumY += xConv[xOffset] * (xConv[index - xOffset] + xConv[index + xOffset])
                    yOffset += width
                    xOffset++
                }

                yConv[index] = sumY
            }
        }

        for (x in initX until maxX) {
            for (y in initY until maxY step width) {
                var sum = 0F
                val index = x + y

                for (i in 1 until kWidth)
                    sum += diffKernel[i] * (yConv[index - i] - yConv[index + i])

                xGradient[index] = sum
            }
        }

        for (x in kWidth until width - kWidth) {
            for (y in initY until maxY step width) {
                var sum = 0.0F
                val index = x + y
                var yOffset = width

                for (i in 1 until kWidth) { //It is normal index - yOffset can be negative ?
                    sum += diffKernel[i] * (yConv.elemOrCloser(index - yOffset) - yConv[index + yOffset])
                    yOffset += width
                }

                yGradient[index] = sum
            }
        }

        initX = kWidth
        maxX = width - kWidth
        initY = width * kWidth
        maxY = width * (height - kWidth)

        for (x in initX until maxX) {
            for (y in initY until maxY step width) {
                val index = x + y
                val indexN = index - width //It is normal it can be negative ?
                val indexS = index + width
                val indexW = index - 1
                val indexE = index + 1
                val indexNW = indexN - 1
                val indexNE = indexN + 1
                val indexSW = indexS - 1
                val indexSE = indexS + 1

                val xGrad = xGradient[index]
                val yGrad = yGradient[index]
                val gradMag = hypot(xGrad, yGrad)

                //Non-maximal suppression
                val nMag = hypot(xGradient.elemOrCloser(indexN), yGradient.elemOrCloser(indexN))
                val sMag = hypot(xGradient[indexS], yGradient[indexS])
                val wMag = hypot(xGradient[indexW], yGradient[indexW])
                val eMag = hypot(xGradient[indexE], yGradient[indexE])
                val neMag = hypot(xGradient.elemOrCloser(indexNE), yGradient.elemOrCloser(indexNE))
                val seMag = hypot(xGradient[indexSE], yGradient[indexSE])
                val swMag = hypot(xGradient[indexSW], yGradient[indexSW])
                val nwMag = hypot(xGradient.elemOrCloser(indexNW), yGradient.elemOrCloser(indexNW))
                var tmp: Float

                var gradientOk: Boolean
                if (xGrad * yGrad <= 0F) {
                    if (abs(xGrad) >= abs(yGrad)) {
                        tmp = abs(xGrad * gradMag)
                        gradientOk = tmp >= abs(yGrad * neMag - (xGrad + yGrad) * eMag) && tmp > abs(yGrad * swMag - (xGrad + yGrad) * wMag)
                    } else {
                        tmp = abs(yGrad * gradMag)
                        gradientOk = tmp >= abs(xGrad * neMag - (yGrad + xGrad) * nMag) && tmp > abs(xGrad * swMag - (yGrad + xGrad) * sMag)
                    }
                } else {
                    if (abs(xGrad) >= abs(yGrad)) {
                        tmp = abs(xGrad * gradMag)
                        gradientOk = tmp >= abs(yGrad * seMag - (xGrad - yGrad) * eMag) && tmp > abs(yGrad * nwMag - (xGrad - yGrad) * wMag)
                    } else {
                        tmp = abs(yGrad * gradMag)
                        gradientOk = tmp >= abs(xGrad * seMag - (yGrad - xGrad) * sMag) && tmp > abs(xGrad * nwMag - (yGrad - xGrad) * nMag)
                    }
                }

                if (gradientOk) {
                    if (gradMag >= magnitudeLimit) {
                        magnitude[index] = magnitudeMax
                    } else {
                        magnitude[index] = (magnitudeScale * gradMag).toInt()
                    }
                } else {
                    magnitude[index] = 0
                }
            }
        }
    }

    private fun gaussian(x: Float, sigma: Float): Float {
        return exp(-(x * x) / (2F * sigma * sigma))
    }

    private fun performHysteresis(low: Int, high: Int) {
        data = IntArray(data.size)

        var offset = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (data[offset] == 0 && magnitude[offset] >= high) {
                    follow(x, y, offset, low)
                }

                offset++
            }
        }
    }

    private fun follow(x1: Int, y1: Int, i1: Int, threshold: Int) {
        var x0: Int = x1
        if (x1 == 0) x0--
        var x2: Int = x1
        if (x1 == width - 1) x2++
        var y0: Int = y1
        if (y1 == 0) y0--
        var y2: Int = y1
        if (y1 == height - 1) y2++

        data[i1] = magnitude[i1]
        for (x in x0..x2) {
            for (y in y0 .. y2) {
                val i2 = x + y * width
                if ((y != y1 || x != x1) && data.elemOrCloser(i2) == 0 && magnitude.elemOrCloser(i2) >= threshold) {
                    follow(x, y, i2, threshold)
                    return
                }
            }
        }
    }

    private fun thresholdEdges(edgeColor: Int) {
        for (i in 0 until picSize) {
            if (data[i] > 0) {
                data[i] = edgeColor
            } else {
                data[i] = Color.TRANSPARENT
            }
        }
    }

    private fun luminance(red: Int, green: Int, blue: Int): Int {
        return (0.299F * red + 0.587F * green + 0.114F * blue).roundToInt()
    }

    private fun readLuminance() {
        val pixels = IntArray(picSize)

        sourceImage.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in 0 until picSize) {
            val pixel = pixels[i]
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            data[i] = luminance(red, green, blue)
        }
    }

    private fun normalizeContrast() {
        val histogram = IntArray(256)

        for (element in data) {
            histogram[element]++
        }

        val remap = IntArray(256)
        var sum = 0
        var j = 0

        for (i in histogram.indices) {
            sum += histogram[i]
            val target = sum * 255 / picSize

            for (k in j + 1 .. target) {
                remap[k] = i
            }

            j = target
        }

        for (i in data.indices) {
            data[i] = remap[data[i]]
        }
    }

    private fun writeEdges(pixels: IntArray) {
        //edgesImage = Bitmap.createBitmap(sourceImage)
        //edgesImage.setPixels(pixels, 0, width, 0, 0, width, height)
        edgesImage = Bitmap.createBitmap(pixels, width, height, sourceImage.config)
    }

    private fun FloatArray.elemOrCloser(index: Int): Float {
        var newIndex = index
        if (newIndex < 0) newIndex = 0
        if (newIndex >= this.size) newIndex = this.size - 1

        return this[newIndex]
    }

    private fun IntArray.elemOrCloser(index: Int): Int {
        var newIndex = index
        if (newIndex < 0) newIndex = 0
        if (newIndex >= this.size) newIndex = this.size - 1

        return this[newIndex]
    }
}