package dev.renkinProject.renkin.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.preferencesDataStore
import dev.renkinProject.renkin.util.Log

/**
 * Set only in the empty replacement for an unreadable settings file. It survives a process death
 * before startup consumes it, so [ProfileStyleStore.initialize] never mistakes the reset values
 * for the active profile's live settings and overwrites its stored snapshot with them.
 */
internal val SettingsReplacedAfterCorruptionKey = booleanPreferencesKey("SETTINGS_REPLACED_AFTER_CORRUPTION")

// Device settings are rebuildable; without a handler a corrupt file fails every read and leaves
// storage recovery stuck. Plain I/O errors are not corruption and still reach recovery.
internal val settingsCorruptionHandler = ReplaceFileCorruptionHandler<Preferences> { error ->
    Log.error("SettingsDataStore", "Settings file is corrupt; replacing it with defaults", error)
    mutablePreferencesOf(SettingsReplacedAfterCorruptionKey to true)
}

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = settingsCorruptionHandler
)
