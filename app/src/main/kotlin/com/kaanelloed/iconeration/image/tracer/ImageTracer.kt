package com.kaanelloed.iconeration.image.tracer

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import java.nio.IntBuffer
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

class ImageTracer {
    companion object {
        fun imageToVector(image: Bitmap, options: TracingOptions): ImageVector {
            val imageData = loadImageData(image)
            val palette = generatePalette(options)

            return imageDataToVector(imageData, options, palette)
        }

        private fun imageDataToVector(imageData: ImageData, options: TracingOptions, palette: Array<ByteArray>): ImageVector {
            val indexedImage = imageDataToTraceData(imageData, options, palette)
            return VectorUtils.getVector(indexedImage, options)
        }

        // Tracing ImageData, then returning IndexedImage with tracedata in layers
        private fun imageDataToTraceData(imageData: ImageData, options: TracingOptions, palette: Array<ByteArray>): IndexedImage {
            // 1. Color quantization
            val ii = VectorizingUtils.colorQuantization(imageData, palette, options)
            // 2. Layer separation and edge detection
            val rawLayers = VectorizingUtils.layering(ii)
            // 3. Batch pathscan
            val bps = VectorizingUtils.batchpathscan(rawLayers, options.pathomit)
            // 4. Batch interpolation
            val bis = VectorizingUtils.batchInternodes(bps)
            // 5. Batch tracing
            ii.layers = VectorizingUtils.batchtracelayers(bis, options.ltres, options.qtres)
            return ii
        }

        private fun loadImageData(image: Bitmap): ImageData {
            val width = image.width
            val height = image.height

            val ib = IntBuffer.allocate(width * height)
            image.copyPixelsToBuffer(ib)

            val rawData = ib.array()
            val data = ByteArray(rawData.size * 4)

            for (i in rawData.indices) {
                data[i * 4 + 3] = byteTrans((rawData[i] ushr 24).toByte())
                data[i * 4 + 2] = byteTrans((rawData[i] ushr 16).toByte())
                data[i * 4 + 1] = byteTrans((rawData[i] ushr 8).toByte())
                data[i * 4] = byteTrans(rawData[i].toByte())
            }

            return ImageData(width, height, data)
        }

        // The bitshift method in loadImageData creates signed bytes where -1 -> 255 unsigned ; -128 -> 128 unsigned ;
        // 127 -> 127 unsigned ; 0 -> 0 unsigned ; These will be converted to -128 (representing 0 unsigned) ...
        // 127 (representing 255 unsigned) and tosvgcolorstr will add +128 to create RGB values 0..255
        private fun byteTrans(b: Byte): Byte {
            return if (b < 0) {
                (b + 128).toByte()
            } else {
                (b - 128).toByte()
            }
        }

        private fun getPalette(image: Bitmap, options: TracingOptions): Array<ByteArray> {
            val numberOfColors = options.numberOfColors
            val pixels = Array(image.width) {
                IntArray(image.height)
            }

            for (i in 0 until image.width) {
                for (j in 0 until image.height) {
                    pixels[i][j] = image.getPixel(i, j)
                }
            }

            val palette = Quantize.quantizeImage(pixels, numberOfColors)
            val bytePalette = Array(numberOfColors) {
                ByteArray(4)
            }

            for (i in palette.indices) {
                val c = Color.valueOf(palette[i])
                bytePalette[i][0] = c.red().toInt().toByte()
                bytePalette[i][1] = c.green().toInt().toByte()
                bytePalette[i][2] = c.blue().toInt().toByte()
                bytePalette[i][3] = 0
            }

            return bytePalette
        }

        private fun generatePalette(options: TracingOptions): Array<ByteArray> {
            val numbersOfColor = options.numberOfColors

            val palette = Array(numbersOfColor) {
                ByteArray(4)
            }

            if (numbersOfColor < 8) {
                //Grayscale
                val graystep = 255.0 / (numbersOfColor - 1)
                for (ccnt in 0 until numbersOfColor) {
                    palette[ccnt][0] = (-128 + (ccnt * graystep).roundToInt()).toByte()
                    palette[ccnt][1] = (-128 + (ccnt * graystep).roundToInt()).toByte()
                    palette[ccnt][2] = (-128 + (ccnt * graystep).roundToInt()).toByte()
                    palette[ccnt][3] = 127.toByte()
                }
            } else {
                // RGB color cube
                val colorqnum = floor(numbersOfColor.toDouble().pow(1.0 / 3.0))
                    .toInt() // Number of points on each edge on the RGB color cube
                val colorstep = floor((255.0 / (colorqnum - 1)))
                    .toInt() // distance between points
                var ccnt = 0

                for (rcnt in 0 until colorqnum) {
                    for (gcnt in 0 until colorqnum) {
                        for (bcnt in 0 until colorqnum) {
                            palette[ccnt][0] = (-128 + rcnt * colorstep).toByte()
                            palette[ccnt][1] = (-128 + gcnt * colorstep).toByte()
                            palette[ccnt][2] = (-128 + bcnt * colorstep).toByte()
                            palette[ccnt][3] = 127.toByte()
                            ccnt++
                        }
                    }
                }

                // Rest is random
                for (rcnt in ccnt until numbersOfColor) {
                    palette[ccnt][0] = (-128 + (0..255).random()).toByte()
                    palette[ccnt][1] = (-128 + (0..255).random()).toByte()
                    palette[ccnt][2] = (-128 + (0..255).random()).toByte()
                    palette[ccnt][3] = (-128 + (0..255).random()).toByte()
                }
            }

            return palette
        }

        private fun samplePalette(imageData: ImageData, options: TracingOptions): Array<ByteArray> {
            val numbersOfColor = options.numberOfColors
            var idx: Int

            val palette = Array(numbersOfColor) {
                ByteArray(4)
            }

            for (i in 0 until numbersOfColor) {
                idx = (floor((0..imageData.data.size).random() / 4.0) * 4).toInt()
                palette[i][0] = imageData.data[idx]
                palette[i][1] = imageData.data[idx + 1]
                palette[i][2] = imageData.data[idx + 2]
                palette[i][3] = imageData.data[idx + 3]
            }

            return palette
        }
    }

    // Container for the color-indexed image before and tracedata after vectorizing
    class IndexedImage(
        val array: Array<IntArray>, // array[x][y] of palette colors
        val palette: Array<ByteArray> // array[palettelength][4] RGBA color palette
    ) {
        val width: Int = array[0].size - 2
        val height: Int = array.size - 2
        var layers: ArrayList<ArrayList<ArrayList<DoubleArray>>>? = null //tracedata
    }

    // https://developer.mozilla.org/en-US/docs/Web/API/ImageData
    class ImageData(val width: Int
    , val height: Int
    , val data: ByteArray)// raw byte data: R G B A R G B A ...

    class TracingOptions {
        // Tracing
        var ltres = 1F
        var qtres = 1F
        var pathomit = 8F
        // Color quantization
        var numberOfColors = 16
        var colorquantcycles = 3
        // Output rendering
        var scale = 1F
        var roundcoords = 1F
        var lcpr = 0F
        var qcpr = 0F
        var viewBox = false
        // Blur
        var blurRadius = 0F
        var blurDelta = 20F
    }
}