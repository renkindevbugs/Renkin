package dev.renkinProject.renkin.icon.creator

import org.junit.Assert.assertEquals
import org.junit.Test

class LetterGeneratorTest {

    @Test
    fun twoLetterInitials_handlesWhitespaceRunsAndUnicodeSeparators() {
        assertEquals("FB", twoLetterInitials("Foo  Bar"))
        assertEquals("FB", twoLetterInitials("Foo\tBar"))
        assertEquals("FB", twoLetterInitials("Foo\nBar"))
        assertEquals("FB", twoLetterInitials("Foo\u00A0Bar"))
        assertEquals("FB", twoLetterInitials("Foo\u3000Bar"))
    }

    @Test
    fun twoLetterInitials_keepsWholeUnicodeCodePoints() {
        assertEquals("😀F", twoLetterInitials("😀 Foo"))
        assertEquals("😀F", twoLetterInitials("😀Foo"))
        assertEquals("", twoLetterInitials(" \t\n "))
    }
}
