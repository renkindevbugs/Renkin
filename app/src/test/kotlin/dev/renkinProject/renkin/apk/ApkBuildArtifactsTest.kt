package dev.renkinProject.renkin.apk

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ApkBuildArtifactsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun apk(directory: File): Uri = Uri.fromFile(directory.resolve("app-release.apk").apply {
        writeText("signed APK")
    })

    @Test
    fun completedBuildIsKeptUntilNextBuildAndPendingChoiceSurvives() {
        val artifacts = ApkBuildArtifacts(temporary.root)
        val finished = artifacts.create()
        val pending = artifacts.create()
        artifacts.release(apk(finished), completed = true)
        assertTrue(finished.exists())
        val next = artifacts.create()
        assertFalse(finished.exists())
        assertTrue(pending.exists())
        assertNotEquals(pending, next)
    }

    @Test
    fun pendingReplacementIsProtectedEvenWhenOlderThanGracePeriod() {
        val artifacts = ApkBuildArtifacts(temporary.root)
        val pending = artifacts.create()
        apk(pending)
        assertTrue(pending.setLastModified(1L))
        artifacts.create()
        assertTrue(pending.exists())
    }

    @Test
    fun cancelledBuildRemovesPartialOutputImmediately() {
        val artifacts = ApkBuildArtifacts(temporary.root)
        val failed = artifacts.create()
        apk(failed)
        artifacts.abandon(failed)
        assertFalse(failed.exists())
    }

    @Test
    fun timeoutGetsGracePeriodButIsPrunedAfterItExpires() {
        val artifacts = ApkBuildArtifacts(temporary.root)
        val uncertain = artifacts.create()
        artifacts.release(apk(uncertain), completed = false)
        artifacts.create()
        assertTrue(uncertain.exists())
        assertTrue(uncertain.setLastModified(1L))
        artifacts.create()
        assertFalse(uncertain.exists())
    }

    @Test
    fun processRestartPreservesRecentUnknownInstallerAndPrunesOldOrphans() {
        val first = ApkBuildArtifacts(temporary.root)
        val recent = first.create()
        apk(recent)
        val old = first.create()
        apk(old)
        assertTrue(old.setLastModified(1L))
        ApkBuildArtifacts(temporary.root).create()
        assertTrue(recent.exists())
        assertFalse(old.exists())
    }

    @Test
    fun releaseCannotMarkFilesOutsideArtifactRootForRemoval() {
        val artifacts = ApkBuildArtifacts(temporary.root)
        val outside = temporary.newFolder("outside")
        artifacts.release(apk(outside), completed = true)
        artifacts.create()
        assertTrue(outside.resolve("app-release.apk").exists())
        assertFalse(outside.resolve(".finished").exists())
    }

    @Test
    fun exportPinsAReleasedArtifactAgainstPruningUntilTheCopyFinishes() = runBlocking {
        val artifacts = ApkBuildArtifacts(temporary.root)
        val failed = artifacts.create()
        val uri = apk(failed)
        artifacts.release(uri, completed = true)
        val lease = requireNotNull(artifacts.reserveExport(uri))
        val target = ByteArrayOutputStream()
        val copied = artifacts.export(lease) {
            artifacts.create()
            assertTrue(failed.exists())
            target
        }
        assertTrue(copied)
        assertEquals("signed APK", target.toString(Charsets.UTF_8.name()))
        artifacts.create()
        assertFalse(failed.exists())
    }

    @Test
    fun exportReportsAnAlreadyPrunedArtifactWithoutOpeningTheTarget() = runBlocking {
        val artifacts = ApkBuildArtifacts(temporary.root)
        val failed = artifacts.create()
        val uri = apk(failed)
        artifacts.release(uri, completed = true)
        val lease = requireNotNull(artifacts.reserveExport(uri))
        artifacts.releaseExport(lease)
        artifacts.create()
        assertFalse(artifacts.export(lease) { error("A pruned artifact must not open the target") })
    }

    @Test
    fun unavailableTargetReleasesThePin() = runBlocking {
        val artifacts = ApkBuildArtifacts(temporary.root)
        val failed = artifacts.create()
        val uri = apk(failed)
        artifacts.release(uri, completed = true)
        assertFalse(artifacts.export(requireNotNull(artifacts.reserveExport(uri))) { null })
        artifacts.create()
        assertFalse(failed.exists())
    }

    @Test
    fun failedCopyReleasesThePinAndPropagatesTheError() = runBlocking {
        val artifacts = ApkBuildArtifacts(temporary.root)
        val failed = artifacts.create()
        val uri = apk(failed)
        artifacts.release(uri, completed = true)
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
        }
        val lease = requireNotNull(artifacts.reserveExport(uri))
        assertThrows(IOException::class.java) { runBlocking { artifacts.export(lease) { broken } } }
        artifacts.create()
        assertFalse(failed.exists())
    }

    @Test
    fun exportIgnoresFilesOutsideTheArtifactRoot() = runBlocking {
        val artifacts = ApkBuildArtifacts(temporary.root)
        val outside = temporary.newFolder("outside")
        assertTrue(artifacts.reserveExport(apk(outside)) == null)
    }

    @Test
    fun pickerReservationSurvivesPruningAndProcessRestartBeforeCopy() = runBlocking {
        val original = ApkBuildArtifacts(temporary.root)
        val directory = original.create()
        val uri = apk(directory)
        original.release(uri, completed = true)
        val lease = requireNotNull(original.reserveExport(uri))
        val restored = ApkBuildArtifacts(temporary.root)
        repeat(2) { restored.create() }
        assertTrue(directory.exists())
        val target = ByteArrayOutputStream()
        assertTrue(restored.export(lease) { target })
        assertEquals("signed APK", target.toString(Charsets.UTF_8.name()))
        restored.create()
        assertFalse(directory.exists())
    }

    @Test
    fun cancellingOnePickerDoesNotReleaseAnotherPickersReservation() {
        val artifacts = ApkBuildArtifacts(temporary.root)
        val directory = artifacts.create()
        val uri = apk(directory)
        artifacts.release(uri, completed = true)
        val first = requireNotNull(artifacts.reserveExport(uri))
        val second = requireNotNull(artifacts.reserveExport(uri))
        artifacts.releaseExport(first)
        artifacts.create()
        assertTrue(directory.exists())
        artifacts.releaseExport(second)
        artifacts.create()
        assertFalse(directory.exists())
    }

    @Test
    fun abandonedPickerReservationExpiresWithoutKeepingTheApkForever() {
        val artifacts = ApkBuildArtifacts(temporary.root)
        val directory = artifacts.create()
        val uri = apk(directory)
        artifacts.release(uri, completed = true)
        val lease = requireNotNull(artifacts.reserveExport(uri))
        assertTrue(directory.resolve(".export-${lease.token}").setLastModified(1L))
        artifacts.create()
        assertFalse(directory.exists())
    }

    @Test
    fun malformedReservationCannotReadOrRemoveFiles() = runBlocking {
        val artifacts = ApkBuildArtifacts(temporary.root)
        val directory = artifacts.create()
        val uri = apk(directory)
        val invalid = ApkExportLease(uri, "../app-release.apk")
        assertFalse(artifacts.export(invalid) { error("An invalid lease cannot export") })
        artifacts.releaseExport(invalid)
        assertTrue(File(requireNotNull(uri.path)).isFile)
    }
}
