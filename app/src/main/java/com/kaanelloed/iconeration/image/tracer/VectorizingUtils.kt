package com.kaanelloed.iconeration.image.tracer

import com.kaanelloed.iconeration.image.tracer.ImageTracer.ImageData
import com.kaanelloed.iconeration.image.tracer.ImageTracer.IndexedImage
import com.kaanelloed.iconeration.image.tracer.ImageTracer.TracingOptions
import kotlin.math.abs

class VectorizingUtils {
    companion object {
        ////////////////////////////////////////////////////////////
        //
        //  Vectorizing functions
        //
        ////////////////////////////////////////////////////////////

        // 1. Color quantization repeated "cycles" times, based on K-means clustering
        // https://en.wikipedia.org/wiki/Color_quantization    https://en.wikipedia.org/wiki/K-means_clustering
        fun colorQuantization(imgd: ImageData, palette: Array<ByteArray>, options: TracingOptions): IndexedImage {
            // Selective Gaussian blur preprocessing
            if (options.blurRadius > 0F) {
                SelectiveBlur.blur(imgd, options.blurRadius, options.blurDelta)
            }

            val cycles = options.colorquantcycles
            // Creating indexed color array arr which has a boundary filled with -1 in every direction
            val arr = Array(imgd.height + 2) {
                IntArray(
                    imgd.width + 2
                )
            }
            for (j in 0 until imgd.height + 2) {
                arr[j][0] = -1
                arr[j][imgd.width + 1] = -1
            }
            for (i in 0 until imgd.width + 2) {
                arr[0][i] = -1
                arr[imgd.height + 1][i] = -1
            }

            var idx: Int
            var cd: Int
            var cdl: Int
            var ci: Int
            var c1: Int
            var c2: Int
            var c3: Int
            var c4: Int

            val originalPaletteBackup = palette
            val paletteacc = Array(palette.size) {
                LongArray(
                    5
                )
            }

            // Repeat clustering step "cycles" times
            for (cnt in 0 until cycles) {
                // Average colors from the second iteration
                if (cnt > 0) {
                    // averaging paletteacc for palette
                    //float ratio;
                    for (k in palette.indices) {
                        // averaging
                        if (paletteacc[k][3] > 0) {
                            palette[k][0] = (-128 + (paletteacc[k][0] / paletteacc[k][4])).toByte()
                            palette[k][1] = (-128 + (paletteacc[k][1] / paletteacc[k][4])).toByte()
                            palette[k][2] = (-128 + (paletteacc[k][2] / paletteacc[k][4])).toByte()
                            palette[k][3] = (-128 + (paletteacc[k][3] / paletteacc[k][4])).toByte()
                        }
                        //ratio = (float)( (double)(paletteacc[k][4]) / (double)(imgd.width*imgd.height) );

                        /*// Randomizing a color, if there are too few pixels and there will be a new cycle
                        if( (ratio<minratio) && (cnt<(cycles-1)) ){
                            palette[k][0] = (byte) (-128+Math.floor(Math.random()*255));
                            palette[k][1] = (byte) (-128+Math.floor(Math.random()*255));
                            palette[k][2] = (byte) (-128+Math.floor(Math.random()*255));
                            palette[k][3] = (byte) (-128+Math.floor(Math.random()*255));
                        }*/
                    }
                }

                // Resetting palette accumulator for averaging
                for (i in palette.indices) {
                    paletteacc[i][0] = 0
                    paletteacc[i][1] = 0
                    paletteacc[i][2] = 0
                    paletteacc[i][3] = 0
                    paletteacc[i][4] = 0
                }

                // loop through all pixels
                for (j in 0 until imgd.height) {
                    for (i in 0 until imgd.width) {
                        idx = ((j * imgd.width) + i) * 4

                        // find closest color from original_palette_backup by measuring (rectilinear)
                        // color distance between this pixel and all palette colors
                        cdl = 256 + 256 + 256 + 256
                        ci = 0
                        for (k in originalPaletteBackup.indices) {
                            // In my experience, https://en.wikipedia.org/wiki/Rectilinear_distance works better than https://en.wikipedia.org/wiki/Euclidean_distance
                            c1 = abs(originalPaletteBackup[k][0]-imgd.data[idx])
                            c2 = abs(originalPaletteBackup[k][1]-imgd.data[idx + 1])
                            c3 = abs(originalPaletteBackup[k][2]-imgd.data[idx + 2])
                            c4 = abs(originalPaletteBackup[k][3]-imgd.data[idx + 3])
                            cd = c1 + c2 + c3 + (c4 * 4) // weighted alpha seems to help images with transparency

                            // Remember this color if this is the closest yet
                            if (cd < cdl) {
                                cdl = cd
                                ci = k
                            }
                        }

                        // add to palettacc
                        paletteacc[ci][0] += 128L + imgd.data[idx]
                        paletteacc[ci][1] += 128L + imgd.data[idx + 1]
                        paletteacc[ci][2] += 128L + imgd.data[idx + 2]
                        paletteacc[ci][3] += 128L + imgd.data[idx + 3]
                        paletteacc[ci][4]++

                        arr[j + 1][i + 1] = ci
                    }
                }
            }

            return IndexedImage(arr, originalPaletteBackup)
        }

        // 2. Layer separation and edge detection
        // Edge node types ( ▓:light or 1; ░:dark or 0 )
        // 12  ░░  ▓░  ░▓  ▓▓  ░░  ▓░  ░▓  ▓▓  ░░  ▓░  ░▓  ▓▓  ░░  ▓░  ░▓  ▓▓
        // 48  ░░  ░░  ░░  ░░  ░▓  ░▓  ░▓  ░▓  ▓░  ▓░  ▓░  ▓░  ▓▓  ▓▓  ▓▓  ▓▓
        //     0   1   2   3   4   5   6   7   8   9   10  11  12  13  14  15
        //
        fun layering(ii: IndexedImage): Array<Array<IntArray>> {
            // Creating layers for each indexed color in arr
            var value: Int
            val aw: Int = ii.array[0].size
            val ah: Int = ii.array.size
            var n1: Int
            var n2: Int
            var n3: Int
            var n4: Int
            var n5: Int
            var n6: Int
            var n7: Int
            var n8: Int

            val layers = Array(ii.palette.size) {
                Array(ah) {
                    IntArray(aw)
                }
            }

            // Looping through all pixels and calculating edge node type
            for (j in 1 until (ah - 1)) {
                for (i in 1 until (aw - 1)) {
                    // This pixel's indexed color
                    value = ii.array[j][i]

                    // Are neighbor pixel colors the same?
                    n1 = if (ii.array[j - 1][i - 1] == value) 1 else 0
                    n2 = if (ii.array[j - 1][i] == value) 1 else 0
                    n3 = if (ii.array[j - 1][i + 1] == value) 1 else 0
                    n4 = if (ii.array[j][i - 1] == value) 1 else 0
                    n5 = if (ii.array[j][i + 1] == value) 1 else 0
                    n6 = if (ii.array[j + 1][i - 1] == value) 1 else 0
                    n7 = if (ii.array[j + 1][i] == value) 1 else 0
                    n8 = if (ii.array[j + 1][i + 1] == value) 1 else 0

                    // this pixel"s type and looking back on previous pixels
                    layers[value][j + 1][i + 1] = 1 + n5 * 2 + n8 * 4 + n7 * 8
                    if (n4 == 0) {
                        layers[value][j + 1][i] = 0 + 2 + n7 * 4 + n6 * 8
                    }
                    if (n2 == 0) {
                        layers[value][j][i + 1] = 0 + n3 * 2 + n5 * 4 + 8
                    }
                    if (n1 == 0) {
                        layers[value][j][i] = 0 + n2 * 2 + 4 + n4 * 8
                    }
                }
            }

            return layers;
        }

        // Lookup tables for pathscan
        private var pathscan_dir_lookup = byteArrayOf(0, 0, 3, 0, 1, 0, 3, 0, 0, 3, 3, 1, 0, 3, 0, 0)
        private var pathscan_holepath_lookup = booleanArrayOf(
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            true,
            false,
            false,
            false,
            true,
            false,
            true,
            true,
            false
        )
        // pathscan_combined_lookup[ arr[py][px] ][ dir ] = [nextarrpypx, nextdir, deltapx, deltapy];
        private var pathscan_combined_lookup = arrayOf(
            arrayOf(// arr[py][px]==0 is invalid
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(-1, -1, -1, -1)
            ),
            arrayOf(
                byteArrayOf(0, 1, 0, -1),
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(0, 2, -1, 0)
            ),
            arrayOf(
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(0, 1, 0, -1),
                byteArrayOf(0, 0, 1, 0)
            ),
            arrayOf(
                byteArrayOf(0, 0, 1, 0),
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(0, 2, -1, 0),
                byteArrayOf(-1, -1, -1, -1)
            ),
            arrayOf(
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(0, 0, 1, 0),
                byteArrayOf(0, 3, 0, 1),
                byteArrayOf(-1, -1, -1, -1)
            ),
            arrayOf(
                byteArrayOf(13, 3, 0, 1),
                byteArrayOf(13, 2, -1, 0),
                byteArrayOf(7, 1, 0, -1),
                byteArrayOf(7, 0, 1, 0)
            ),
            arrayOf(
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(0, 1, 0, -1),
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(0, 3, 0, 1)
            ),
            arrayOf(
                byteArrayOf(0, 3, 0, 1),
                byteArrayOf(0, 2, -1, 0),
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(-1, -1, -1, -1)
            ),
            arrayOf(
                byteArrayOf(0, 3, 0, 1),
                byteArrayOf(0, 2, -1, 0),
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(-1, -1, -1, -1)
            ),
            arrayOf(
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(0, 1, 0, -1),
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(0, 3, 0, 1)
            ),
            arrayOf(
                byteArrayOf(11, 1, 0, -1),
                byteArrayOf(14, 0, 1, 0),
                byteArrayOf(14, 3, 0, 1),
                byteArrayOf(11, 2, -1, 0)
            ),
            arrayOf(
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(0, 0, 1, 0),
                byteArrayOf(0, 3, 0, 1),
                byteArrayOf(-1, -1, -1, -1)
            ),
            arrayOf(
                byteArrayOf(0, 0, 1, 0),
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(0, 2, -1, 0),
                byteArrayOf(-1, -1, -1, -1)
            ),
            arrayOf(
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(0, 1, 0, -1),
                byteArrayOf(0, 0, 1, 0)
            ),
            arrayOf(
                byteArrayOf(0, 1, 0, -1),
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(0, 2, -1, 0)
            ),
            arrayOf(
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(-1, -1, -1, -1),
                byteArrayOf(-1, -1, -1, -1)
            )// arr[py][px]==15 is invalid
        )

        // 3. Walking through an edge node array, discarding edge node types 0 and 15 and creating paths from the rest.
        // Walk directions (dir): 0 > ; 1 ^ ; 2 < ; 3 v
        // Edge node types ( ▓:light or 1; ░:dark or 0 )
        // ░░  ▓░  ░▓  ▓▓  ░░  ▓░  ░▓  ▓▓  ░░  ▓░  ░▓  ▓▓  ░░  ▓░  ░▓  ▓▓
        // ░░  ░░  ░░  ░░  ░▓  ░▓  ░▓  ░▓  ▓░  ▓░  ▓░  ▓░  ▓▓  ▓▓  ▓▓  ▓▓
        // 0   1   2   3   4   5   6   7   8   9   10  11  12  13  14  15
        //
        private fun pathScan(arr: Array<IntArray>, pathomit: Float): ArrayList<ArrayList<IntArray>> {
            val paths = ArrayList<ArrayList<IntArray>>()
            var thispath: ArrayList<IntArray>
            var px: Int
            var py: Int
            val w: Int = arr[0].size
            val h: Int = arr.size
            var dir: Int
            var pathfinished: Boolean
            var holepath: Boolean
            var lookuprow: ByteArray

            for (j in 0 until h) {
                for (i in 0 until w) {
                    if (arr[j][i] != 0 && arr[j][i] != 15) {
                        // Init
                        px = i
                        py = j
                        paths.add(ArrayList())
                        thispath = paths[paths.size - 1]
                        pathfinished = false

                        // fill paths will be drawn, but hole paths are also required to remove unnecessary edge nodes
                        dir = pathscan_dir_lookup[arr[py][px]].toInt()
                        holepath = pathscan_holepath_lookup[arr[py][px]]

                        // Path points loop
                        while (!pathfinished) {
                            // New path point
                            thispath.add(IntArray(3))
                            thispath[thispath.size - 1][0] = px - 1
                            thispath[thispath.size - 1][1] = py - 1
                            thispath[thispath.size - 1][2] = arr[py][px]

                            // Next: look up the replacement, direction and coordinate changes = clear this cell, turn if required, walk forward
                            lookuprow = pathscan_combined_lookup[arr[py][px]][dir]
                            arr[py][px] = lookuprow[0].toInt()
                            dir = lookuprow[1].toInt()
                            px += lookuprow[2]
                            py += lookuprow[3]

                            // Close path
                            if (px - 1 == thispath[0][0] && py - 1 == thispath[0][1]) {
                                pathfinished = true
                                // Discarding 'hole' type paths and paths shorter than pathomit
                                if (holepath || thispath.size < pathomit) {
                                    paths.remove(thispath)
                                }
                            }
                        }
                    }
                }
            }

            return paths
        }

        // 3. Batch pathscan
        fun batchpathscan(layers: Array<Array<IntArray>>, pathomit: Float): ArrayList<ArrayList<ArrayList<IntArray>>> {
            val bpaths = ArrayList<ArrayList<ArrayList<IntArray>>>()
            for (layer in layers) {
                bpaths.add(pathScan(layer, pathomit))
            }
            return bpaths
        }

        // 4. interpolating between path points for nodes with 8 directions ( East, SouthEast, S, SW, W, NW, N, NE )
        private fun internodes(paths: ArrayList<ArrayList<IntArray>>): ArrayList<ArrayList<DoubleArray>> {
            val ins = ArrayList<ArrayList<DoubleArray>>()
            var thisinp: ArrayList<DoubleArray>
            var thispoint: DoubleArray
            val nextpoint = DoubleArray(2)
            var pp1: IntArray
            var pp2: IntArray
            var pp3: IntArray
            var palen: Int
            var nextidx: Int
            var nextidx2: Int

            // paths loop
            for (pacnt in 0 until paths.size) {
                ins.add(ArrayList())
                thisinp = ins[ins.size - 1]
                palen = paths[pacnt].size

                // pathpoints loop
                for (pcnt in  0 until palen) {
                    // interpolate between two path points
                    nextidx = (pcnt+1)%palen
                    nextidx2 = (pcnt+2)%palen
                    thisinp.add(DoubleArray(3))
                    thispoint = thisinp[thisinp.size - 1]
                    pp1 = paths[pacnt][pcnt]
                    pp2 = paths[pacnt][nextidx]
                    pp3 = paths[pacnt][nextidx2]
                    thispoint[0] = (pp1[0]+pp2[0]) / 2.0
                    thispoint[1] = (pp1[1]+pp2[1]) / 2.0
                    nextpoint[0] = (pp2[0]+pp3[0]) / 2.0
                    nextpoint[1] = (pp2[1]+pp3[1]) / 2.0

                    // line segment direction to the next point
                    if (thispoint[0] < nextpoint[0]) {
                        if (thispoint[1] < nextpoint[1]) {
                            thispoint[2] = 1.0 // SouthEast
                        } else if (thispoint[1] > nextpoint[1]) {
                            thispoint[2] = 7.0 // NE
                        } else {
                            thispoint[2] = 0.0 // E
                        }
                    } else if (thispoint[0] > nextpoint[0]) {
                        if (thispoint[1] < nextpoint[1]) {
                            thispoint[2] = 3.0 // SW
                        } else if (thispoint[1] > nextpoint[1]) {
                            thispoint[2] = 5.0 // NW
                        } else {
                            thispoint[2] = 4.0 // W
                        }
                    } else{
                        if (thispoint[1] < nextpoint[1]) {
                            thispoint[2] = 2.0 // S
                        } else if (thispoint[1] > nextpoint[1]) {
                            thispoint[2] = 6.0 // N
                        } else {
                            thispoint[2] = 8.0 // center, this should not happen
                        }
                    }
                }
            }

            return ins
        }

        // 4. Batch interpolation
        fun batchInternodes(bpaths: ArrayList<ArrayList<ArrayList<IntArray>>>): ArrayList<ArrayList<ArrayList<DoubleArray>>> {
            val binternodes = ArrayList<ArrayList<ArrayList<DoubleArray>>>()
            for (k in 0 until bpaths.size) {
                binternodes.add(internodes(bpaths[k]))
            }

            return binternodes
        }

        // 5. tracepath() : recursively trying to fit straight and quadratic spline segments on the 8 direction internode path

        // 5.1. Find sequences of points with only 2 segment types
        // 5.2. Fit a straight line on the sequence
        // 5.3. If the straight line fails (an error>ltreshold), find the point with the biggest error
        // 5.4. Fit a quadratic spline through errorpoint (project this to get controlpoint), then measure errors on every point in the sequence
        // 5.5. If the spline fails (an error>qtreshold), find the point with the biggest error, set splitpoint = (fitting point + errorpoint)/2
        // 5.6. Split sequence and recursively apply 5.2. - 5.7. to startpoint-splitpoint and splitpoint-endpoint sequences
        // 5.7. TODO? If splitpoint-endpoint is a spline, try to add new points from the next sequence

        // This returns an SVG Path segment as a double[7] where
        // segment[0] ==1.0 linear  ==2.0 quadratic interpolation
        // segment[1] , segment[2] : x1 , y1
        // segment[3] , segment[4] : x2 , y2 ; middle point of Q curve, endpoint of L line
        // segment[5] , segment[6] : x3 , y3 for Q curve, should be 0.0 , 0.0 for L line
        //
        // path type is discarded, no check for path.size < 3 , which should not happen
        private fun tracePath(path: ArrayList<DoubleArray>, ltreshold: Float, qtreshold: Float): ArrayList<DoubleArray> {
            var pcnt = 0
            var seqend: Int
            var segtype1: Double
            var segtype2: Double
            val smp = ArrayList<DoubleArray>()
            //Double [] thissegment;
            val pathlength = path.size

            while (pcnt < pathlength) {
                // 5.1. Find sequences of points with only 2 segment types
                segtype1 = path[pcnt][2]
                segtype2 = -1.0
                seqend = pcnt + 1

                while(
                    ((path[seqend][2] == segtype1) || (path[seqend][2] == segtype2) || (segtype2 == -1.0))
                    && (seqend < (pathlength - 1))) {
                    if ((path[seqend][2] != segtype1) && (segtype2 == -1.0)) {
                        segtype2 = path[seqend][2]
                    }
                    seqend++
                }

                if (seqend == (pathlength - 1)) {
                    seqend = 0
                }

                // 5.2. - 5.6. Split sequence and recursively apply 5.2. - 5.6. to startpoint-splitpoint and splitpoint-endpoint sequences
                smp.addAll(fitseq(path, ltreshold, qtreshold, pcnt, seqend))
                // 5.7. TODO? If splitpoint-endpoint is a spline, try to add new points from the next sequence

                // forward pcnt;
                pcnt = if (seqend > 0) {
                    seqend
                } else {
                    pathlength
                }
            }

            return smp
        }

        // 5.2. - 5.6. recursively fitting a straight or quadratic line segment on this sequence of path nodes,
        // called from tracepath()
        private fun fitseq(path: ArrayList<DoubleArray>, ltreshold: Float, qtreshold: Float, seqstart: Int, seqend: Int): ArrayList<DoubleArray> {
            var segment = ArrayList<DoubleArray>()
            val thissegment: DoubleArray
            val pathlength = path.size

            if ((seqend > pathlength) || (seqend < 0)) {
                return segment
            }

            var errorpoint = seqstart
            var curvepass = true
            var px: Double
            var py: Double
            var dist2: Double
            var errorval = 0.0
            var tl = (seqend - seqstart).toDouble()
            if (tl < 0) {
                tl += pathlength
            }
            val vx: Double = (path[seqend][0] - path[seqstart][0]) / tl
            val vy: Double = (path[seqend][1] - path[seqstart][1]) / tl

            // 5.2. Fit a straight line on the sequence
            var pcnt = (seqstart + 1) % pathlength
            var pl: Double
            while (pcnt != seqend) {
                pl = (pcnt - seqstart).toDouble()
                if (pl < 0) {
                    pl += pathlength.toDouble()
                }
                px = path[seqstart][0] + vx * pl
                py = path[seqstart][1] + vy * pl
                dist2 = (path[pcnt][0] - px) * (path[pcnt][0] - px) + (path[pcnt][1] - py) * (path[pcnt][1] - py)
                if (dist2 > ltreshold) {
                    curvepass = false
                }
                if (dist2 > errorval) {
                    errorpoint = pcnt
                    errorval = dist2
                }
                pcnt = (pcnt + 1) % pathlength
            }

            // return straight line if fits
            if (curvepass) {
                segment.add(DoubleArray(7))
                thissegment = segment[segment.size - 1]
                thissegment[0] = 1.0
                thissegment[1] = path[seqstart][0]
                thissegment[2] = path[seqstart][1]
                thissegment[3] = path[seqend][0]
                thissegment[4] = path[seqend][1]
                thissegment[5] = 0.0
                thissegment[6] = 0.0
                return segment
            }

            // 5.3. If the straight line fails (an error>ltreshold), find the point with the biggest error
            val fitpoint: Int = errorpoint
            curvepass = true
            errorval = 0.0

            // 5.4. Fit a quadratic spline through this point, measure errors on every point in the sequence
            // helpers and projecting to get control point
            var t = (fitpoint - seqstart) / tl
            var t1 = (1.0 - t) * (1.0 - t)
            var t2 = 2.0 * (1.0 - t) * t
            var t3 = t * t
            val cpx: Double = (t1 * path[seqstart][0] + t3 * path[seqend][0] - path[fitpoint][0]) / -t2
            val cpy: Double = (t1 * path[seqstart][1] + t3 * path[seqend][1] - path[fitpoint][1]) / -t2

            // Check every point
            pcnt = seqstart + 1
            while (pcnt != seqend) {
                t = (pcnt - seqstart) / tl
                t1 = (1.0 - t) * (1.0 - t)
                t2 = 2.0 * (1.0 - t) * t
                t3 = t * t

                px = (t1 * path[seqstart][0]) + (t2 * cpx) + (t3 * path[seqend][0])
                py = (t1 * path[seqstart][1]) + (t2 * cpy) + (t3 * path[seqend][1])

                dist2 = ((path[pcnt][0] - px) * (path[pcnt][0] - px)) + ((path[pcnt][1] - py) * (path[pcnt][1] - py))
                if (dist2 > qtreshold) {
                    curvepass = false
                }
                if (dist2 > errorval) {
                    errorpoint = pcnt
                    errorval = dist2
                }
                pcnt = (pcnt + 1) % pathlength;
            }

            // return spline if fits
            if (curvepass) {
                segment.add(DoubleArray(7))
                thissegment = segment[segment.size - 1]
                thissegment[0] = 2.0
                thissegment[1] = path[seqstart][0]
                thissegment[2] = path[seqstart][1]
                thissegment[3] = cpx
                thissegment[4] = cpy
                thissegment[5] = path[seqend][0]
                thissegment[6] = path[seqend][1]
                return segment
            }

            // 5.5. If the spline fails (an error>qtreshold), find the point with the biggest error,
            // set splitpoint = (fitting point + errorpoint)/2
            val splitpoint = (fitpoint + errorpoint) / 2

            // 5.6. Split sequence and recursively apply 5.2. - 5.6. to startpoint-splitpoint and splitpoint-endpoint sequences
            segment = fitseq(path, ltreshold, qtreshold, seqstart, splitpoint)
            segment.addAll(fitseq(path, ltreshold, qtreshold, splitpoint, seqend))
            return segment
        }

        // 5. Batch tracing paths
        private fun batchtracepaths(internodepaths: ArrayList<ArrayList<DoubleArray>>, ltres: Float, qtres: Float): ArrayList<ArrayList<DoubleArray>> {
            val btracedPaths = ArrayList<ArrayList<DoubleArray>>()
            for (k in 0 until internodepaths.size) {
                btracedPaths.add(tracePath(internodepaths[k], ltres, qtres))
            }
            return btracedPaths
        }

        // 5. Batch tracing layers
        fun batchtracelayers(binternodes: ArrayList<ArrayList<ArrayList<DoubleArray>>>, ltres: Float, qtres: Float): ArrayList<ArrayList<ArrayList<DoubleArray>>> {
            val btbis = ArrayList<ArrayList<ArrayList<DoubleArray>>>()
            for (k in 0 until binternodes.size) {
                btbis.add(batchtracepaths(binternodes[k], ltres, qtres))
            }
            return btbis
        }
    }
}