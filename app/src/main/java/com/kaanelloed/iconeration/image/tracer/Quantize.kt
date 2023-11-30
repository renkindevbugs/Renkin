package com.kaanelloed.iconeration.image.tracer

/**
* @(#)Quantize.java    0.90 9/19/00 Adam Doppelt
*/

/**
 * An efficient color quantization algorithm, adapted from the C++
 * implementation quantize.c in [ImageMagick](http://www.imagemagick.org/). The pixels for
 * an image are placed into an oct tree. The oct tree is reduced in
 * size, and the pixels from the original image are reassigned to the
 * nodes in the reduced tree.<p>
 *
 * Here is the copyright notice from ImageMagick:
 *
 * <pre>
%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
%  Permission is hereby granted, free of charge, to any person obtaining a    %
%  copy of this software and associated documentation files ("ImageMagick"),  %
%  to deal in ImageMagick without restriction, including without limitation   %
%  the rights to use, copy, modify, merge, publish, distribute, sublicense,   %
%  and/or sell copies of ImageMagick, and to permit persons to whom the       %
%  ImageMagick is furnished to do so, subject to the following conditions:    %
%                                                                             %
%  The above copyright notice and this permission notice shall be included in %
%  all copies or substantial portions of ImageMagick.                         %
%                                                                             %
%  The software is provided "as is", without warranty of any kind, express or %
%  implied, including but not limited to the warranties of merchantability,   %
%  fitness for a particular purpose and noninfringement.  In no event shall   %
%  E. I. du Pont de Nemours and Company be liable for any claim, damages or   %
%  other liability, whether in an action of contract, tort or otherwise,      %
%  arising from, out of or in connection with ImageMagick or the use or other %
%  dealings in ImageMagick.                                                   %
%                                                                             %
%  Except as contained in this notice, the name of the E. I. du Pont de       %
%  Nemours and Company shall not be used in advertising or otherwise to       %
%  promote the sale, use or other dealings in ImageMagick without prior       %
%  written authorization from the E. I. du Pont de Nemours and Company.       %
%                                                                             %
%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
</pre>
 *
 *
 * @version 0.90 19 Sep 2000
 * @author <a href="http://www.gurge.com/amd/">Adam Doppelt</a>
 */

