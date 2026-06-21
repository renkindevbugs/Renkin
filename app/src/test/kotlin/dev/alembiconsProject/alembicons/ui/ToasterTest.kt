package dev.alembiconsProject.alembicons.ui

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Toaster] is plain coroutines (a buffered Channel exposed as a Flow), so it runs as a
 * pure JVM test. Covers the one-shot toast mechanism that replaced the scattered
 * `if (flag) { ShowToast(...); flag = false }` idiom.
 */
class ToasterTest {

    @Test
    fun show_emitsTheMessage() = runBlocking {
        val toaster = Toaster()
        toaster.show("hello")
        assertEquals("hello", toaster.events.first())
    }

    @Test
    fun show_buffersMessagesFiredBeforeCollectionInOrder() = runBlocking {
        val toaster = Toaster()
        // Fire before anyone collects — the BUFFERED channel must keep them, in order.
        toaster.show("a")
        toaster.show("b")
        toaster.show("c")

        assertEquals(listOf("a", "b", "c"), toaster.events.take(3).toList())
    }
}
