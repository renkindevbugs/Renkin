package dev.renkinProject.renkin.ui

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import dev.renkinProject.renkin.data.watch.RuleWithDetails
import dev.renkinProject.renkin.data.watch.WatchRule
import dev.renkinProject.renkin.data.watch.WatchRuleApp
import dev.renkinProject.renkin.packages.PackageInfoStruct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the quick actions' watch row reads: whether this exact component is already covered by a
 * rule. Getting it wrong either hides the action or lets duplicate rules pile up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class WatchRuleLookupTest {

    private fun app(pkg: String, activity: String = "$pkg.Main") = PackageInfoStruct(
        appName = pkg,
        packageName = pkg,
        activityName = activity,
        icon = ColorDrawable(Color.RED),
        iconID = 0
    )

    private fun rule(id: Long, vararg apps: PackageInfoStruct) = RuleWithDetails(
        rule = WatchRule(id = id),
        apps = apps.map { WatchRuleApp(id, it.packageName, it.activityName) },
        packs = emptyList(),
        suggestions = emptyList()
    )

    @Test
    fun findsTheRuleThatListsTheApp() {
        val signal = app("com.signal")
        val rules = listOf(rule(1, app("com.firefox")), rule(2, signal))

        assertEquals(2L, watchRuleFor(rules, signal)?.rule?.id)
    }

    @Test
    fun aRuleCoveringSeveralAppsMatchesEachOfThem() {
        val signal = app("com.signal")
        val firefox = app("com.firefox")
        val rules = listOf(rule(7, signal, firefox))

        assertEquals(7L, watchRuleFor(rules, signal)?.rule?.id)
        assertEquals(7L, watchRuleFor(rules, firefox)?.rule?.id)
    }

    @Test
    fun anotherActivityOfTheSamePackageIsADifferentIcon() {
        val main = app("com.dual", "com.dual.Main")
        val second = app("com.dual", "com.dual.Second")

        // Renkin identifies icons by component, and so must the rule lookup.
        assertNull(watchRuleFor(listOf(rule(1, main)), second))
    }

    @Test
    fun noRulesMeansNothingIsWatched() {
        assertNull(watchRuleFor(emptyList(), app("com.signal")))
        assertNull(watchRuleFor(listOf(rule(1, app("com.other"))), app("com.signal")))
    }
}
