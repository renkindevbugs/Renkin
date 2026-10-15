package dev.renkinProject.renkin

import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import dev.renkinProject.renkin.apk.ApkExportLease

internal class PendingApkExportState(private val savedState: SavedStateHandle) {
    var lease: ApkExportLease?
        get() = savedState.get<Bundle>(KEY)?.let { bundle ->
            val source = bundle.getString("source") ?: return@let null
            val token = bundle.getString("token") ?: return@let null
            ApkExportLease(Uri.parse(source), token)
        }
        set(value) {
            savedState[KEY] = value?.let { lease ->
                Bundle().apply {
                    putString("source", lease.source.toString())
                    putString("token", lease.token)
                }
            }
        }

    private companion object {
        const val KEY = "pendingApkExport"
    }
}
