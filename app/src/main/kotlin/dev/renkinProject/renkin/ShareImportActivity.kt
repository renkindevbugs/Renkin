package dev.renkinProject.renkin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import dev.renkinProject.renkin.feature.language.LocalizedActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.renkinProject.renkin.data.SharedImageImporter
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives images shared into Renkin (e.g. back from an external editor) without starting a
 * second MainActivity in the sender's task. It brings Renkin's existing task forward the way the
 * launcher icon does, so an open editor or Global options screen keeps its state.
 */
@AndroidEntryPoint
class ShareImportActivity : LocalizedActivity() {
    @Inject
    lateinit var importer: SharedImageImporter

    private var imported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        imported = savedInstanceState?.getBoolean(STATE_IMPORTED) == true
        val uri = sharedImageUri(intent)
        lifecycleScope.launch {
            // The URI grant can end with this activity, so the copy finishes before it does.
            if (uri != null && !imported) importer.import(uri)
            imported = true
            packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_IMPORTED, imported)
        super.onSaveInstanceState(outState)
    }

    private fun sharedImageUri(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return null
        return IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    }
}

private const val STATE_IMPORTED = "sharedImageImported"
