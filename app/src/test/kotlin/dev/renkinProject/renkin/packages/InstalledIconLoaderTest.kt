package dev.renkinProject.renkin.packages

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InstalledIconLoaderTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun descriptor(configuration: Configuration = context.resources.configuration) =
        ApplicationIconSource.Installed(ComponentName("fixture", "fixture.Main"),
            Process.myUserHandle(), "/fixture/base.apk", listOf("/fixture/split.apk"), 1, configuration)

    @Test
    fun configurationSnapshotCannotBeChangedByCaller() {
        val original = Configuration(context.resources.configuration).apply { densityDpi = 240 }
        val source = descriptor(original)
        original.densityDpi = 640
        source.configuration().densityDpi = 120
        assertEquals(240, source.configuration().densityDpi)
        assertNull(source.retainedDrawableOrNull())
        assertFalse(source.previewIdentity is ApplicationIconSource)
        assertFalse(source.previewIdentity is Drawable)
    }

    @Test
    fun preparedEditorSourceDoesNotReplaceCatalogOrChangeBaselineVersion() {
        val source = descriptor()
        val original = PackageInfoStruct("Fixture", "fixture", "fixture.Main", iconID = 10,
            internalVersion = 7, iconSource = source)
        val loaded = LoadedApplicationIcon(BlockingIcon(block = false), 42)
        val prepared = original.withLoadedApplicationIcon(loaded)
        val loader = InstalledIconLoader(context)

        assertSame(source, original.iconSource)
        assertNull(original.iconSource.retainedDrawableOrNull())
        assertEquals(original, prepared)
        assertEquals(7, prepared.internalVersion)
        assertSame(loaded, loader.load(prepared.iconSource))
        assertSame(source, original.locked().iconSource)
    }

    private fun activity(info: ActivityInfo, user: UserHandle) = ReflectionHelpers.callConstructor(
        LauncherActivityInfo::class.java,
        ClassParameter.from(Context::class.java, context),
        ClassParameter.from(ActivityInfo::class.java, info),
        ClassParameter.from(UserHandle::class.java, user)
    )

    private fun activityInfo(iconId: Int, path: String = "/fixture/base.apk") = ActivityInfo().apply {
        name = "fixture.Main"
        packageName = "fixture"
        icon = iconId
        applicationInfo = ApplicationInfo().apply {
            packageName = "fixture"
            sourceDir = path
            icon = 10
        }
    }

    @Test
    fun pre31PersonalActivityKeepsItsOwnResourceId() {
        val info = activityInfo(42)
        shadowOf(context.packageManager).addOrUpdateActivity(info)
        assertEquals(42, launcherActivityIconId(context, activity(info, Process.myUserHandle())))
    }

    @Test
    fun pre31WorkActivityUsesItsArchiveRatherThanPersonalActivity() {
        shadowOf(context.packageManager).addOrUpdateActivity(activityInfo(11))
        val work = activityInfo(42, "/work/base.apk")
        shadowOf(context.packageManager).setPackageArchiveInfo(work.applicationInfo.sourceDir,
            PackageInfo().apply { activities = arrayOf(work) })
        val user = ReflectionHelpers.callConstructor(UserHandle::class.java, ClassParameter.from(Int::class.javaPrimitiveType, 10))
        assertEquals(42, launcherActivityIconId(context, activity(work, user)))
    }

    @Test
    fun pre31MissingWorkArchiveFallsBackToWorkApplicationId() {
        shadowOf(context.packageManager).addOrUpdateActivity(activityInfo(11))
        val work = activityInfo(42, "/unavailable/work.apk")
        val user = ReflectionHelpers.callConstructor(UserHandle::class.java, ClassParameter.from(Int::class.javaPrimitiveType, 10))
        assertEquals(10, launcherActivityIconId(context, activity(work, user)))
    }

    @Test
    fun cancelledOwnerAllowsSurvivingWaiterToRetry() = runBlocking {
        val icon = BlockingIcon()
        val source = ApplicationIconSource.Inline(icon, 1)
        val loader = InstalledIconLoader(context)
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { loader.preview(source, 16) }
        var waiter: kotlinx.coroutines.Deferred<android.graphics.Bitmap?>? = null
        try {
            withTimeout(15_000) { icon.entered.await() }
            waiter = async(start = CoroutineStart.UNDISPATCHED) { loader.preview(source, 16) }
            owner.cancel()
            icon.release.countDown()
            val result = withTimeout(5_000) { waiter.await() }
            assertNotNull(result)
            assertSame(result, loader.cachedPreview(source, 16))
            assertEquals(2, icon.draws.get())
        } finally {
            icon.release.countDown()
            owner.cancel()
            waiter?.cancel()
        }
    }

    @Test
    fun cancelledWaiterDoesNotCancelSharedOwnerOrRecycleItsPixels() = runBlocking {
        val icon = BlockingIcon()
        val source = ApplicationIconSource.Inline(icon, 1)
        val loader = InstalledIconLoader(context)
        val owner = async(start = CoroutineStart.UNDISPATCHED) { loader.preview(source, 16) }
        try {
            withTimeout(15_000) { icon.entered.await() }
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { loader.preview(source, 16) }
            waiter.cancel()
            icon.release.countDown()
            val result = withTimeout(5_000) { owner.await() }
            assertNotNull(result)
            assertFalse(result!!.isRecycled)
            assertSame(result, loader.preview(source, 16))
            assertEquals(1, icon.draws.get())
        } finally {
            icon.release.countDown()
            owner.cancel()
        }
    }

    private class BlockingIcon(private val block: Boolean = true) : Drawable() {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val draws = AtomicInteger()
        override fun getIntrinsicWidth() = 16
        override fun getIntrinsicHeight() = 16
        override fun draw(canvas: Canvas) {
            if (draws.incrementAndGet() == 1 && block) {
                entered.complete(Unit)
                check(release.await(15, TimeUnit.SECONDS))
            }
            canvas.drawColor(Color.RED)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("deprecated in Drawable")
        override fun getOpacity() = PixelFormat.OPAQUE
    }
}
