package com.kaanelloed.iconeration.image.tracer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.kaanelloed.iconeration.image.tracer.ImageTracer.IndexedImage
import com.kaanelloed.iconeration.image.tracer.ImageTracer.TracingOptions
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp
import kotlin.math.pow
import kotlin.math.round

class VectorUtils {
    companion object {
        fun getVector(ii: IndexedImage, options: TracingOptions): ImageVector {
            val w = ii.width * options.scale
            val h = ii.height * options.scale

            val builder = ImageVector.Builder(defaultWidth = w.dp, defaultHeight = h.dp, viewportWidth = w, viewportHeight = h)

            // creating Z-index
            val zIndex = mutableMapOf<Double, IntArray>()
            var label: Double

            // Layer loop
            for (k in 0 until ii.layers!!.size) {
                // Path loop
                for (pcnt in 0 until ii.layers!![k].size) {
                    // Label (Z-index key) is the startpoint of the path, linearized
                    label = (ii.layers!![k][pcnt][0][2] * w) + ii.layers!![k][pcnt][0][1]
                    // Creating new list if required
                    if (!zIndex.containsKey(label)) {
                        zIndex[label] = IntArray(2)
                    }
                    // Adding layer and path number to list
                    zIndex[label]?.set(0, k)
                    zIndex[label]?.set(1, pcnt)
                }
            }

            // Sorting Z-index is required

            // Drawing
            // Z-index loop
            for (entry in zIndex.toSortedMap()) {
                val entry0 = entry.value[0]
                val entry1 = entry.value[1]

                vectorPath(
                    builder,
                    ii.layers!![entry0][entry1],
                    ii.palette[entry0],
                    options
                )
            }

            return builder.build()
        }

        private fun vectorPath(
            builder: ImageVector.Builder,
            segments: ArrayList<DoubleArray>,
            colorArray: ByteArray,
            options: TracingOptions
        ) {
            val scale = options.scale
            val lcpr = options.lcpr
            val qcpr = options.qcpr
            val roundcoords = options.roundcoords

            val pathNodes = mutableListOf<PathNode>()
            pathNodes.add(PathNode.MoveTo(
                (segments[0][1] * scale).toFloat(),
                (segments[0][2] * scale).toFloat()
            ))

            if (roundcoords == -1F) {
                for (pcnt in 0 until segments.size) {
                    if (segments[pcnt][0] == 1.0) {
                        pathNodes.add(PathNode.LineTo(
                            (segments[pcnt][3] * scale).toFloat(),
                            (segments[pcnt][4] * scale).toFloat()
                        ))
                    } else {
                        pathNodes.add(PathNode.QuadTo(
                            (segments[pcnt][3] * scale).toFloat(),
                            (segments[pcnt][4] * scale).toFloat(),
                            (segments[pcnt][5] * scale).toFloat(),
                            (segments[pcnt][6] * scale).toFloat()
                        ))
                    }
                }
            } else {
                for (pcnt in 0 until segments.size) {
                    if (segments[pcnt][0] == 1.0) {
                        pathNodes.add(PathNode.LineTo(
                            roundToDec(segments[pcnt][3] * scale, roundcoords).toFloat(),
                            roundToDec(segments[pcnt][4] * scale, roundcoords).toFloat()
                        ))
                    } else {
                        pathNodes.add(PathNode.QuadTo(
                            roundToDec(segments[pcnt][3] * scale, roundcoords).toFloat(),
                            roundToDec(segments[pcnt][4] * scale, roundcoords).toFloat(),
                            roundToDec(segments[pcnt][5] * scale, roundcoords).toFloat(),
                            roundToDec(segments[pcnt][6] * scale, roundcoords).toFloat()
                        ))
                    }
                }
            }

            pathNodes.add(PathNode.Close)

            val red = colorArray[0] + 128
            val green = colorArray[1] + 128
            val blue = colorArray[2] + 128
            val alpha = (colorArray[3] + 128) / 255.0f

            val color = SolidColor(Color(red, green, blue))

            builder.addPath(pathNodes, fill = color, fillAlpha = alpha, stroke = color, strokeAlpha = alpha, strokeLineWidth = 1F)

            // Rendering control points
            /*for (pcnt in 0 until segments.size) {
                if ((lcpr > 0) && (segments[pcnt][0] == 1.0)) {
                    sb.append( "<circle cx=\"").append(segments[pcnt][3]*scale).append("\" cy=\"").append(
                        segments[pcnt][4]*scale).append("\" r=\"").append(lcpr).append("\" fill=\"white\" stroke-width=\"").append(lcpr*0.2).append("\" stroke=\"black\" />")
                }
                if ((qcpr > 0) && (segments[pcnt][0] == 2.0)) {
                    sb.append( "<circle cx=\"").append(segments[pcnt][3]*scale).append("\" cy=\"").append(
                        segments[pcnt][4]*scale).append("\" r=\"").append(qcpr).append("\" fill=\"cyan\" stroke-width=\"").append(qcpr*0.2).append("\" stroke=\"black\" />")
                    sb.append( "<circle cx=\"").append(segments[pcnt][5]*scale).append("\" cy=\"").append(
                        segments[pcnt][6]*scale).append("\" r=\"").append(qcpr).append("\" fill=\"white\" stroke-width=\"").append(qcpr*0.2).append("\" stroke=\"black\" />")
                    sb.append( "<line x1=\"").append(segments[pcnt][1]*scale).append("\" y1=\"").append(
                        segments[pcnt][2]*scale).append("\" x2=\"").append(segments[pcnt][3]*scale).append("\" y2=\"").append(
                        segments[pcnt][4]*scale).append("\" stroke-width=\"").append(qcpr*0.2).append("\" stroke=\"cyan\" />")
                    sb.append( "<line x1=\"").append(segments[pcnt][3]*scale).append("\" y1=\"").append(
                        segments[pcnt][4]*scale).append("\" x2=\"").append(segments[pcnt][5]*scale).append("\" y2=\"").append(
                        segments[pcnt][6]*scale).append("\" stroke-width=\"").append(qcpr*0.2).append("\" stroke=\"cyan\" />")
                }
            }*/
        }

        private fun roundToDec(value: Double, places: Float): Double {
            return round(value * 10F.pow(places)) / 10F.pow(places)
        }
    }
}