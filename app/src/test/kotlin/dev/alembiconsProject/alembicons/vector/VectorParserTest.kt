package dev.alembiconsProject.alembicons.vector

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The strokeLineCap / strokeLineJoin / fillType value → enum mappings, including the default a
 * missing attribute (-1) falls back to. A regression defaulted an absent strokeLineJoin to Bevel;
 * Android's default — and the renderer's — is Miter, which this pins.
 */
class VectorParserTest {

    @Test
    fun strokeCapMapsValuesAndDefaultsToButt() {
        assertEquals(StrokeCap.Butt, parseCap(0))
        assertEquals(StrokeCap.Round, parseCap(1))
        assertEquals(StrokeCap.Square, parseCap(2))
        assertEquals(StrokeCap.Butt, parseCap(-1)) // absent attribute → Android default
    }

    @Test
    fun strokeJoinMapsValuesAndDefaultsToMiter() {
        assertEquals(StrokeJoin.Miter, parseJoin(0))
        assertEquals(StrokeJoin.Round, parseJoin(1))
        assertEquals(StrokeJoin.Bevel, parseJoin(2))
        assertEquals(StrokeJoin.Miter, parseJoin(-1)) // absent attribute → Android default (was Bevel)
    }

    @Test
    fun fillTypeIsNonZeroForZeroAndEvenOddOtherwise() {
        assertEquals(PathFillType.NonZero, parseFill(0))
        assertEquals(PathFillType.EvenOdd, parseFill(1))
    }
}
