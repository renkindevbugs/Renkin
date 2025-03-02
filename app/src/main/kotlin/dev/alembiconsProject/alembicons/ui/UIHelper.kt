package dev.alembiconsProject.alembicons.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.alembiconsProject.alembicons.MainActivity
import dev.alembiconsProject.alembicons.dataStore

@Composable
fun getCurrentContext(): Context {
    return LocalContext.current.applicationContext
}

@Composable
fun getCurrentActivity(): Activity {
    var context = LocalContext.current

    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }

    throw IllegalStateException("No Activity")
}

@Composable
fun getCurrentMainActivity(): MainActivity {
    return getCurrentActivity() as MainActivity
}

@Composable
fun getPreferences(): DataStore<Preferences> {
    return getCurrentContext().dataStore
}

@Composable
fun ShowToast(text: String) {
    Toast.makeText(LocalContext.current, text, Toast.LENGTH_LONG).show()
}