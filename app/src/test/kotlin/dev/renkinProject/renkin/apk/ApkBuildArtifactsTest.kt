package dev.renkinProject.renkin.apk

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

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
}
