package dev.renkinProject.renkin.feature.buildinstall

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.MainViewModel
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Borrows the live home owner; constructing a new MainViewModel would reload unsaved icons. */
internal object PackPreviewEditorOwners {
    private val owners = ConcurrentHashMap<String, WeakReference<MainViewModel>>()

    fun register(owner: MainViewModel): String = UUID.randomUUID().toString().also {
        owners[it] = WeakReference(owner)
        owner.packPreviewOpen = true
    }

    fun find(id: String?): MainViewModel? = id?.let { owners[it]?.get() }

    fun release(id: String?) {
        id?.let { owners.remove(it)?.get()?.packPreviewOpen = false }
    }
}

internal val LocalIconEditorOwner = staticCompositionLocalOf<MainViewModel?> { null }

@Composable
internal fun iconEditorViewModel(): MainViewModel = LocalIconEditorOwner.current ?: hiltViewModel()