class Quantize {
    /**
    %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
    %                                                                             %
    %                                                                             %
    %                                                                             %
    %           QQQ   U   U   AAA   N   N  TTTTT  IIIII   ZZZZZ  EEEEE            %
    %          Q   Q  U   U  A   A  NN  N    T      I        ZZ  E                %
    %          Q   Q  U   U  AAAAA  N N N    T      I      ZZZ   EEEEE            %
    %          Q  QQ  U   U  A   A  N  NN    T      I     ZZ     E                %
    %           QQQQ   UUU   A   A  N   N    T    IIIII   ZZZZZ  EEEEE            %
    %                                                                             %
    %                                                                             %
    %              Reduce the Number of Unique Colors in an Image                 %
    %                                                                             %
    %                                                                             %
    %                           Software Design                                   %
    %                             John Cristy                                     %
    %                              July 1992                                      %
    %                                                                             %
    %                                                                             %
    %  Copyright 1998 E. I. du Pont de Nemours and Company                        %
    %                                                                             %
    %  Permission is hereby granted, free of charge, to any person obtaining a    %
    %  copy of this software and associated documentation files ("ImageMagick"),  %
    %  to deal in ImageMagick without restriction, including without limitation   %
    %  the rights to use, copy, modify, merge, publish, distribute, sublicense,   %
    %  and/or sell copies of ImageMagick, and to permit persons to whom the       %
    %  ImageMagick is furnished to do so, subject to the following conditions:    %
    %                                                                             %
    %  The above copyright notice and this permission notice shall be included in %
    %  all copies or substantial portions of ImageMagick.                         %
    %                                                                             %
    %  The software is provided "as is", without warranty of any kind, express or %
    %  implied, including but not limited to the warranties of merchantability,   %
    %  fitness for a particular purpose and noninfringement.  In no event shall   %
    %  E. I. du Pont de Nemours and Company be liable for any claim, damages or   %
    %  other liability, whether in an action of contract, tort or otherwise,      %
    %  arising from, out of or in connection with ImageMagick or the use or other %
    %  dealings in ImageMagick.                                                   %
    %                                                                             %
    %  Except as contained in this notice, the name of the E. I. du Pont de       %
    %  Nemours and Company shall not be used in advertising or otherwise to       %
    %  promote the sale, use or other dealings in ImageMagick without prior       %
    %  written authorization from the E. I. du Pont de Nemours and Company.       %
    %                                                                             %
    %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
    %
    %  Realism in computer graphics typically requires using 24 bits/pixel to
    %  generate an image. Yet many graphic display devices do not contain
    %  the amount of memory necessary to match the spatial and color
    %  resolution of the human eye. The QUANTIZE program takes a 24 bit
    %  image and reduces the number of colors so it can be displayed on
    %  raster device with less bits per pixel. In most instances, the
    %  quantized image closely resembles the original reference image.
    %
    %  A reduction of colors in an image is also desirable for image
    %  transmission and real-time animation.
    %
    %  Function Quantize takes a standard RGB or monochrome images and quantizes
    %  them down to some fixed number of colors.
    %
    %  For purposes of color allocation, an image is a set of n pixels, where
    %  each pixel is a point in RGB space. RGB space is a 3-dimensional
    %  vector space, and each pixel, pi, is defined by an ordered triple of
    %  red, green, and blue coordinates, (ri, gi, bi).
    %
    %  Each primary color component (red, green, or blue) represents an
    %  intensity which varies linearly from 0 to a maximum value, cmax, which
    %  corresponds to full saturation of that color. Color allocation is
    %  defined over a domain consisting of the cube in RGB space with
    %  opposite vertices at (0,0,0) and (cmax,cmax,cmax). QUANTIZE requires
    %  cmax = 255.
    %
    %  The algorithm maps this domain onto a tree in which each node
    %  represents a cube within that domain. In the following discussion
    %  these cubes are defined by the coordinate of two opposite vertices:
    %  The vertex nearest the origin in RGB space and the vertex farthest
    %  from the origin.
    %
    %  The tree's root node represents the the entire domain, (0,0,0) through
    %  (cmax,cmax,cmax). Each lower level in the tree is generated by
    %  subdividing one node's cube into eight smaller cubes of equal size.
    %  This corresponds to bisecting the parent cube with planes passing
    %  through the midpoints of each edge.
    %
    %  The basic algorithm operates in three phases: Classification,
    %  Reduction, and Assignment. Classification builds a color
    %  description tree for the image. Reduction collapses the tree until
    %  the number it represents, at most, the number of colors desired in the
    %  output image. Assignment defines the output image's color map and
    %  sets each pixel's color by reclassification in the reduced tree.
    %  Our goal is to minimize the numerical discrepancies between the original
    %  colors and quantized colors (quantization error).
    %
    %  Classification begins by initializing a color description tree of
    %  sufficient depth to represent each possible input color in a leaf.
    %  However, it is impractical to generate a fully-formed color
    %  description tree in the classification phase for realistic values of
    %  cmax. If colors components in the input image are quantized to k-bit
    %  precision, so that cmax= 2k-1, the tree would need k levels below the
    %  root node to allow representing each possible input color in a leaf.
    %  This becomes prohibitive because the tree's total number of nodes is
    %  1 + sum(i=1,k,8k).
    %
    %  A complete tree would require 19,173,961 nodes for k = 8, cmax = 255.
    %  Therefore, to avoid building a fully populated tree, QUANTIZE: (1)
    %  Initializes data structures for nodes only as they are needed;  (2)
    %  Chooses a maximum depth for the tree as a function of the desired
    %  number of colors in the output image (currently log2(colormap size)).
    %
    %  For each pixel in the input image, classification scans downward from
    %  the root of the color description tree. At each level of the tree it
    %  identifies the single node which represents a cube in RGB space
    %  containing the pixel's color. It updates the following data for each
    %  such node:
    %
    %    n1: Number of pixels whose color is contained in the RGB cube
    %    which this node represents;
    %
    %    n2: Number of pixels whose color is not represented in a node at
    %    lower depth in the tree;  initially,  n2 = 0 for all nodes except
    %    leaves of the tree.
    %
    %    Sr, Sg, Sb: Sums of the red, green, and blue component values for
    %    all pixels not classified at a lower depth. The combination of
    %    these sums and n2  will ultimately characterize the mean color of a
    %    set of pixels represented by this node.
    %
    %    E: The distance squared in RGB space between each pixel contained
    %    within a node and the nodes' center. This represents the quantization
    %    error for a node.
    %
    %  Reduction repeatedly prunes the tree until the number of nodes with
    %  n2 > 0 is less than or equal to the maximum number of colors allowed
    %  in the output image. On any given iteration over the tree, it selects
    %  those nodes whose E count is minimal for pruning and merges their
    %  color statistics upward. It uses a pruning threshold, Ep, to govern
    %  node selection as follows:
    %
    %    Ep = 0
    %    while number of nodes with (n2 > 0) > required maximum number of colors
    %      prune all nodes such that E <= Ep
    %      Set Ep to minimum E in remaining nodes
    %
    %  This has the effect of minimizing any quantization error when merging
    %  two nodes together.
    %
    %  When a node to be pruned has offspring, the pruning procedure invokes
    %  itself recursively in order to prune the tree from the leaves upward.
    %  n2,  Sr, Sg,  and  Sb in a node being pruned are always added to the
    %  corresponding data in that node's parent. This retains the pruned
    %  node's color characteristics for later averaging.
    %
    %  For each node, n2 pixels exist for which that node represents the
    %  smallest volume in RGB space containing those pixel's colors. When n2
    %  > 0 the node will uniquely define a color in the output image. At the
    %  beginning of reduction,  n2 = 0  for all nodes except a the leaves of
    %  the tree which represent colors present in the input image.
    %
    %  The other pixel count, n1, indicates the total number of colors
    %  within the cubic volume which the node represents. This includes n1 -
    %  n2  pixels whose colors should be defined by nodes at a lower level in
    %  the tree.
    %
    %  Assignment generates the output image from the pruned tree. The
    %  output image consists of two parts: (1)  A color map, which is an
    %  array of color descriptions (RGB triples) for each color present in
    %  the output image;  (2)  A pixel array, which represents each pixel as
    %  an index into the color map array.
    %
    %  First, the assignment phase makes one pass over the pruned color
    %  description tree to establish the image's color map. For each node
    %  with n2  > 0, it divides Sr, Sg, and Sb by n2 . This produces the
    %  mean color of all pixels that classify no lower than this node. Each
    %  of these colors becomes an entry in the color map.
    %
    %  Finally,  the assignment phase reclassifies each pixel in the pruned
    %  tree to identify the deepest node containing the pixel's color. The
    %  pixel's value in the pixel array becomes the index of this node's mean
    %  color in the color map.
    %
    %  With the permission of USC Information Sciences Institute, 4676 Admiralty
    %  Way, Marina del Rey, California  90292, this code was adapted from module
    %  ALCOLS written by Paul Raveling.
    %
    %  The names of ISI and USC are not used in advertising or publicity
    %  pertaining to distribution of the software without prior specific
    %  written permission from ISI.
    %
    */
    companion object {
        const val QUICK = true
        const val MAX_RGB = 255
        const val MAX_NODES = 266817
        const val MAX_TREE_DEPTH = 8

        // these are precomputed in advance
        val SQUARES = IntArray(MAX_RGB + MAX_RGB + 1)
        val SHIFT = IntArray(MAX_TREE_DEPTH + 1)

        init {
            for (i in -MAX_RGB..MAX_RGB) {
                SQUARES[i + MAX_RGB] = i * i
            }

            for (i in 0 until MAX_TREE_DEPTH) {
                SHIFT[i] = 1 shl (15 - i)
            }
        }

        /**
         * Reduce the image to the given number of colors. The pixels are
         * reduced in place.
         * @return The new color palette.
         */
        fun quantizeImage(pixels: Array<IntArray>, maxColors: Int): IntArray {
            val cube = Cube(pixels, maxColors)
            cube.classification()
            cube.reduction()
            cube.assignment()
            return cube.colorMap
        }
    }

