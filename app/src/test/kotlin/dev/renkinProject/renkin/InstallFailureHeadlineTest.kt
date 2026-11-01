package dev.renkinProject.renkin

import org.junit.Assert.assertEquals
import org.junit.Test

class InstallFailureHeadlineTest {
    @Test
    fun plainInstallerMessageIsKeptAsIs() {
        assertEquals("User rejected permissions", installFailureHeadline("User rejected permissions\nsecond line"))
    }

    @Test
    fun stackTraceShowsInnermostCauseWithoutPackage() {
        val detail = """
            java.lang.IllegalStateException: Session failed
                at com.example.Installer.commit(Installer.kt:10)
            Caused by: java.io.IOException: Write failed
                at com.example.Io.write(Io.kt:5)
            Caused by: android.system.ErrnoException: write failed: ENOSPC (No space left on device)
                at libcore.io.Linux.write(Native Method)
        """.trimIndent()
        assertEquals("ErrnoException: write failed: ENOSPC (No space left on device)", installFailureHeadline(detail))
    }

    @Test
    fun firstLineLosesExceptionPackageWhenThereIsNoCause() {
        assertEquals("SecurityException: Permission denied",
            installFailureHeadline("\n  java.lang.SecurityException: Permission denied\n\tat x.y.Z.a(Z.kt:1)"))
    }

    @Test
    fun uppercaseInstallerCodesAreNotTreatedAsPackages() {
        assertEquals("INSTALL_FAILED_INSUFFICIENT_STORAGE: Failed to override installation location",
            installFailureHeadline("INSTALL_FAILED_INSUFFICIENT_STORAGE: Failed to override installation location"))
    }

    @Test
    fun headlineIsCappedAt300Characters() {
        assertEquals(300, installFailureHeadline("x".repeat(500)).length)
    }
}
