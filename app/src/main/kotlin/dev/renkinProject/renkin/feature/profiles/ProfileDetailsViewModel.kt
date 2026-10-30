package dev.renkinProject.renkin.feature.profiles

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.data.ProfilePackIcon
import dev.renkinProject.renkin.data.ProfilePackIconCodec
import dev.renkinProject.renkin.icon.creator.IconModifierInput
import dev.renkinProject.renkin.icon.creator.VerifiedModifierInput
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.packages.ApplicationIconSource
import dev.renkinProject.renkin.packages.InstalledIconLoader
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.ui.EditorPurpose
import dev.renkinProject.renkin.ui.EditorSession
import dev.renkinProject.renkin.ui.prepareEditorApplicationIcon
import dev.renkinProject.renkin.util.Log
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ProfileDetailsSubmission(
    val name: String,
    val description: String,
    val packLabel: String,
    val customPackageName: String,
    val packIcon: String
)

@HiltViewModel
class ProfileDetailsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installedIcons: InstalledIconLoader
) : ViewModel() {
    private val codec = ProfilePackIconCodec(context)
    private var key by mutableStateOf<String?>(null)
    private var artwork: ProfilePackIcon? = null
    private var work: Job? = null
    private var restoreWork: Job? = null

    var name by mutableStateOf("")
        private set
    var description by mutableStateOf("")
        private set
    var packLabel by mutableStateOf("")
        private set
    var customPackageName by mutableStateOf("")
        private set
    private var automaticPackageName = ""
    internal val hasCustomPackageName: Boolean
        get() = customPackageName.trim() != automaticPackageName
    var advanced by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var errorRes by mutableStateOf<Int?>(null)
        private set
    internal var editor by mutableStateOf<EditorSession?>(null)
        private set
    internal var preview by mutableStateOf<IconPackDrawable?>(null)
        private set
    internal var pendingSubmission: ProfileDetailsSubmission? = null
    private var encodedIcon by mutableStateOf("")
    internal val hasCustomIcon: Boolean get() = encodedIcon.isNotEmpty()

    internal fun owns(key: String): Boolean = this.key == key

    internal fun start(
        key: String, name: String, description: String, packLabel: String,
        customPackageName: String, packIcon: String, automaticPackageName: String
    ) {
        if (this.key == key) return
        discard()
        this.key = key
        this.name = name
        this.description = description
        this.packLabel = packLabel
        this.automaticPackageName = automaticPackageName
        this.customPackageName = customPackageName.ifEmpty { automaticPackageName }
        encodedIcon = packIcon
        busy = packIcon.isNotEmpty()
        if (!busy) return
        work = viewModelScope.launch {
            try {
                val decoded = codec.decode(packIcon)
                if (this@ProfileDetailsViewModel.key == key) {
                    artwork = decoded
                    preview = decoded?.icon
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.error("ProfileDetailsViewModel", "Profile icon could not be loaded", error)
                if (this@ProfileDetailsViewModel.key == key) errorRes = R.string.profileIconLoadFailed
            } catch (error: OutOfMemoryError) {
                Log.error("ProfileDetailsViewModel", "Profile icon exceeded available memory", error)
                if (this@ProfileDetailsViewModel.key == key) errorRes = R.string.profileIconLoadFailed
            } finally {
                if (this@ProfileDetailsViewModel.key == key) busy = false
            }
        }
    }

    fun editName(value: String) { name = value.take(24) }
    fun editDescription(value: String) { description = value.take(60) }
    fun editPackLabel(value: String) { packLabel = value.take(30) }
    fun editPackageName(value: String) { customPackageName = value.take(255) }
    fun resetPackageName() { customPackageName = automaticPackageName }
    fun toggleAdvanced() { advanced = !advanced }

    internal fun submission() = ProfileDetailsSubmission(
        name.trim(), description.trim(), packLabel.trim(),
        customPackageName.trim().takeUnless { it == automaticPackageName }.orEmpty(), encodedIcon
    )

    fun resetIcon() {
        if (busy) return
        artwork = null
        preview = null
        encodedIcon = ""
        errorRes = null
    }

    internal fun openEditor(owner: ProfileSessionToken, initialPack: String) {
        if (busy || editor != null) return
        val original = requireNotNull(ContextCompat.getDrawable(context, R.drawable.xxxhdpi_ic_launcher))
        val saved = artwork
        // This context supplies names and a baseline to the shared renderer; it is never an app-list row.
        val target = PackageInfoStruct(
            appName = packLabel.ifBlank { name }.ifBlank { context.getString(R.string.profilePackLabel) },
            packageName = context.packageName,
            activityName = "ProfileIcon",
            iconID = 0,
            iconSource = ApplicationIconSource.Inline(original, 0),
            createdIcon = saved?.icon,
            baseIcon = saved?.icon,
            sourcePackName = saved?.sourcePackName,
            sourceUrl = saved?.sourceUrl,
            modifierRecipe = saved?.modifierRecipe,
            modifierInput = saved?.modifierInput
        )
        val state = EditorSession(owner, target, initialPack, "", purpose = EditorPurpose.PROFILE_ICON)
        editor = state
        work = viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.Default) { prepareEditorApplicationIcon(target, installedIcons) }
                if (editor === state) state.applicationIcon = prepared
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.error("ProfileDetailsViewModel", "Profile icon source could not be prepared", error)
            } catch (error: OutOfMemoryError) {
                Log.error("ProfileDetailsViewModel", "Profile icon source exceeded available memory", error)
            } finally {
                if (editor === state) state.applicationIconPrepared = true
            }
        }
    }

    internal fun confirmEditor(
        icon: IconPackDrawable?, sourcePackName: String?, sourceUrl: String?,
        modifierRecipe: String?, modifierInput: IconModifierInput?
    ) {
        val state = editor ?: return
        if (busy) return
        val ownerKey = key
        busy = true
        errorRes = null
        work?.cancel()
        work = viewModelScope.launch {
            try {
                val encoded = codec.encode(icon, sourcePackName, sourceUrl, modifierRecipe, modifierInput)
                val detached = codec.decode(encoded)
                if (key == ownerKey && editor === state) {
                    encodedIcon = encoded
                    artwork = detached
                    preview = detached?.icon
                    editor = null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.error("ProfileDetailsViewModel", "Profile icon could not be retained", error)
                if (key == ownerKey && editor === state) errorRes = R.string.profileIconSaveFailed
            } catch (error: OutOfMemoryError) {
                Log.error("ProfileDetailsViewModel", "Profile icon exceeded available memory while saving", error)
                if (key == ownerKey && editor === state) errorRes = R.string.profileIconSaveFailed
            } finally {
                if (key == ownerKey) busy = false
            }
        }
    }

    internal fun closeEditor() {
        if (busy) return
        restoreWork?.cancel()
        work?.cancel()
        editor = null
    }

    internal fun restoreModifiers() {
        val state = editor ?: return
        val retained = state.application.modifierInput ?: return
        val input = retained.liveInput ?: return
        val base = state.application.baseIcon ?: return
        cancelModifierRestore()
        state.restoringModifiers = true
        state.modifierRestoreFailed = false
        restoreWork = viewModelScope.launch {
            try {
                val verified = VerifiedModifierInput.verify(context.resources, input, retained, base)
                if (editor === state) {
                    if (verified == null) state.modifierRestoreFailed = true
                    else state.restoreModifiers(verified)
                }
            } finally {
                if (editor === state) state.restoringModifiers = false
            }
        }
    }

    internal fun cancelModifierRestore() {
        restoreWork?.cancel()
        editor?.restoringModifiers = false
    }

    internal fun discard() {
        work?.cancel()
        restoreWork?.cancel()
        key = null
        editor = null
        artwork = null
        preview = null
        encodedIcon = ""
        errorRes = null
        advanced = false
        busy = false
        pendingSubmission = null
    }
}