    internal class Cube(
        private val pixels: Array<IntArray>,
        private val maxColors: Int
    ) {
        lateinit var colorMap: IntArray

        private var root: Node
        var depth = 0

        // counter for the number of colors in the cube. this gets
        // recalculated often.
        var colors = 0

        // counter for the number of nodes in the tree
        var nodes = 0

        init {
            var i = maxColors
            // tree_depth = log max_colors
            //                 4
            depth = 1
            while (i != 0) {
                i /= 4
                depth++
            }

            if (depth > 1) {
                --depth
            }
            if (depth > MAX_TREE_DEPTH) {
                depth = MAX_TREE_DEPTH
            } else if (depth < 2) {
                depth = 2
            }

            root = Node(this)
        }

        /**
         * Procedure Classification begins by initializing a color
         * description tree of sufficient depth to represent each
         * possible input color in a leaf. However, it is impractical
         * to generate a fully-formed color description tree in the
         * classification phase for realistic values of cmax. If
         * colors components in the input image are quantized to k-bit
         * precision, so that cmax= 2k-1, the tree would need k levels
         * below the root node to allow representing each possible
         * input color in a leaf. This becomes prohibitive because the
         * tree's total number of nodes is 1 + sum(i=1,k,8k).
         *
         * A complete tree would require 19,173,961 nodes for k = 8,
         * cmax = 255. Therefore, to avoid building a fully populated
         * tree, QUANTIZE: (1) Initializes data structures for nodes
         * only as they are needed; (2) Chooses a maximum depth for
         * the tree as a function of the desired number of colors in
         * the output image (currently log2(colormap size)).
         *
         * For each pixel in the input image, classification scans
         * downward from the root of the color description tree. At
         * each level of the tree it identifies the single node which
         * represents a cube in RGB space containing It updates the
         * following data for each such node:
         *
         *   number_pixels : Number of pixels whose color is contained
         *   in the RGB cube which this node represents;
         *
         *   unique : Number of pixels whose color is not represented
         *   in a node at lower depth in the tree; initially, n2 = 0
         *   for all nodes except leaves of the tree.
         *
         *   total_red/green/blue : Sums of the red, green, and blue
         *   component values for all pixels not classified at a lower
         *   depth. The combination of these sums and n2 will
         *   ultimately characterize the mean color of a set of pixels
         *   represented by this node.
         */
        fun classification() {
            val pixels = this.pixels

            val width = pixels.size
            val height = pixels[0].size

            // convert to indexed color
            var x = width
            while (x-- > 0) {
                var y = height
                while (y-- > 0) {
                    val pixel = pixels[x][y]
                    val red = (pixel shr 16) and 0xFF
                    val green = (pixel shr 8) and 0xFF
                    val blue = (pixel shr 0) and 0xFF

                    // a hard limit on the number of nodes in the tree
                    if (nodes > MAX_NODES) {
                        root.pruneLevel()
                        --depth
                    }

                    // walk the tree to depth, increasing the
                    // number_pixels count for each node
                    var node = root
                    for (level in 0..depth) {
                        val id = ((if (red > node.midRed) 1 else 0) shl 0) or
                                ((if (green > node.midGreen) 1 else 0) shl 1) or
                                ((if (blue > node.midBlue) 1 else 0) shl 2)
                        if (node.child[id] == null) {
                            Node(node, id, level)
                        }
                        node = node.child[id]!!
                        node.numberPixels += SHIFT[level]
                    }

                    ++node.unique
                    node.totalRed += red
                    node.totalGreen += green
                    node.totalBlue += blue
                }
            }
        }

        /**
         * reduction repeatedly prunes the tree until the number of
         * nodes with unique > 0 is less than or equal to the maximum
         * number of colors allowed in the output image.
         *
         * When a node to be pruned has offspring, the pruning
         * procedure invokes itself recursively in order to prune the
         * tree from the leaves upward.  The statistics of the node
         * being pruned are always added to the corresponding data in
         * that node's parent.  This retains the pruned node's color
         * characteristics for later averaging.
         */
        fun reduction() {
            var threshold = 1
            while (colors > maxColors) {
                colors = 0
                threshold = root.reduce(threshold, Int.MAX_VALUE)
            }
        }

        /**
         * The result of a closest color search.
         */
        internal class Search {
            var distance = 0
            var colorNumber = 0
        }

        /**
         * Procedure assignment generates the output image from the
         * pruned tree. The output image consists of two parts: (1) A
         * color map, which is an array of color descriptions (RGB
         * triples) for each color present in the output image; (2) A
         * pixel array, which represents each pixel as an index into
         * the color map array.
         *
         * First, the assignment phase makes one pass over the pruned
         * color description tree to establish the image's color map.
         * For each node with n2 > 0, it divides Sr, Sg, and Sb by n2.
         * This produces the mean color of all pixels that classify no
         * lower than this node. Each of these colors becomes an entry
         * in the color map.
         *
         * Finally, the assignment phase reclassifies each pixel in
         * the pruned tree to identify the deepest node containing the
         * pixel's color. The pixel's value in the pixel array becomes
         * the index of this node's mean color in the color map.
         */
        fun assignment() {
            colorMap = IntArray(colors)

            colors = 0
            root.colorMap()

            val pixels = this.pixels

            val width = pixels.size
            val height = pixels[0].size

            val search = Search()

            // convert to indexed color
            var x = width
            while (x-- > 0) {
                var y = height
                while (y-- > 0) {
                    val pixel = pixels[x][y]
                    val red = (pixel shr 16) and 0xFF
                    val green = (pixel shr 8) and 0xFF
                    val blue = (pixel shr 0) and 0xFF

                    // walk the tree to find the cube containing that color
                    var node = root
                    while (true) {
                        val id = ((if (red > node.midRed) 1 else 0) shl 0) or
                                ((if (green > node.midGreen) 1 else 0) shl 1) or
                                ((if (blue > node.midBlue) 1 else 0) shl 2)
                        if (node.child[id] == null) {
                            break
                        }
                        node = node.child[id]!!
                    }

                    if (QUICK) {
                        // if QUICK is set, just use that
                        // node. Strictly speaking, this isn't
                        // necessarily best match.
                        pixels[x][y] = node.colorNumber
                    } else {
                        // Find the closest color.
                        search.distance = Int.MAX_VALUE
                        node.parent!!.closestColor(red, green, blue, search)
                        pixels[x][y] = search.colorNumber
                    }
                }
            }
        }

        /**
         * A single Node in the tree.
         */
        internal class Node {
            private var cube: Cube?

            // parent node
            var parent: Node?

            // child nodes
            var child: Array<Node?>
            private var nChild = 0

            // our index within our parent
            private var id = 0
            // our level within the tree
            private var level = 0
            // our color midpoint
            var midRed = 0
            var midGreen = 0
            var midBlue = 0

            // the pixel count for this node and all children
            var numberPixels = 0

            // the pixel count for this node
            var unique = 0
            // the sum of all pixels contained in this node
            var totalRed = 0
            var totalGreen = 0
            var totalBlue = 0

            // used to build the colormap
            var colorNumber = 0

            constructor(cube: Cube) {
                this.cube = cube
                parent = this
                child = arrayOfNulls(8)
                id = 0
                level = 0

                numberPixels = Int.MAX_VALUE

                midRed = (MAX_RGB + 1) shr 1
                midGreen = (MAX_RGB + 1) shr 1
                midBlue = (MAX_RGB + 1) shr 1
            }

            constructor(parent: Node, id: Int, level: Int) {
                cube = parent.cube
                this.parent = parent
                child = arrayOfNulls(8)
                this.id = id
                this.level = level

                // add to the cube
                ++cube!!.nodes
                if (level == cube!!.depth) {
                    ++cube!!.colors
                }

                // add to the parent
                ++parent.nChild
                parent.child[id] = this

                // figure out our midpoint
                val bi = (1 shl (MAX_TREE_DEPTH - level)) shr 1
                midRed = parent.midRed + if ((id and 1) > 0) bi else -bi
                midGreen = parent.midGreen + if ((id and 2) > 0) bi else -bi
                midBlue = parent.midBlue + if ((id and 4) > 0) bi else -bi
            }

            /**
             * Remove this child node, and make sure our parent
             * absorbs our pixel statistics.
             */
            private fun pruneChild() {
                --parent!!.nChild
                parent!!.unique += unique
                parent!!.totalRed += totalRed
                parent!!.totalGreen += totalGreen
                parent!!.totalBlue += totalBlue
                parent!!.child[id] = null
                --cube!!.nodes
                cube = null
                parent = null
            }

            /**
             * Prune the lowest layer of the tree.
             */
            fun pruneLevel() {
                if (nChild != 0) {
                    for (id in 0 until 8) {
                        if (child[id] != null) {
                            child[id]!!.pruneLevel()
                        }
                    }
                }

                if (level == cube!!.depth) {
                    pruneChild()
                }
            }

            /**
             * Remove any nodes that have fewer than threshold
             * pixels. Also, as long as we're walking the tree:
             *
             *  - figure out the color with the fewest pixels
             *  - recalculate the total number of colors in the tree
             */
            fun reduce(threshold: Int, nextThreshold: Int): Int {
                var nextThreshold = nextThreshold
                if (nChild != 0) {
                    for (id in 0 until 8) {
                        if (child[id] != null) {
                            nextThreshold = child[id]!!.reduce(threshold, nextThreshold)
                        }
                    }
                }

                if (numberPixels <= threshold) {
                    pruneChild()
                } else {
                    if (unique != 0) {
                        cube!!.colors++
                    }
                    if (numberPixels < nextThreshold) {
                        nextThreshold = numberPixels
                    }
                }

                return nextThreshold
            }

            /**
             * colormap traverses the color cube tree and notes each
             * colormap entry. A colormap entry is any node in the
             * color cube tree where the number of unique colors is
             * not zero.
             */
            fun colorMap() {
                if (nChild != 0) {
                    for (id in 0 until 8) {
                        if (child[id] != null) {
                            child[id]!!.colorMap()
                        }
                    }
                }

                if (unique != 0) {
                    val r = (totalRed + (unique shr 1)) / unique
                    val g = (totalGreen + (unique shr 1)) / unique
                    val b = (totalBlue + (unique shr 1)) / unique
                    cube!!.colorMap[cube!!.colors] = (0xFF shl 24) or
                            ((r and 0xFF) shl 16) or
                            ((g and 0xFF) shl 8) or
                            ((b and 0xFF) shl 0)
                    colorNumber = cube!!.colors++
                }
            }

            /**
             * ClosestColor traverses the color cube tree at a
             * particular node and determines which colormap entry
             * best represents the input color.
             */
            fun closestColor(red: Int, green: Int, blue: Int, search: Search) {
                if (nChild != 0) {
                    for (id in 0 until 8) {
                        if (child[id] != null) {
                            child[id]!!.closestColor(red, green, blue, search)
                        }
                    }
                }

                if (unique != 0) {
                    val color = cube!!.colorMap[colorNumber]
                    val distance = distance(color, red, green, blue)
                    if (distance < search.distance) {
                        search.distance = distance
                        search.colorNumber = colorNumber
                    }
                }
            }

            override fun toString(): String {
                val buf = StringBuffer()
                if (parent == this) {
                    buf.append("root")
                } else {
                    buf.append("node")
                }
                buf.append(' ')
                buf.append(level)
                buf.append(" [")
                buf.append(midRed)
                buf.append(',')
                buf.append(midGreen)
                buf.append(',')
                buf.append(midBlue)
                buf.append(']')

                return buf.toString()
            }

            companion object {
                fun distance(color: Int, r: Int, g: Int, b: Int): Int {
                    return SQUARES[((color shr 16) and 0xFF) - r + MAX_RGB] +
                            SQUARES[((color shr 8) and 0xFF) - g + MAX_RGB] +
                            SQUARES[((color shr 0) and 0xFF) - b + MAX_RGB]
                }
            }
        }
    }
}