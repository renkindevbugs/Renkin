package dev.renkinProject.renkin.data.online

import android.app.Application
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class CollapsedSetCategoriesTest {
    @Test
    fun collapsedCategoriesSurviveANewStoreIncludingTheUncategorizedGroup() = runTest {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(emptySet<String>(), CollapsedSetCategories(context).read())

        CollapsedSetCategories(context).write(setOf("Material", ""))

        assertEquals(setOf("Material", ""), CollapsedSetCategories(context).read())
    }
}
