package dev.renkinProject.renkin.data.transfer

import dev.renkinProject.renkin.data.ActiveProfileIdKey
import dev.renkinProject.renkin.data.DEFAULT_PROFILE_ID
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.ProfilePrefKeys
import dev.renkinProject.renkin.data.ProfileStyle
import dev.renkinProject.renkin.data.ProfileStyleCodec
import dev.renkinProject.renkin.data.ProfileStylePreferences
import dev.renkinProject.renkin.data.isValidPersistedPreference
import dev.renkinProject.renkin.util.Log
import org.json.JSONObject
import java.io.IOException

internal object ProfileStyleBackup {
    private val profileKeys = ProfilePrefKeys.map { it.name }.toSet()

    fun requireSupportedFormat(format: Int) {
        if (format !in 1..BackupCodec.FORMAT_VERSION) throw IOException("Unsupported backup format")
    }

    fun readDocument(profile: JSONObject, format: Int): String {
        if (format <= BackupCodec.LEGACY_FORMAT_VERSION) {
            if (profile.has("profileStyle")) throw IOException("Typed style in a legacy backup")
            return ""
        }
        val document = profile.opt("profileStyle") as? String ?: throw IOException("Missing typed profile style")
        decode(document)
        return document
    }

    fun validatedStyle(profile: Profile): ProfileStyle {
        val style = try {
            decode(profile.profileStyle)
        } catch (error: IOException) {
            val message = "Invalid typed profile style for profile ${profile.id}"
            Log.error("ProfileStyleBackup", message, error)
            throw IOException(message, error)
        }
        val legacy = try {
            ProfileStylePreferences.decodeLegacySnapshot(profile.prefsSnapshot)
        } catch (error: Exception) {
            throw IOException("Invalid profile compatibility copy", error)
        }
        if (style != legacy) throw IOException("Profile style does not match its compatibility copy")
        return style
    }

    fun validateRawPreference(name: String, entry: JSONObject) {
        if (name == ActiveProfileIdKey.name) {
            val value = entry.opt("v")
            if (entry.opt("t") != BackupPref.LONG || (value !is Int && value !is Long)) {
                throw IOException("Invalid active profile identity")
            }
            return
        }
        if (name !in profileKeys) return
        val value = entry.opt("v") ?: throw IOException("Missing profile preference value")
        if (!isValidPersistedPreference(name, value) || BackupPref.of(value)?.tag != entry.opt("t")) {
            throw IOException("Invalid profile preference type")
        }
    }

    fun validateActiveProjection(data: BackupData) {
        val activeId = data.prefs[ActiveProfileIdKey.name]?.value as? Long ?: DEFAULT_PROFILE_ID
        val profile = data.profiles.singleOrNull { it.profile.id == activeId }?.profile
            ?: throw IOException("Invalid active profile")
        val values = JSONObject()
        for ((name, pref) in data.prefs) if (name in profileKeys) values.put(name, pref.value)
        val projected = try {
            ProfileStylePreferences.decodeLegacySnapshot(values.toString())
        } catch (error: Exception) {
            throw IOException("Invalid active profile compatibility copy", error)
        }
        if (decode(profile.profileStyle) != projected) throw IOException("Active profile preferences do not match its style")
    }

    private fun decode(document: String): ProfileStyle = try {
        ProfileStyleCodec.decode(document)
    } catch (error: Exception) {
        throw IOException("Invalid typed profile style", error)
    }
}
