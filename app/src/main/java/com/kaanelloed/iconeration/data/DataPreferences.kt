package com.kaanelloed.iconeration.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

const val DARK_MODE_NAME = "NIGHT_THEME"
val DARK_MODE_DEFAULT = DarkMode.FOLLOW_SYSTEM

val DarkModeKey: Preferences.Key<Int>
    get() = intPreferencesKey(DARK_MODE_NAME)

val DarkModeLabels = mapOf(DarkMode.FOLLOW_SYSTEM to "Follow System"
    , DarkMode.DARK to "Dark Mode"
    , DarkMode.LIGHT to "Light Mode")

fun DataStore<Preferences>.getDarkMode(): Flow<Int?> {
    return getPrefs(DarkModeKey)
}

@Composable
fun DataStore<Preferences>.getDarkModeValue(): DarkMode {
    val index = getDarkMode().collectAsState(initial = 0).value ?: DARK_MODE_DEFAULT.ordinal
    return DarkMode.entries[index]
}


suspend fun DataStore<Preferences>.setDarkMode(value: Int) {
    setPrefs(DarkModeKey, value)
}

suspend fun DataStore<Preferences>.setDarkMode(value: DarkMode) {
    setDarkMode(value.ordinal)
}

fun <T : Any> DataStore<Preferences>.getPrefs(key: Preferences.Key<T>): Flow<T?> {
    return data.map { settings ->
        settings[key]
    }
}

suspend fun <T> DataStore<Preferences>.setPrefs(key: Preferences.Key<T>, value: T) {
    edit { settings ->
        settings[key] = value
    }
}

enum class DarkMode {
    FOLLOW_SYSTEM, DARK, LIGHT
}