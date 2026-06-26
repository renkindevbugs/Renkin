package dev.alembiconsProject.alembicons.drawable

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic tests for [widestWord], the word the multi-line text must shrink to fit. A
 * regression discarded the sort result and returned the first word instead of the widest;
 * these pin the corrected behaviour. Word "width" is stood in by character count.
 */
class MultiLineTextDrawableTest {

    private val byLength: (String) -> Int = { it.length }

    @Test
    fun picksTheWidestWordEvenWhenItIsNotFirst() {
        // "Acrobat" is wider than the leading "Adobe" — the bug returned "Adobe".
        assertEquals("Acrobat", widestWord(listOf("Adobe", "Acrobat", "PDF"), maxLines = 3, byLength))
    }

    @Test
    fun singleWordIsReturnedAsIs() {
        assertEquals("Settings", widestWord(listOf("Settings"), maxLines = 3, byLength))
    }

    @Test
    fun onlyConsidersTheFirstMaxLinesWords() {
        // The very wide trailing word falls past maxLines, so it's ignored.
        assertEquals("Two", widestWord(listOf("A", "Two", "Superlongword"), maxLines = 2, byLength))
    }
}
