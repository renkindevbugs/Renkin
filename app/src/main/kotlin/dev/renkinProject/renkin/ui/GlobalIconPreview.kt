package dev.renkinProject.renkin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class GlobalIconPreviewState(
    val icon: IconPackDrawable?,
    val generating: Boolean,
    val canApply: Boolean
)

private data class GlobalIconPreviewRequest(
    val source: IconPackDrawable?,
    val options: GenerationOptions,
    val owner: ProfileSessionToken?,
    val active: Boolean
)

private data class GlobalIconPreviewResult(
    val request: GlobalIconPreviewRequest,
    val icon: IconPackDrawable?
)

@Composable
internal fun rememberGlobalIconPreview(
    source: IconPackDrawable?,
    options: GenerationOptions,
    owner: ProfileSessionToken?,
    active: Boolean,
    render: suspend (IconPackDrawable, GenerationOptions) -> IconPackDrawable
): GlobalIconPreviewState {
    // Capture before the asynchronous source arrives, including edits made while it loads.
    val openingOptions = remember(owner) { options }
    val request = remember(source, options, owner, active) {
        GlobalIconPreviewRequest(source, options, owner, active)
    }
    val currentRender by rememberUpdatedState(render)
    val result by produceState<GlobalIconPreviewResult?>(null, request) {
        val input = request.source ?: return@produceState
        if (!request.active || request.options == openingOptions) return@produceState
        val rendered = try {
            val renderRequest = currentRender
            withContext(Dispatchers.Default) { renderRequest(input, request.options) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("GlobalIconPreview", "Could not render the edited icon", error)
            null
        }
        value = GlobalIconPreviewResult(request, rendered)
    }
    // produceState retains its last value until the next effect runs. An old result must not
    // enable Apply after a control, source or session changes, even for that first frame.
    val accepted = result?.takeIf { it.request === request }
    val edited = options != openingOptions
    val ready = active && source != null && edited
    return GlobalIconPreviewState(
        icon = if (ready) accepted?.icon ?: source else source,
        generating = ready && accepted == null,
        canApply = ready && accepted?.icon != null && accepted.icon !== source
    )
}
