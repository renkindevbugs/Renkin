package dev.renkinProject.renkin.service

import android.app.Application
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.RawCalendar
import dev.renkinProject.renkin.data.RawElement
import dev.renkinProject.renkin.data.RawItem
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.data.watch.WatchReadFailureReason
import dev.renkinProject.renkin.data.watch.WatchReadIssue
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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
        assertEquals(listOf(WatchReadIssue("broken", WatchReadFailureReason.PACK_UNREADABLE)), icons.readIssues)
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
        val icons = WatchPackIcons(listOf(first, second), ::parseThemingFirstAndSecond, resolve = { pack, app, elements ->
            if (app == first) error("bitmap decode failed") else resolveByLastItem(pack, app, elements)
        })

        assertNull(icons.resolveIcon("pack.a", first))
        assertEquals("pack.a/${second.toComponentInfo()}", icons.resolveIcon("pack.a", second)?.first)
        assertEquals(1, parses.size)
        assertEquals(listOf(WatchReadIssue("pack.a", WatchReadFailureReason.ICON_UNREADABLE,
            first.packageName, first.activityName, "pack.a/${first.toComponentInfo()}")), icons.readIssues)
    }

    @Test fun anAppOutsideTheRunsSetGetsItsOwnParseInsteadOfAFalseNoIcon() {
        val icons = icons(watched = listOf(first))
        icons.resolveIcon("pack.a", first)

        assertEquals("pack.a/${second.toComponentInfo()}", icons.resolveIcon("pack.a", second)?.first)
        assertEquals(listOf("pack.a" to listOf(first.toComponentInfo()), "pack.a" to listOf(second.toComponentInfo())), parses)
    }

    @Test fun aMissingMappedDrawableReportsTheWinningResourceAndAppOnlyOnce() {
        val icons = WatchPackIcons(listOf(first),
            parse = { _, _ -> listOf(RawItem(first.toComponentInfo(), "older_icon"),
                RawItem(first.toComponentInfo(), "authenticator_google")) },
            resolve = { _, app, elements ->
                elements.filterIsInstance<RawItem>().last { it.component == app.toComponentInfo() }.drawableLink to null
            },
            describeFailure = { pack, app, name ->
                assertEquals("pack.a", pack)
                assertEquals(first, app)
                assertEquals("authenticator_google", name)
                WatchReadFailureReason.MISSING_DRAWABLE
            })

        assertNull(icons.resolveIcon("pack.a", first))
        assertNull(icons.resolveIcon("pack.a", first))
        assertEquals(listOf(WatchReadIssue("pack.a", WatchReadFailureReason.MISSING_DRAWABLE,
            first.packageName, first.activityName, "authenticator_google")), icons.readIssues)
        assertTrue(icons.hadReadFailures)
        assertEquals(setOf("pack.a"), icons.failedPackages)
    }

    @Test fun baselineNeverCheckpointsABrokenMappedIconButKeepsHealthyAndUnthemedApps() {
        val icons = WatchPackIcons(listOf(first, second, unthemed), ::parseThemingFirstAndSecond,
            resolve = { pack, app, elements ->
                val resolved = resolveByLastItem(pack, app, elements)
                if (app == first) resolved.first to null else resolved
            },
            describeFailure = { _, _, _ -> WatchReadFailureReason.MISSING_DRAWABLE })
        val pack = IconPack("pack.a", "Pack A", 7, "1.0", 0)

        val baseline = icons.baseline(pack, 123)

        assertEquals(setOf(second.packageName, unthemed.packageName), baseline.map { it.packageName }.toSet())
        val healthy = baseline.single { it.packageName == second.packageName }
        assertEquals(7L, healthy.lastPackVersionCode)
        assertEquals(123L, healthy.lastCheckedAt)
        assertEquals("hash:pack.a/${second.toComponentInfo()}", healthy.lastIconHash)
        val noIcon = baseline.single { it.packageName == unthemed.packageName }
        assertNull(noIcon.lastIconName)
        assertNull(noIcon.lastIconHash)
        assertEquals(first.packageName, icons.readIssues.single().appPackage)
        assertEquals(1, parses.size)
    }

    @Test fun aMissingMappedIconCanRecoverInANewRunAtTheSamePackVersion() {
        var repaired = false
        fun run() = WatchPackIcons(listOf(first), ::parseThemingFirstAndSecond,
            resolve = { pack, app, elements ->
                val resolved = resolveByLastItem(pack, app, elements)
                if (repaired) resolved else resolved.first to null
            })
        val pack = IconPack("pack.a", "Pack A", 7, "1.0", 0)
        assertTrue(run().baseline(pack, 123).isEmpty())

        repaired = true
        val recovered = run()
        val baseline = recovered.baseline(pack, 456).single()

        assertEquals(7L, baseline.lastPackVersionCode)
        assertEquals("hash:pack.a/${first.toComponentInfo()}", baseline.lastIconHash)
        assertTrue(recovered.readIssues.isEmpty())
        assertEquals(2, parses.size)
    }

    @Test fun aCalendarPrefixIsNotMisreportedAsAMissingStaticDrawable() {
        var diagnoses = 0
        val icons = WatchPackIcons(listOf(first),
            parse = { _, _ -> listOf(RawCalendar(first.toComponentInfo(), "calendar_")) },
            resolve = ::resolveByLastItem,
            describeFailure = { _, _, _ -> diagnoses++; WatchReadFailureReason.MISSING_DRAWABLE })

        assertEquals(null to null, icons.resolveIcon("pack.a", first))
        assertEquals(0, diagnoses)
        assertTrue(icons.readIssues.isEmpty())
    }

    @Test fun cancellingFailureDiagnosticsDoesNotBecomeAPartialCheck() {
        val icons = WatchPackIcons(listOf(first), ::parseThemingFirstAndSecond,
            resolve = { _, _, _ -> "missing_icon" to null },
            describeFailure = { _, _, _ -> throw CancellationException("cancel") })

        assertThrows(CancellationException::class.java) { icons.resolveIcon("pack.a", first) }
        assertTrue(icons.readIssues.isEmpty())
    }
}
