package com.kaanelloed.iconeration.image.tracer

import com.kaanelloed.iconeration.image.tracer.ImageTracer.IndexedImage
import com.kaanelloed.iconeration.image.tracer.ImageTracer.TracingOptions
import kotlin.math.pow
import kotlin.math.round

class SVGUtils {
    ////////////////////////////////////////////////////////////
    //
    //  SVG Drawing functions
    //
    ////////////////////////////////////////////////////////////

    companion object {
        fun getSVGString(ii: IndexedImage, options: TracingOptions): String {
            val w = ii.width * options.scale
            val h = ii.height * options.scale

            val viewBoxOrViewPort = if (options.viewBox) "viewBox=\"0 0 $w $h\"" else "width=\"$w\" height=\"$h\""
            val svgstr = StringBuilder("<svg $viewBoxOrViewPort version=\"1.1\" xmlns=\"http://www.w3.org/2000/svg\">")

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

                svgPathString(svgstr
                    , ii.layers!![entry0][entry1]
                    , toSvgColorStr(ii.palette[entry0])
                    , options)
            }

            return svgstr.toString()
        }

        private fun svgPathString(sb: StringBuilder, segments: ArrayList<DoubleArray>, colorStr: String, options: TracingOptions) {
            val scale = options.scale
            val lcpr = options.lcpr
            val qcpr = options.qcpr
            val roundcoords = options.roundcoords

            sb.append("<path ").append(colorStr).append("d=\"").append("M ")
                .append(segments[0][1] * scale).append(" ").append(
                segments[0][2] * scale
            ).append(" ")

            if (roundcoords == -1F) {
                for (pcnt in 0 until segments.size) {
                    if (segments[pcnt][0] == 1.0) {
                        sb.append("L ").append(segments[pcnt][3] * scale).append(" ")
                            .append(segments[pcnt][4] * scale).append(" ")
                    } else {
                        sb.append("Q ").append(segments[pcnt][3] * scale).append(" ")
                            .append(segments[pcnt][4] * scale).append(" ").append(
                            segments[pcnt][5] * scale
                        ).append(" ").append(segments[pcnt][6] * scale).append(" ")
                    }
                }
            } else {
                for (pcnt in 0 until segments.size) {
                    if (segments[pcnt][0] == 1.0) {
                        sb.append("L ").append(roundToDec((segments[pcnt][3] * scale), roundcoords))
                            .append(" ")
                            .append(roundToDec((segments[pcnt][4] * scale), roundcoords))
                            .append(" ")
                    } else {
                        sb.append("Q ").append(roundToDec((segments[pcnt][3] * scale), roundcoords))
                            .append(" ")
                            .append(roundToDec((segments[pcnt][4] * scale), roundcoords))
                            .append(" ")
                            .append(roundToDec((segments[pcnt][5] * scale), roundcoords))
                            .append(" ")
                            .append(roundToDec((segments[pcnt][6] * scale), roundcoords))
                            .append(" ")
                    }
                }
            }

            sb.append("Z\" />")

            // Rendering control points
            for (pcnt in 0 until segments.size) {
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
            }
        }

        private fun roundToDec(value: Float, places: Float): Float {
            return round(value * 10F.pow(places)) / 10F.pow(places)
        }

        private fun roundToDec(value: Double, places: Float): Double {
            return round(value * 10F.pow(places)) / 10F.pow(places)
        }

        private fun toSvgColorStr(c: ByteArray): String {
            val red = c[0] + 128
            val green = c[1] + 128
            val blue = c[2] + 128
            val alpha = (c[3] + 128) / 255.0

            return "fill=\"rgb($red,$green,$blue)\" stroke=\"rgb($red,$green,$blue)\" stroke-width=\"1\" opacity=\"$alpha\" "
        }
    }
}