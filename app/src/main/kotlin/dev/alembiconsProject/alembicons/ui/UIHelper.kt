package dev.alembiconsProject.alembicons.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.alembiconsProject.alembicons.MainActivity
import dev.alembiconsProject.alembicons.dataStore

/**
 * The hosting [MainActivity], provided at the root by MainActivity itself.
 * Lets composables reach the activity without walking and casting the context
 * chain (the old getCurrentActivity() as MainActivity).
 */
val LocalMainActivity = staticCompositionLocalOf<MainActivity> {
    error("LocalMainActivity not provided")
}

@Composable
fun getCurrentContext(): Context {
    return LocalContext.current.applicationContext
}

@Composable
fun getCurrentMainActivity(): MainActivity = LocalMainActivity.current

@Composable
fun getPreferences(): DataStore<Preferences> {
    return getCurrentContext().dataStore
}

@Composable
fun ShowToast(text: String) {
    // Fire the toast as a side effect so it shows once on appearance instead of
    // on every recomposition while it is in the tree
    val context = LocalContext.current
    LaunchedEffect(text) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}