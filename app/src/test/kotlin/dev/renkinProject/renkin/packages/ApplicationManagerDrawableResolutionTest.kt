package dev.renkinProject.renkin.packages

import android.app.Application
import android.graphics.drawable.ColorDrawable
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ApplicationManagerDrawableResolutionTest {

    @Test
    fun missingName_doesNotShiftFollowingNameToTheWrongResource() {
        val ids = mapOf("first" to 11, "missing" to 0, "last" to 33)

        val result = resolveNamedDrawables(
            listOf("first", "missing", "last"),
            resolveId = { ids.getValue(it) },
            loadDrawable = { ColorDrawable(it) }
        )

        assertEquals(listOf("first", "last"), result.map { it.name })
        assertEquals(listOf(11, 33), result.map { it.resource.resourceId })
    }

    @Test
    fun malformedDrawable_skipsOnlyThatEntry() {
        val result = resolveNamedDrawables(
            listOf("first", "broken", "last"),
            resolveId = { name -> mapOf("first" to 11, "broken" to 22, "last" to 33).getValue(name) },
            loadDrawable = { id -> if (id == 22) error("inflate failed") else ColorDrawable(id) }
        )

        assertEquals(listOf("first", "last"), result.map { it.name })
        assertEquals(listOf(11, 33), result.map { it.resource.resourceId })
    }
}
