package dev.renkinProject.renkin.apk

import android.app.Application
import android.content.pm.PackageInfo
import dev.renkinProject.renkin.data.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ProfilePackIdentityTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun automaticIdentityKeepsExistingPackagesAndCustomNameOverridesOnlyItsProfile() {
        assertEquals(IconPackBuilder.PACKAGE_NAME, Profile(1, "Default").effectivePackPackageName())
        assertEquals("${IconPackBuilder.PACKAGE_NAME}.p7", Profile(7, "Other").effectivePackPackageName())
        assertEquals("example.personal.icons",
            Profile(7, "Other", customPackageName = "example.personal.icons").effectivePackPackageName())
        validateProfilePackIdentity(context, 7, "", emptyList())
        validateProfilePackIdentity(context, 7, "example.Personal_icons", emptyList())
    }

    @Test
    fun invalidIdentifiersAreRejectedBeforeTheyReachTheBuilder() {
        for (value in listOf("single", ".example.icons", "example..icons", "example.2icons",
            "example.icon-pack", "example.icons ", "example/Icons", "example." + "a".repeat(250))) {
            expect(ProfilePackIdentityError.INVALID_PACKAGE) {
                validateProfilePackIdentity(context, 7, value, emptyList())
            }
        }
    }

    @Test
    fun renkinAndOtherProfilesAutomaticNamesCannotBeClaimed() {
        for (value in listOf(context.packageName, IconPackBuilder.PACKAGE_NAME,
            "${IconPackBuilder.PACKAGE_NAME}.p8")) {
            expect(ProfilePackIdentityError.RESERVED_PACKAGE) {
                validateProfilePackIdentity(context, 7, value, emptyList())
            }
        }
        validateProfilePackIdentity(context, 7, automaticPackPackageName(7), emptyList())
    }

    @Test
    fun customNamesAreUniqueAcrossProfilesButUnchangedOwnerCanSave() {
        val owner = Profile(7, "Owner", customPackageName = "example.personal.icons")
        validateProfilePackIdentity(context, owner.id, owner.customPackageName, listOf(owner))
        expect(ProfilePackIdentityError.PROFILE_COLLISION) {
            validateProfilePackIdentity(context, 8, owner.customPackageName, listOf(owner))
        }
        expect(ProfilePackIdentityError.PROFILE_COLLISION) {
            validateProfilePackIdentity(context, null, owner.customPackageName, listOf(owner))
        }
    }

    @Test
    fun occupiedForeignNameIsRejectedEvenWhenImportedMetadataClaimsIt() {
        val name = "example.foreign.installed"
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = name })
        val imported = Profile(7, "Imported", customPackageName = name)
        expect(ProfilePackIdentityError.INSTALLED_COLLISION) {
            validateProfilePackIdentity(context, imported.id, name, listOf(imported))
        }
    }

    @Test
    fun fullBackupValidationCanIgnoreInstalledPackagesWithoutIgnoringProfileCollisions() {
        val name = "example.restore.target"
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = name })
        validateProfilePackIdentity(context, 7, name, emptyList(), checkInstalled = false)
        expect(ProfilePackIdentityError.PROFILE_COLLISION) {
            validateProfilePackIdentity(context, 7, name,
                listOf(Profile(8, "Other", customPackageName = name)), checkInstalled = false)
        }
    }

    private fun expect(reason: ProfilePackIdentityError, action: () -> Unit) {
        try {
            action()
            fail("Expected package identity rejection: $reason")
        } catch (error: ProfilePackIdentityException) {
            assertEquals(reason, error.reason)
        }
    }
}
