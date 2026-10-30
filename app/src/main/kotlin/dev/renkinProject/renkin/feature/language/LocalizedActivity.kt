package dev.renkinProject.renkin.feature.language

import android.content.Context
import androidx.activity.ComponentActivity

/** Base for every activity so the in-app language also applies before Android 13. */
abstract class LocalizedActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocales.wrap(newBase))
    }
}
