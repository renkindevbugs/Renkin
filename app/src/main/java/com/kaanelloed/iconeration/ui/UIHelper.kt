package com.kaanelloed.iconeration.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.kaanelloed.iconeration.dataStore

@Composable
fun getCurrentContext(): Context {
    return LocalContext.current.applicationContext
}

@Composable
fun getCurrentActivity(): Activity {
    var context = getCurrentContext()

    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }

    throw IllegalStateException("No Activity")
}

@Composable
fun getPreferences(): DataStore<Preferences> {
    return getCurrentContext().dataStore
}