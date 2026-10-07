package dev.renkinProject.renkin

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.renkinProject.renkin.apk.ApplicationProvider
import dev.renkinProject.renkin.apk.ProfileOperationBusyException
import dev.renkinProject.renkin.apk.ProfileSessionToken
import dev.renkinProject.renkin.apk.ProfileSessionChangedException
import dev.renkinProject.renkin.data.BackgroundStyleKeys
import dev.renkinProject.renkin.data.CalendarIconsKey
import dev.renkinProject.renkin.data.ColorStyleKeys
import dev.renkinProject.renkin.data.ColorizerStyleKeys
import dev.renkinProject.renkin.data.ExportThemedKey
import dev.renkinProject.renkin.data.FallbackSource
import dev.renkinProject.renkin.data.FallbackSourceKey
import dev.renkinProject.renkin.data.GradientPreset
import dev.renkinProject.renkin.data.GradientPresets
import dev.renkinProject.renkin.data.IconColorKey
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.IncludeVectorKey
import dev.renkinProject.renkin.data.MonochromeKey
import dev.renkinProject.renkin.data.OutlineAddKey
import dev.renkinProject.renkin.data.OutlineStyleKeys
import dev.renkinProject.renkin.data.OutlineWidthKey
import dev.renkinProject.renkin.data.OverrideIconKey
import dev.renkinProject.renkin.data.PrimaryImageEditKey
import dev.renkinProject.renkin.data.PrimaryTextTypeKey
import dev.renkinProject.renkin.data.SecondaryIconPackKey
import dev.renkinProject.renkin.data.SecondaryImageEditKey
import dev.renkinProject.renkin.data.SecondarySourceKey
import dev.renkinProject.renkin.data.SecondaryTextTypeKey
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.data.TextFontKey
import dev.renkinProject.renkin.data.TextType
import dev.renkinProject.renkin.data.observePreferences
import dev.renkinProject.renkin.data.setBooleanValue
import dev.renkinProject.renkin.data.setColorStyle
import dev.renkinProject.renkin.data.setColorValue
import dev.renkinProject.renkin.data.setEnumValue
import dev.renkinProject.renkin.data.setIntValue
import dev.renkinProject.renkin.data.setStringValue
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.icon.creator.ColorizerStyle
import dev.renkinProject.renkin.util.Log
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel

/**
 * Shared owner for the option controls hosted by both MainActivity and GlobalOptionsActivity.
 * Keeping their writes and bundled-resource loading here prevents the shared composables from
 * choosing a host-specific view model or performing I/O themselves.
 */
@HiltViewModel
class OptionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appProvider: ApplicationProvider,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val preferences = context.dataStore

    val sessionToken: ProfileSessionToken
        get() = savedStateHandle.profileSession() ?: appProvider.sessionToken

    private val _toastEvents = Channel<Int>(Channel.BUFFERED)
    val toastEvents = _toastEvents.receiveAsFlow()

    /**
     * Activity-scoped preference snapshot for option UIs that can temporarily leave composition.
     * A direct DataStore collect starts again from an empty snapshot whenever a sheet/dialog is
     * reopened; that made enabled switches briefly appear off while their persisted value was
     * still on. StateFlow retains the last real snapshot across those UI transitions.
     */
    val preferenceSnapshot = preferences.observePreferences().stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        emptyPreferences()
    )

    private val _gradientPresets = MutableStateFlow<List<GradientPreset>>(emptyList())
    val gradientPresets = _gradientPresets.asStateFlow()
    private var gradientPresetsLoadStarted = false

    fun loadGradientPresets() {
        if (gradientPresetsLoadStarted) return
        gradientPresetsLoadStarted = true
        viewModelScope.launch(Dispatchers.IO) {
            _gradientPresets.value = GradientPresets.load(context)
        }
    }

    fun setPrimaryImageEdit(value: ImageEdit) = write {
        setEnumValue(PrimaryImageEditKey, value)
    }

    fun setPrimaryTextType(value: TextType) = write {
        setEnumValue(PrimaryTextTypeKey, value)
    }

    fun setTextFont(path: String) = write { setStringValue(TextFontKey, path) }

    fun setSecondarySource(value: Source) = write { setEnumValue(SecondarySourceKey, value) }

    fun setSecondaryIconPack(packageName: String) = write {
        setStringValue(SecondaryIconPackKey, packageName)
    }

    fun setSecondaryImageEdit(value: ImageEdit) = write {
        setEnumValue(SecondaryImageEditKey, value)
    }

    fun setSecondaryTextType(value: TextType) = write {
        setEnumValue(SecondaryTextTypeKey, value)
    }

    fun setCalendarIcons(enabled: Boolean) = write { setBooleanValue(CalendarIconsKey, enabled) }

    fun setFallbackSource(value: FallbackSource) = write {
        setEnumValue(FallbackSourceKey, value)
    }

    fun setIconColor(color: Color) = write { setColorValue(IconColorKey, color) }

    fun setColorizerStyle(style: ColorizerStyle) = writeColorStyle(ColorizerStyleKeys, style)

    fun setBackgroundStyle(style: ColorizerStyle) = writeColorStyle(BackgroundStyleKeys, style)

    fun setOutlineEnabled(enabled: Boolean) = write { setBooleanValue(OutlineAddKey, enabled) }

    fun setOutlineWidth(
        width: Float,
        expected: ProfileSessionToken,
        onComplete: (Boolean) -> Unit
    ) = write(expected, onComplete) {
        setIntValue(OutlineWidthKey, width.roundToInt())
    }

    fun setOutlineStyle(style: ColorizerStyle) = writeColorStyle(OutlineStyleKeys, style)

    fun setOverrideIcons(enabled: Boolean) = write { setBooleanValue(OverrideIconKey, enabled) }

    fun setVectorEnabled(enabled: Boolean) = write { setBooleanValue(IncludeVectorKey, enabled) }

    fun setMaterialYouEnabled(enabled: Boolean) = write { setBooleanValue(MonochromeKey, enabled) }

    fun setThemedEnabled(enabled: Boolean) = write { setBooleanValue(ExportThemedKey, enabled) }

    suspend fun fallbackPreview(
        snapshot: Preferences,
        source: FallbackSource
    ): List<IconPackDrawable> = appProvider.fallbackPreview(snapshot, source)

    private fun writeColorStyle(keys: ColorStyleKeys, style: ColorizerStyle) = write {
        setColorStyle(
            keys = keys,
            mode = style.mode.ordinal,
            gradientType = style.gradientType.ordinal,
            gradientAngle = style.gradientAngle.roundToInt(),
            firstColor = Color(style.firstColor),
            gradientStops = style.gradientStops,
            gradientPositions = style.gradientPositions
        )
    }

    private fun write(
        expected: ProfileSessionToken = sessionToken,
        onComplete: (Boolean) -> Unit = {},
        block: suspend DataStore<Preferences>.() -> Unit
    ) {
        if (!appProvider.startupComplete || appProvider.isProfileSwitching) {
            _toastEvents.trySend(R.string.profileStillLoading)
            onComplete(false)
            return
        }
        viewModelScope.launch {
            val saved = try {
                appProvider.updateProfilePreferences(expected, block)
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProfileSessionChangedException) {
                _toastEvents.trySend(error.userMessage())
                false
            } catch (error: ProfileOperationBusyException) {
                _toastEvents.trySend(R.string.profileOperationBusy)
                false
            } catch (error: Exception) {
                Log.error("OptionsViewModel", "Could not save profile options", error)
                _toastEvents.trySend(R.string.profileOperationFailed)
                false
            }
            onComplete(saved)
        }
    }
}
