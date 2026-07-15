package dev.renkinProject.renkin.ui

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UploadImageProcessingTest {

    @Test
    fun limitedTextRead_acceptsContentAtTheLimit() {
        val input = ByteArrayInputStream("12345".toByteArray())

        assertEquals("12345", input.readUtf8TextLimited(maxBytes = 5))
    }

    @Test
    fun limitedTextRead_rejectsContentAboveTheLimit() {
        val input = ByteArrayInputStream("123456".toByteArray())

        assertNull(input.readUtf8TextLimited(maxBytes = 5))
    }
}
