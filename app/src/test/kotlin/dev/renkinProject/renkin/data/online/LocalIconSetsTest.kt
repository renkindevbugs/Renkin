package dev.renkinProject.renkin.data.online

import android.app.Application
import dev.renkinProject.renkin.data.online.OnlineIconRepository.Companion.HttpTextResponse
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class LocalIconSetsTest {
    private fun TestScope.bundledSets() = LocalIconSets(
        { name -> File("src/main/assets", name).inputStream() },
        StandardTestDispatcher(testScheduler)
    )

    @Test
    fun bundledTablerParsesAndMatchesItsInfo() = runTest {
        val sets = bundledSets()

        val tabler = sets.set("tabler")!!
        val info = sets.collections().single()

        assertEquals("tabler", info.prefix)
        assertEquals("MIT", info.license)
        assertEquals(info.total, tabler.visibleNames.size)
        info.samples.forEach { assertTrue(tabler.lookup(it) is IconLookup.Markup) }
        val whatsapp = tabler.lookup("brand-whatsapp") as IconLookup.Markup
        assertTrue(OnlineIconRepository.isWellFormedSvg(whatsapp.svg))
        assertTrue(whatsapp.svg.contains("viewBox=\"0 0 24 24\""))
        assertNull(sets.set("mdi"))
    }

    @Test
    fun repositoryUsesTheBundledSetWithoutNetwork() = runTest {
        val repository = OnlineIconRepository(
            RuntimeEnvironment.getApplication(),
            { _, _ -> HttpTextResponse(status = 503) },
            { testScheduler.currentTime },
            StandardTestDispatcher(testScheduler),
            bundledSets()
        )

        val collections = repository.collections()!!
        val icons = repository.icons(collections.single())!!

        assertEquals(listOf("tabler"), collections.map { it.prefix })
        assertTrue(repository.isBuiltIn("tabler"))
        assertTrue(OnlineIcon("tabler", "brand-whatsapp") in icons)
        assertTrue(repository.svg(OnlineIcon("tabler", "brand-whatsapp"), cachedOnly = true)!!
            .startsWith("<svg"))
        assertNull(repository.svg(OnlineIcon("mdi", "home"), cachedOnly = true))
    }
}
