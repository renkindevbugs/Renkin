package com.kaanelloed.iconeration.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val DataStore<Preferences>.NightThemeKey: Preferences.Key<String>
    get() = stringPreferencesKey("")

fun <T : Any> DataStore<Preferences>.getPrefs(key: Preferences.Key<T>): Flow<T?> {
    return data.map {settings ->
        settings[key]
    }
}

suspend fun <T> DataStore<Preferences>.setPrefs(key: Preferences.Key<T>, value: T) {
    edit {settings ->
        settings[key] = value
    }
}