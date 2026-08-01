package dev.renkinProject.renkin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import dev.renkinProject.renkin.ui.CrashLogsScreen
import dev.renkinProject.renkin.ui.LocalToaster
import dev.renkinProject.renkin.ui.ToastHost
import dev.renkinProject.renkin.ui.Toaster
import dev.renkinProject.renkin.ui.theme.RenkinTheme

/**
 * The crash logs on their own, reachable from a launcher shortcut. Deliberately independent of
 * [MainActivity]: when a startup failure is what needs reporting, the screen that reports it must
 * not need the thing that failed. It only reads files — no database, no icon packs, no view model.
 */
class CrashLogsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val toaster = Toaster()
        setContent {
            RenkinTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CompositionLocalProvider(LocalToaster provides toaster) {
                        ToastHost(toaster)
                        CrashLogsScreen(onDismiss = { finish() })
                    }
                }
            }
        }
    }
}
