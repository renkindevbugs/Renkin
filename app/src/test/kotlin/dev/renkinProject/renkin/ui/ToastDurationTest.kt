package dev.renkinProject.renkin.ui

import android.widget.Toast
import org.junit.Assert.assertEquals
import org.junit.Test

class ToastDurationTest {
    @Test
    fun shortNoticeKeepsTheShortToast() {
        assertEquals(Toast.LENGTH_SHORT, toastDuration("Press back again to exit"))
        assertEquals(Toast.LENGTH_SHORT, toastDuration("x".repeat(60)))
    }

    @Test
    fun longMessageGetsTheLongToast() {
        assertEquals(Toast.LENGTH_LONG, toastDuration("x".repeat(61)))
        assertEquals(
            Toast.LENGTH_LONG,
            toastDuration("Couldn't import this file. Check that it is a Renkin backup and try again.")
        )
    }
}
