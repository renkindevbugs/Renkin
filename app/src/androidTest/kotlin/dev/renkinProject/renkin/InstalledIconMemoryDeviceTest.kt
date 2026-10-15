package dev.renkinProject.renkin

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.UserManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.renkinProject.renkin.packages.InstalledAppCatalog
import dev.renkinProject.renkin.packages.InstalledIconMemorySnapshot
import dev.renkinProject.renkin.packages.inspectInstalledIconMemory
import dev.renkinProject.renkin.packages.retainedDrawableOrNull
import dev.renkinProject.renkin.packages.ApplicationIconSource
import dev.renkinProject.renkin.packages.InstalledIconLoader
import dev.renkinProject.renkin.packages.LoadedApplicationIcon
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.packages.launcherActivityIconId
import dev.renkinProject.renkin.drawable.hasValidDimensions
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import dev.renkinProject.renkin.extension.toDrawable
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.icon.creator.ApplicationIconVariant
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconExportOptions
import dev.renkinProject.renkin.icon.creator.IconGenerator
import dev.renkinProject.renkin.icon.creator.IconPackContainer
import dev.renkinProject.renkin.icon.creator.IconSourceOptions
import dev.renkinProject.renkin.icon.creator.ModifierStack
import dev.renkinProject.renkin.icon.creator.EffectModifier
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.ui.EditorSession
import dev.renkinProject.renkin.ui.captureRenderWitness
import dev.renkinProject.renkin.ui.renderWitnessOrNull
import dev.renkinProject.renkin.ui.canConfirmDraft
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/** Aggregate-only measurement; run with a backed-up debug app and an external meminfo sampler. */
@RunWith(AndroidJUnit4::class)
class InstalledIconMemoryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun verifyEditorPreviewSourceChanges() {
        check(context.packageName == "dev.renkinProject.renkin.dev")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }) as MainActivity
        fun node(label: String): AccessibilityNodeInfo? {
            fun find(value: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (value.text?.toString() == label || value.contentDescription?.toString() == label) return value
                repeat(value.childCount) { index -> value.getChild(index)?.let { find(it)?.let { return it } } }
                return null
            }
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
            return if (root.packageName?.toString() == context.packageName) find(root) else null
        }
        fun click(label: String) {
            await { node(label) != null }
            var value = requireNotNull(node(label))
            while (!value.isClickable) value = requireNotNull(value.parent)
            check(value.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        try {
            await { node("Not now") != null || node("Edit") != null }
            if (node("Not now") != null) click("Not now")
            await { node("Edit") != null }
            click("Edit")
            lateinit var editor: EditorSessionViewModel
            instrumentation.runOnMainSync { editor = ViewModelProvider(activity)[EditorSessionViewModel::class.java] }
            await { editor.lifetime.value.sessionId != null }
            lateinit var session: EditorSession
            instrumentation.runOnMainSync {
                val activeField = EditorSessionViewModel::class.java.getDeclaredField("activeSession").apply { isAccessible = true }
                val active = requireNotNull(activeField.get(editor))
                session = active.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(active) as EditorSession
            }
            await { session.applicationIconPrepared && session.draft.completedFrameSerial != null }
            val initial = requireNotNull(session.draft.completedFrameSerial)
            await { node("App Icon") != null }
            click("App Icon")
            try {
                await { (session.draft.completedFrameSerial ?: 0) > initial && !session.draft.generating }
            } finally {
                emit(JSONObject().put("phase", "editor-source-state")
                    .put("source", session.source.name).put("revision", session.draft.sourceRevision)
                    .put("initialSerial", initial).put("serial", session.draft.completedFrameSerial)
                    .put("generating", session.draft.generating)
                    .put("sameArtworkAsOpening", session.draft.iconToConfirm === session.application.createdIcon))
            }
            assertTrue("Editor kept the opening artwork after selecting App Icon",
                session.draft.iconToConfirm !== session.application.createdIcon)
            for ((label, variant) in listOf("Default" to ApplicationIconVariant.DEFAULT,
                "Monochrome" to ApplicationIconVariant.MONOCHROME, "Material You" to ApplicationIconVariant.MATERIAL_YOU)) {
                if (session.applicationIconVariant != variant) {
                    val previous = requireNotNull(session.draft.completedFrameSerial)
                    click(label)
                    await { (session.draft.completedFrameSerial ?: 0) > previous && !session.draft.generating }
                }
                await { session.canConfirmDraft(session.renderWitnessOrNull()) }
                lateinit var state: dev.renkinProject.renkin.ui.IconDraftState.CaptureState
                instrumentation.runOnMainSync { state = session.draft.captureState(session.captureRenderWitness()) }
                assertEquals(Source.APPLICATION_ICON, state.requested.source.primarySource)
                assertEquals(variant, state.requested.source.applicationIconVariant)
                val expected = IconGenerator(context, state.requested,
                    IconPackContainer("", emptyMap()), IconPackContainer("", emptyMap()))
                    .generatePreview(requireNotNull(session.applicationIcon).application, null).icon
                assertTrue("Editor preview differs from its reviewed app-source request",
                    requireNotNull(expected).toDbString() == requireNotNull(state.artwork).toDbString())
            }
            emit(JSONObject().put("phase", "editor-variants").put("variants", 3).put("completedArtworkMatched", true))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test
    fun verifyDeferredSourcesAndGeneration() = runBlocking {
        check(context.packageName == "dev.renkinProject.renkin.dev")
        val loader = InstalledIconLoader(context)
        val apps = InstalledAppCatalog(context).getAllInstalledApps()
        val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val users = context.getSystemService(Context.USER_SERVICE) as UserManager
        val eagerEntries = users.userProfiles.flatMap { launcher.getActivityList(null, it) }
            .associateBy { it.componentName to it.user }
        var layers = 0
        var generated = 0
        val kinds = mutableSetOf<String>()
        for (app in apps) {
            val source = app.iconSource as ApplicationIconSource.Installed
            val entry = requireNotNull(eagerEntries[source.component to source.user])
            val direct = entry.getIcon(context.resources.displayMetrics.densityDpi)
            val eager = if (direct.hasValidDimensions()) direct else {
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).toDrawable(context.resources)
            }
            val deferred = requireNotNull(loader.load(source))
            assertEquals(launcherActivityIconId(context, entry), deferred.resourceId)
            compareSource(eager, deferred.drawable)
            if (Build.VERSION.SDK_INT >= 26 && eager is AdaptiveIconDrawable) {
                val adaptive = deferred.drawable as AdaptiveIconDrawable
                compareSource(eager.foreground, adaptive.foreground)
                compareSource(eager.background, adaptive.background)
                layers += 2
                if (Build.VERSION.SDK_INT >= 33 && eager.monochrome != null) {
                    compareSource(requireNotNull(eager.monochrome), requireNotNull(adaptive.monochrome))
                    layers++
                }
            }
            val kind = eager.javaClass.name + if (Build.VERSION.SDK_INT >= 33 && eager is AdaptiveIconDrawable) {
                "/monochrome=${eager.monochrome != null}"
            } else ""
            if (kinds.add(kind)) {
                val captured = app.withLoadedApplicationIcon(LoadedApplicationIcon(eager, deferred.resourceId, entry.applicationInfo))
                for (variant in ApplicationIconVariant.entries) for (vector in listOf(false, true)) for (full in listOf(false, true)) {
                    val options = GenerationOptions(
                        source = IconSourceOptions(primarySource = Source.APPLICATION_ICON,
                            applicationIconVariant = variant, useFullApplicationIcon = full),
                        export = IconExportOptions(vector = vector),
                        modifiers = ModifierStack(EffectModifier(ImageEdit.NONE, ColorizerStyle(firstColor = -1))),
                        override = true
                    )
                    fun generate(value: PackageInfoStruct) = IconGenerator(context, options,
                        IconPackContainer("", emptyMap()), IconPackContainer("", emptyMap()))
                        .generatePreview(value, null, retainInput = true).icon
                    val expected = requireNotNull(generate(captured))
                    val actual = requireNotNull(generate(app))
                    assertEquals(expected.javaClass, actual.javaClass)
                    assertEquals(expected.isAdaptiveIcon(), actual.isAdaptiveIcon())
                    assertTrue("Generated serialization differs", expected.toDbString() == actual.toDbString())
                    generated++
                }
            }
        }
        emit(JSONObject().put("phase", "deferred-equivalence").put("sources", apps.size)
            .put("adaptiveLayers", layers).put("sourceKinds", kinds.size).put("generationCases", generated))
    }

    private fun compareSource(expected: Drawable, actual: Drawable) {
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.intrinsicWidth, actual.intrinsicWidth)
        assertEquals(expected.intrinsicHeight, actual.intrinsicHeight)
        fun pixels(value: Drawable): Bitmap {
            val copy = value.constantState?.newDrawable(context.resources)?.mutate() ?: value
            return requireNotNull(copy.toSafeBitmapOrNull(500, 500))
        }
        assertTrue("Source or adaptive-layer pixels differ at generation size", pixels(expected).sameAs(pixels(actual)))
    }

    @Test
    fun measureProductionReload() {
        check(context.packageName == "dev.renkinProject.renkin.dev")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }) as MainActivity
        try {
            lateinit var viewModel: MainViewModel
            instrumentation.runOnMainSync { viewModel = ViewModelProvider(activity)[MainViewModel::class.java] }
            await { viewModel.runtimeState.value.ready }
            val appCount = viewModel.applicationList.size
            val createdCount = viewModel.applicationList.count { it.createdIcon != null }
            repeat(2) { index ->
                report("reload-$index-before", inspectInstalledIconMemory(viewModel.applicationList.mapNotNull { it.iconSource.retainedDrawableOrNull() }))
                val token = viewModel.runtimeState.value.sessionToken
                val started = SystemClock.elapsedRealtimeNanos()
                instrumentation.runOnMainSync { viewModel.refreshApps() }
                await {
                    val runtime = viewModel.runtimeState.value
                    runtime.ready && runtime.sessionToken != token &&
                        viewModel.applicationState.value.sessionToken == runtime.sessionToken
                }
                report("reload-$index-completed", inspectInstalledIconMemory(viewModel.applicationList.mapNotNull { it.iconSource.retainedDrawableOrNull() }),
                    (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
                assertEquals(token.profileId, viewModel.runtimeState.value.sessionToken.profileId)
                assertEquals(appCount, viewModel.applicationList.size)
                assertEquals(createdCount, viewModel.applicationList.count { it.createdIcon != null })
                Thread.sleep(6_000)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun measureSourceAcquisitionAndRetainedCatalogOverlap() = runBlocking {
        check(context.packageName == "dev.renkinProject.renkin.dev")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }) as MainActivity
        try {
            lateinit var viewModel: MainViewModel
            instrumentation.runOnMainSync { viewModel = ViewModelProvider(activity)[MainViewModel::class.java] }
            await { viewModel.runtimeState.value.ready }
            val original = viewModel.applicationState.value
            val originalIcons = original.applications.mapNotNull { it.iconSource.retainedDrawableOrNull() }
            report("live-catalog", inspectInstalledIconMemory(originalIcons))

            val started = SystemClock.elapsedRealtimeNanos()
            val candidate = InstalledAppCatalog(context).getAllInstalledApps()
            val loadedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            assertEquals(original.applications.size, candidate.size)
            emit(JSONObject().put("phase", "source-descriptor-catalog")
                .put("entries", original.applications.size).put("candidateEntries", candidate.size))
            report("second-catalog", inspectInstalledIconMemory(candidate.mapNotNull { it.iconSource.retainedDrawableOrNull() }), loadedMs)
            report("retained-catalog-overlap", inspectInstalledIconMemory(originalIcons + candidate.mapNotNull { it.iconSource.retainedDrawableOrNull() }))
            // The explicit hold lets the external sampler observe this controlled ownership case.
            // It is not a claim that production always retains both catalogs this long.
            Thread.sleep(2_000)
            report("retained-catalog-overlap-end", inspectInstalledIconMemory(originalIcons + candidate.mapNotNull { it.iconSource.retainedDrawableOrNull() }))

            val users = context.getSystemService(Context.USER_SERVICE) as UserManager
            val loader = InstalledIconLoader(context)
            val entries = candidate.map { it.iconSource }
            repeat(2) { pass ->
                val durations = ArrayList<Double>(entries.size)
                var failures = 0
                var bitmaps = 0
                var bitmapBytes = 0L
                val wallStart = SystemClock.elapsedRealtimeNanos()
                entries.forEach { source ->
                    val start = SystemClock.elapsedRealtimeNanos()
                    val icon = runCatching { loader.load(source)?.drawable }.getOrNull()
                    durations += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    if (icon == null) failures++ else {
                        val inspected = inspectInstalledIconMemory(listOf(icon))
                        bitmaps += inspected.bitmaps
                        bitmapBytes += inspected.bitmapBytes
                    }
                }
                val wallMs = (SystemClock.elapsedRealtimeNanos() - wallStart) / 1_000_000.0
                durations.sort()
                fun percentile(fraction: Double) = durations[((durations.size - 1) * fraction).toInt()]
                assertTrue(durations.isNotEmpty())
                emit(JSONObject().put("phase", "deferred-sequential-source-$pass")
                    .put("entries", entries.size).put("users", users.userProfiles.size)
                    .put("failures", failures).put("wallIncludingInspectionMs", wallMs)
                    .put("acquireTotalMs", durations.sum()).put("p50Ms", percentile(0.50))
                    .put("p95Ms", percentile(0.95)).put("maxMs", durations.last())
                    // Per-read sums deliberately do not deduplicate across acquisitions.
                    .put("perReadBitmapCountSum", bitmaps).put("perReadBitmapBytesSum", bitmapBytes))
            }
            assertTrue(viewModel.runtimeState.value.ready)
            assertEquals(original.sessionToken, viewModel.applicationState.value.sessionToken)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun report(phase: String, value: InstalledIconMemorySnapshot, loadMs: Double? = null) {
        val record = JSONObject().put("phase", phase).put("roots", value.roots)
            .put("scope", "retained-source-drawables")
            .put("drawables", value.drawables).put("bitmaps", value.bitmaps)
            .put("bitmapBytes", value.bitmapBytes).put("hardwareBytes", value.hardwareBytes)
            .put("adaptive", value.adaptiveDrawables).put("vectors", value.vectors)
            .put("opaque", value.opaqueContainers).put("unknown", value.unknownLeaves)
            .put("truncated", value.truncated).put("readFailures", value.readFailures)
        loadMs?.let { record.put("loadMs", it) }
        emit(record)
    }

    private fun emit(record: JSONObject) {
        instrumentation.sendStatus(0, Bundle().apply { putString("installedIconMemory", record.toString()) })
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Catalog startup did not finish" }
            Thread.sleep(50)
        }
    }
}
