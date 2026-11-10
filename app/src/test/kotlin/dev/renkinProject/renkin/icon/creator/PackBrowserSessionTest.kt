package dev.renkinProject.renkin.icon.creator

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.RawElement
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.packages.NamedResourceDrawable
import dev.renkinProject.renkin.packages.PackBrowserDataSource
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PackBrowserSessionTest {
    @get:Rule val testName = TestName()
    private val context get() = RuntimeEnvironment.getApplication()
    private val owners = mutableListOf<PackBrowserSession>()
    private val indexes = mutableListOf<PackBrowserIndex>()
    private fun pack(name: String = "fixture.${testName.methodName.lowercase()}") = IconPack(name, name, 1, "1", 0)
    private val options = GenerationOptions(
        source = IconSourceOptions(primarySource = Source.ICON_PACK),
        modifiers = ModifierStack(EffectModifier(ImageEdit.NONE, ColorizerStyle(firstColor = Color.BLACK))),
        export = IconExportOptions(),
        override = true
    )

    @After fun closeOwners() {
        owners.forEach { it.close() }
        indexes.forEach { it.close() }
    }

    private class Catalog : PackBrowserDataSource {
        val names = ConcurrentHashMap<String, List<String>>()
        val ids = ConcurrentHashMap<String, Map<String, Int>>()
        val reads = ConcurrentHashMap<String, AtomicInteger>()
        val drawableReads = AtomicInteger()
        val idReads = AtomicInteger()
        @Volatile var read: (String) -> List<String> = { names[it].orEmpty() }

        fun put(pack: IconPack, vararg entries: Pair<String, Int>) {
            names[pack.packageName] = entries.map { it.first }
            ids[pack.packageName] = entries.toMap()
        }

        override fun getIconPackDrawableNames(iconPackName: String): List<String> {
            reads.getOrPut(iconPackName) { AtomicInteger() }.incrementAndGet()
            return read(iconPackName)
        }

        override fun getIconPackDrawableIds(iconPackName: String, drawableNames: List<String>): Map<String, Int> {
            idReads.addAndGet(drawableNames.size)
            return drawableNames.associateWith { ids[iconPackName]?.get(it) ?: 0 }
        }

        override fun getIconPackDrawableEntries(iconPackName: String, drawableNames: List<String>): List<NamedResourceDrawable> {
            drawableReads.incrementAndGet()
            return drawableNames.mapNotNull { name ->
                ids[iconPackName]?.get(name)?.takeIf { it > 0 }?.let {
                    NamedResourceDrawable(name, ResourceDrawable(it, ColorDrawable(Color.WHITE)))
                }
            }
        }

        override fun getAppFilterRawElements(iconPackName: String, applications: List<InstalledApplication>): List<RawElement> = emptyList()
    }

    private fun session(source: Catalog, exact: List<String> = emptyList(), renders: AtomicInteger = AtomicInteger()): PackBrowserSession {
        val index = PackBrowserIndex(context, source)
        return PackBrowserSession(index, source, { _, _ -> exact }) { _, _, _, size ->
            renders.incrementAndGet()
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        }.also { owners.add(it) }
    }

    private suspend fun results(session: PackBrowserSession, packs: List<IconPack>, query: String = "",
        component: InstalledApplication? = null, sort: IconSortOrder = IconSortOrder.NAME_ASC): Map<String, PackSearchResult> =
        withTimeout(10_000) {
            session.search(packs, sort, query, component).first { state -> packs.all { state[it.packageName]?.complete == true } }
        }

    @Test fun missingReferencesBecomeEmptyResultsInsteadOfUnavailableTiles() = runBlocking {
        val pack = pack()
        val source = Catalog().apply { put(pack, "reddit" to 0, "reddit_alt" to 0) }
        val session = session(source, listOf("reddit"))
        val state = results(session, listOf(pack), "reddit", InstalledApplication("com.reddit", "Main", 0)).getValue(pack.packageName)

        assertTrue(state.complete)
        assertFalse(state.failed)
        assertTrue(state.names.isEmpty())
        assertEquals(0, source.drawableReads.get())
    }

    @Test fun allPacksAreSearchedWithoutLoadingAnyDrawablesAndNamesAreReused() = runBlocking {
        val first = pack("fixture.first")
        val last = pack("fixture.last")
        val source = Catalog().apply {
            put(first, "calendar_10" to 10, "calendar_2" to 2)
            put(last, "reddit" to 3)
        }
        val renders = AtomicInteger()
        val session = session(source, renders = renders)

        assertEquals(listOf("reddit"), results(session, listOf(first, last), "reddit").getValue(last.packageName).names)
        assertEquals(listOf("calendar_2", "calendar_10"), results(session, listOf(first, last)).getValue(first.packageName).names)
        assertEquals(1, source.reads.getValue(first.packageName).get())
        assertEquals(1, source.reads.getValue(last.packageName).get())
        assertEquals(0, source.drawableReads.get())
        assertEquals(0, renders.get())
    }

    @Test fun exactAlternativesArePublishedBeforeBlockedNameIndexCompletes() = runBlocking {
        val pack = pack()
        val release = CountDownLatch(1)
        val source = Catalog().apply {
            put(pack, "named_differently" to 1, "alternate" to 2, "reddit" to 3)
            read = { name ->
                check(release.await(10, TimeUnit.SECONDS))
                names.getValue(name)
            }
        }
        val session = session(source, listOf("named_differently", "alternate"))
        val early = CompletableDeferred<PackSearchResult>()
        val final = CompletableDeferred<PackSearchResult>()
        val collector = launch(Dispatchers.Default) {
            session.search(listOf(pack), IconSortOrder.NAME_ASC, "reddit", InstalledApplication("app", "Main", 0)).collect { state ->
                state[pack.packageName]?.let {
                    if (!it.complete && it.names.isNotEmpty()) early.complete(it)
                    if (it.complete) final.complete(it)
                }
            }
        }
        try {
            assertEquals(listOf("named_differently", "alternate"), withTimeout(10_000) { early.await() }.names)
            assertFalse(final.isCompleted)
            assertEquals(0, source.drawableReads.get())
            release.countDown()
            assertEquals(listOf("named_differently", "alternate", "reddit"), withTimeout(10_000) { final.await() }.names)
        } finally {
            release.countDown()
            collector.cancelAndJoin()
        }
    }

    @Test fun brokenPackDoesNotHideHealthyPackOrPretendSearchIsEmpty() = runBlocking {
        val broken = pack("fixture.broken")
        val healthy = pack("fixture.healthy")
        val source = Catalog().apply {
            put(healthy, "reddit" to 1)
            read = { if (it == broken.packageName) error("Malformed XML") else names.getValue(it) }
        }
        val state = results(session(source), listOf(broken, healthy), "reddit")

        assertTrue(state.getValue(broken.packageName).failed)
        assertEquals(listOf("reddit"), state.getValue(healthy.packageName).names)
    }

    @Test fun metadataDoesNotResolveTheEntireLargePackOrGenerateItsFourHundredIcons() = runBlocking {
        val pack = pack()
        val entries = (1..2000).map { "icon_$it" to it }.toTypedArray()
        val source = Catalog().apply { put(pack, *entries) }
        val state = results(session(source), listOf(pack)).getValue(pack.packageName)

        assertEquals(2000, state.names.size)
        assertTrue(source.idReads.get() < entries.size / 2)
        assertEquals(0, source.drawableReads.get())
    }

    @Test fun rowAndDetailReuseThumbnailAcrossPrimaryPackSelectionAndClearRejectsOldDescriptor() = runBlocking {
        val pack = pack()
        val source = Catalog().apply { put(pack, "reddit" to 1) }
        val renders = AtomicInteger()
        val session = session(source, renders = renders)
        val identity = results(session, listOf(pack)).getValue(pack.packageName).identity
        val first = session.thumbnail(pack, identity, "reddit", options, 96)
        val changedSelection = options.withSource { copy(primaryIconPack = "another.pack") }

        assertNotNull(first)
        assertSame(first, session.thumbnail(pack, identity, "reddit", changedSelection, 96))
        assertEquals(1, renders.get())
        session.clear()
        assertNull(session.cachedThumbnail(pack, identity, "reddit", options, 96))
        assertNull(session.resource(pack, identity, "reddit"))
    }

    @Test fun sameVersionReinstallInvalidatesIndexAndOldResourceSelection() = runBlocking {
        val pack = pack()
        fun install(stamp: Long) {
            shadowOf(context.packageManager).installPackage(PackageInfo().apply {
                packageName = pack.packageName
                versionCode = 1
                lastUpdateTime = stamp
                applicationInfo = ApplicationInfo().apply { packageName = pack.packageName; sourceDir = "/fixture/base.apk" }
            })
        }
        install(10)
        val source = Catalog().apply { put(pack, "old" to 1) }
        val session = session(source)
        val old = results(session, listOf(pack)).getValue(pack.packageName)
        install(20)
        source.put(pack, "new" to 2)

        val updated = results(session, listOf(pack)).getValue(pack.packageName)
        assertNotEquals(old.identity, updated.identity)
        assertEquals(listOf("new"), updated.names)
        assertNull(session.resource(pack, old.identity, "old"))
    }

    @Test fun persistentNameIndexCanBeReadByANewOwnerWithoutReparsingXml() = runBlocking {
        val pack = pack("fixture.persisted")
        val source = Catalog().apply { put(pack, "calendar_10" to 10, "calendar_2" to 2) }
        val first = PackBrowserIndex(context, source).also { indexes.add(it) }
        val expected = first.names(pack)
        withTimeout(10_000) {
            while (File(context.cacheDir, "pack-browser-index-v1").listFiles().orEmpty().none {
                it.extension == "bin" && it.readBytes().toString(Charsets.UTF_8).contains(pack.packageName)
            }) delay(10)
        }
        val noXml = Catalog().apply { read = { error("XML must not be opened for a valid persisted index") } }
        val second = PackBrowserIndex(context, noXml).also { indexes.add(it) }

        assertEquals(expected, second.names(pack))
        assertTrue(noXml.reads.isEmpty())
    }

    @Test fun adaptiveThumbnailUsesSmallNativeRenderAndRestoresSourceBounds() {
        var largestLayer = 0
        val foreground = object : Drawable() {
            override fun draw(canvas: Canvas) { largestLayer = maxOf(largestLayer, bounds.width(), bounds.height()); canvas.drawColor(Color.WHITE) }
            override fun setAlpha(alpha: Int) = Unit
            override fun setColorFilter(colorFilter: ColorFilter?) = Unit
            @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
        }
        val source = AdaptiveIconDrawable(ColorDrawable(Color.BLUE), foreground).apply { bounds = Rect(1, 2, 80, 90) }
        val bounds = Rect(source.bounds)
        val container = IconPackContainer("", emptyMap())
        val generator = IconGenerator(context, options, container, container)

        val bitmap = generator.packBrowserBitmap("fixture.pack", ResourceDrawable(1, source), 96)

        assertNotNull(bitmap)
        assertEquals(96, bitmap!!.width)
        assertTrue("A thumbnail must not capture 750px export layers", largestLayer in 1..192)
        assertEquals(bounds, source.bounds)
    }
}
