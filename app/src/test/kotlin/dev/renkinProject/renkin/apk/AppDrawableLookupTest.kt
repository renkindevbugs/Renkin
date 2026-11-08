package dev.renkinProject.renkin.apk

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.RawCalendar
import dev.renkinProject.renkin.data.RawItem
import dev.renkinProject.renkin.data.toComponentInfo
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.icon.creator.IconPackContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A generator used to decode every mapped app of both packs for each icon (B1). The captured
 * lookup must resolve only the app it is asked for, with the full-map path's pairing rules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class AppDrawableLookupTest {
    private val pack = "com.pack"
    private val apps = (0 until 300).map { InstalledApplication("com.app$it", "com.app$it.Main", 0) }
    private val index = AppFilterIndex(
        mapOf(pack to apps.mapIndexed { i, app -> RawItem(app.toComponentInfo(), "icon_$i") }),
        apps
    )

    private class CountingResolver(private val missing: Set<String> = emptySet()) : (String) -> ResourceDrawable? {
        val requested = mutableListOf<String>()
        override fun invoke(name: String): ResourceDrawable? {
            requested += name
            return if (name in missing) null else ResourceDrawable(name.hashCode(), ColorDrawable(Color.RED))
        }
    }

    @Test
    fun oneAppResolvesOnlyItsOwnDrawable() {
        val resolver = CountingResolver()
        val lookup = appDrawableLookup(index, pack, resolver)

        val drawable = lookup(InstalledApplication("com.app7", "com.app7.Main", 0))

        assertEquals("icon_7".hashCode(), drawable?.resourceId)
        assertEquals(listOf("icon_7"), resolver.requested)
    }

    @Test
    fun appOutsideTheCapturedApplicationsGetsNothing() {
        val stranger = InstalledApplication("com.new", "com.new.Main", 0)
        val withStrangerMapping = AppFilterIndex(
            mapOf(pack to listOf(RawItem(stranger.toComponentInfo(), "new_icon"))),
            apps
        )
        val resolver = CountingResolver()

        assertNull(appDrawableLookup(withStrangerMapping, pack, resolver)(stranger))
        assertTrue(resolver.requested.isEmpty())
    }

    @Test
    fun emptyOrUnknownPackResolvesNothing() {
        val resolver = CountingResolver()

        assertNull(appDrawableLookup(index, "", resolver)(apps[0]))
        assertNull(appDrawableLookup(index, "com.other", resolver)(apps[0]))
        assertTrue(resolver.requested.isEmpty())
    }

    @Test
    fun lastDeclarationWinsAndABrokenWinnerDoesNotReviveTheOlderIcon() {
        val app = apps[0]
        val duplicated = AppFilterIndex(
            mapOf(
                pack to listOf(
                    RawItem(app.toComponentInfo(), "old_icon"),
                    RawCalendar(app.toComponentInfo(), "calendar_"),
                    RawItem(app.toComponentInfo(), "new_icon")
                )
            ),
            listOf(app)
        )
        val resolver = CountingResolver(missing = setOf("new_icon"))

        assertNull(appDrawableLookup(duplicated, pack, resolver)(app))
        assertEquals(listOf("new_icon"), resolver.requested)
    }

    @Test
    fun containerAsksItsLookupOnlyForRequestedApps() {
        val resolver = CountingResolver()
        val container = IconPackContainer(pack, appDrawableLookup(index, pack, resolver))

        container.getApplicationIcon(apps[3])
        container.getApplicationIcon(apps[5])

        assertEquals(listOf("icon_3", "icon_5"), resolver.requested)
    }
}
