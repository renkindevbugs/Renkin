package dev.renkinProject.renkin.service

import android.app.Application
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.RawElement
import dev.renkinProject.renkin.data.RawItem
import dev.renkinProject.renkin.data.toComponentInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class WatchPackIconsTest {
    private val first = InstalledApplication("com.first", "com.first.Main", 0)
    private val second = InstalledApplication("com.second", "com.second.Main", 0)
    private val unthemed = InstalledApplication("com.plain", "com.plain.Main", 0)

    private val parses = mutableListOf<Pair<String, List<String>>>()
    private val resolves = mutableListOf<Pair<String, String>>()

    private fun parseThemingFirstAndSecond(pack: String, apps: List<InstalledApplication>): List<RawElement> {
        parses += pack to apps.map { it.toComponentInfo() }
        val themed = setOf(first.toComponentInfo(), second.toComponentInfo())
        return apps.map { it.toComponentInfo() }.filter { it in themed }.map { RawItem(it, "$pack/$it") }
    }

    private fun resolveByLastItem(pack: String, app: InstalledApplication, elements: List<RawElement>): Pair<String?, String?> {
        resolves += pack to app.toComponentInfo()
        val name = elements.filterIsInstance<RawItem>().lastOrNull { it.component == app.toComponentInfo() }?.drawableLink
        return name to name?.let { "hash:$it" }
    }

    private fun icons(watched: List<InstalledApplication> = listOf(first, second, unthemed)) =
        WatchPackIcons(watched, ::parseThemingFirstAndSecond, ::resolveByLastItem)

    @Test fun eachPackIsParsedOnceForAllWatchedAppsAndEachPairResolvedOnce() {
        val icons = icons()
        for (pack in listOf("pack.a", "pack.b")) {
            for (app in listOf(first, second, unthemed, first)) icons.resolveIcon(pack, app)
        }

        val watched = listOf(first, second, unthemed).map { it.toComponentInfo() }
        assertEquals(listOf("pack.a" to watched, "pack.b" to watched), parses)
        assertEquals(6, resolves.size)
        assertEquals("pack.a/${first.toComponentInfo()}" to "hash:pack.a/${first.toComponentInfo()}",
            icons.resolveIcon("pack.a", first))
    }

    @Test fun noIconStaysAPairOfNullsWhileAnUnreadablePackIsNull() {
        val icons = WatchPackIcons(listOf(first, unthemed),
            parse = { pack, apps -> if (pack == "broken") error("malformed appfilter") else parseThemingFirstAndSecond(pack, apps) },
            resolve = ::resolveByLastItem)

        assertEquals(null to null, icons.resolveIcon("pack.a", unthemed))
        assertNull(icons.resolveIcon("broken", first))
        assertNull(icons.resolveIcon("broken", unthemed))
        assertEquals(listOf("pack.a"), parses.map { it.first })
    }

    @Test fun anUnreadablePackIsReadOncePerRunAndRetriedByTheNextRun() {
        var attempts = 0
        fun run() = WatchPackIcons(listOf(first, second),
            parse = { _, _ -> attempts++; error("pack busy") },
            resolve = ::resolveByLastItem)

        val firstRun = run()
        assertNull(firstRun.resolveIcon("pack.a", first))
        assertNull(firstRun.resolveIcon("pack.a", second))
        assertEquals(1, attempts)

        assertNull(run().resolveIcon("pack.a", first))
        assertEquals(2, attempts)
    }

    @Test fun aFailedDrawableReadDoesNotHideTheOtherAppsOfThePack() {
        val icons = WatchPackIcons(listOf(first, second), ::parseThemingFirstAndSecond) { pack, app, elements ->
            if (app == first) error("bitmap decode failed") else resolveByLastItem(pack, app, elements)
        }

        assertNull(icons.resolveIcon("pack.a", first))
        assertEquals("pack.a/${second.toComponentInfo()}", icons.resolveIcon("pack.a", second)?.first)
        assertEquals(1, parses.size)
    }

    @Test fun anAppOutsideTheRunsSetGetsItsOwnParseInsteadOfAFalseNoIcon() {
        val icons = icons(watched = listOf(first))
        icons.resolveIcon("pack.a", first)

        assertEquals("pack.a/${second.toComponentInfo()}", icons.resolveIcon("pack.a", second)?.first)
        assertEquals(listOf("pack.a" to listOf(first.toComponentInfo()), "pack.a" to listOf(second.toComponentInfo())), parses)
    }
}
