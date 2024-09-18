package com.kaanelloed.iconeration.image.tracer

import com.kaanelloed.iconeration.image.tracer.ImageTracer.ImageData
import kotlin.math.abs
import kotlin.math.floor

class SelectiveBlur {
    companion object {
        // Gaussian kernels for blur
        private val gks = arrayOf(
            doubleArrayOf(0.27901, 0.44198, 0.27901),
            doubleArrayOf(0.135336, 0.228569, 0.272192, 0.228569, 0.135336),
            doubleArrayOf(0.086776, 0.136394, 0.178908, 0.195843, 0.178908, 0.136394, 0.086776),
            doubleArrayOf(
                0.063327,
                0.093095,
                0.122589,
                0.144599,
                0.152781,
                0.144599,
                0.122589,
                0.093095,
                0.063327
            ),
            doubleArrayOf(
                0.049692,
                0.069304,
                0.089767,
                0.107988,
                0.120651,
                0.125194,
                0.120651,
                0.107988,
                0.089767,
                0.069304,
                0.049692
            )
        )

        // Selective Gaussian blur for preprocessing
        fun blur(imgd: ImageData, rad: Float, del: Float): ImageData {
            var d: Int
            var idx: Int
            var racc: Double
            var gacc: Double
            var bacc: Double
            var aacc: Double
            var wacc: Double
            val imgd2 = ImageData(imgd.width, imgd.height, ByteArray(imgd.width * imgd.height * 4))

            // radius and delta limits, this kernel
            var radius = floor(rad).toInt()
            if (radius < 1) {
                return imgd
            }
            if (radius > 5) {
                radius = 5
            }
            var delta = abs(del).toInt()
            if (delta > 1024) {
                delta = 1024
            }
            val thisgk = gks[radius - 1]

            // loop through all pixels, horizontal blur
            for (j in 0 until imgd.height) {
                for (i in 0 until imgd.width) {
                    racc = 0.0
                    gacc = 0.0
                    bacc = 0.0
                    aacc = 0.0
                    wacc = 0.0

                    // gauss kernel loop
                    for (k in -radius until (radius + 1)) {
                        // add weighted color values
                        if (((i + k) > 0) && ((i + k) < imgd.width)){
                            idx = ((j * imgd.width) + i + k) * 4
                            racc += imgd.data[idx] * thisgk[k + radius]
                            gacc += imgd.data[idx + 1] * thisgk[k + radius]
                            bacc += imgd.data[idx + 2] * thisgk[k + radius]
                            aacc += imgd.data[idx + 3] * thisgk[k + radius]
                            wacc += thisgk[k + radius]
                        }
                    }

                    // The new pixel
                    idx = (j * imgd.width + i) * 4
                    imgd2.data[idx] = floor(racc / wacc).toInt().toByte()
                    imgd2.data[idx + 1] = floor(gacc / wacc).toInt().toByte()
                    imgd2.data[idx + 2] = floor(bacc / wacc).toInt().toByte()
                    imgd2.data[idx + 3] = floor(aacc / wacc).toInt().toByte()
                }
            }

            // copying the half blurred imgd2
            val himgd = imgd2.data.clone()

            // loop through all pixels, vertical blur
            for (j in 0 until imgd.height) {
                for (i in 0 until imgd.width) {
                    racc = 0.0
                    gacc = 0.0
                    bacc = 0.0
                    aacc = 0.0
                    wacc = 0.0

                    // gauss kernel loop
                    for (k in -radius until (radius + 1)) {
                        // add weighted color values
                        if (((j + k) > 0) && ((j + k) < imgd.height)){
                            idx = (((j + k) * imgd.width) + i) * 4
                            racc += himgd[idx] * thisgk[k + radius]
                            gacc += himgd[idx + 1] * thisgk[k + radius]
                            bacc += himgd[idx + 2] * thisgk[k + radius]
                            aacc += himgd[idx + 3] * thisgk[k + radius]
                            wacc += thisgk[k + radius]
                        }
                    }

                    // The new pixel
                    idx = (j * imgd.width + i) * 4
                    imgd2.data[idx] = floor(racc / wacc).toInt().toByte()
                    imgd2.data[idx + 1] = floor(gacc / wacc).toInt().toByte()
                    imgd2.data[idx + 2] = floor(bacc / wacc).toInt().toByte()
                    imgd2.data[idx + 3] = floor(aacc / wacc).toInt().toByte()
                }
            }

            // Selective blur: loop through all pixels
            for (j in 0 until imgd.height) {
                for (i in 0 until imgd.width) {
                    idx = ((j * imgd.width) + i) * 4

                    // d is the difference between the blurred and the original pixel
                    d = abs(imgd2.data[idx] - imgd.data[idx]) + abs(imgd2.data[idx + 1] - imgd.data[idx + 1]) +
                            abs(imgd2.data[idx + 2] - imgd.data[idx + 2]) + abs(imgd2.data[idx + 3] - imgd.data[idx + 3])

                    // selective blur: if d>delta, put the original pixel back
                    if(d > delta){
                        imgd2.data[idx] = imgd.data[idx]
                        imgd2.data[idx + 1] = imgd.data[idx + 1]
                        imgd2.data[idx + 2] = imgd.data[idx + 2]
                        imgd2.data[idx + 3] = imgd.data[idx + 3]
                    }
                }
            }

            return imgd2
        }
    }
}