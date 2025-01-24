package com.kaanelloed.iconeration.data

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kaanelloed.iconeration.R
import com.kaanelloed.iconeration.ui.toColor
import com.kaanelloed.iconeration.ui.toHexString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.enums.enumEntries

private const val DARK_MODE_NAME = "NIGHT_THEME"
private const val INCLUDE_VECTOR_NAME = "INCLUDE_VECTOR"
private const val MONOCHROME_NAME = "MONOCHROME"
private const val EXPORT_THEMED_NAME = "EXPORT_THEMED"
private const val ICON_COLOR_NAME = "ICON_COLOR"
private const val BACKGROUND_COLOR_NAME = "BACKGROUND_COLOR"
private const val RETRIEVE_CALENDAR_ICONS_NAME = "RETRIEVE_CALENDAR_ICONS"
private const val PACKAGE_ADDED_NOTIFICATION_NAME = "PACKAGE_ADDED_NOTIFICATION"
private const val OVERRIDE_ICON_NAME = "OVERRIDE_ICON"
private const val AUTOMATICALLY_UPDATE_PACK_NAME = "AUTOMATICALLY_UPDATE_PACK"
private const val PRIMARY_SOURCE_NAME = "PRIMARY_SOURCE"
private const val PRIMARY_IMAGE_EDIT_NAME = "PRIMARY_IMAGE_EDIT"
private const val PRIMARY_TEXT_TYPE_NAME = "PRIMARY_TEXT_TYPE"
private const val PRIMARY_ICON_PACK_NAME = "PRIMARY_ICON_PACK"
private const val SECONDARY_SOURCE_NAME = "SECONDARY_SOURCE"
private const val SECONDARY_IMAGE_EDIT_NAME = "SECONDARY_IMAGE_EDIT"
private const val SECONDARY_TEXT_TYPE_NAME = "SECONDARY_TEXT_TYPE"
private const val SECONDARY_ICON_PACK_NAME = "SECONDARY_ICON_PACK"

val DARK_MODE_DEFAULT = DarkMode.FOLLOW_SYSTEM
val SOURCE_DEFAULT = Source.NONE
val IMAGE_EDIT_DEFAULT = ImageEdit.NONE
val TEXT_TYPE_DEFAULT = TextType.FULL_NAME

val DarkModeKey: Preferences.Key<Int>
    get() = intPreferencesKey(DARK_MODE_NAME)
val IncludeVectorKey: Preferences.Key<Boolean>
    get() = booleanPreferencesKey(INCLUDE_VECTOR_NAME)
val MonochromeKey: Preferences.Key<Boolean>
    get() = booleanPreferencesKey(MONOCHROME_NAME)
val ExportThemedKey: Preferences.Key<Boolean>
    get() = booleanPreferencesKey(EXPORT_THEMED_NAME)
val IconColorKey: Preferences.Key<String>
    get() = stringPreferencesKey(ICON_COLOR_NAME)
val BackgroundColorKey: Preferences.Key<String>
    get() = stringPreferencesKey(BACKGROUND_COLOR_NAME)
val CalendarIconsKey: Preferences.Key<Boolean>
    get() = booleanPreferencesKey(RETRIEVE_CALENDAR_ICONS_NAME)
val PackageAddedNotificationKey: Preferences.Key<Boolean>
    get() = booleanPreferencesKey(PACKAGE_ADDED_NOTIFICATION_NAME)
val OverrideIconKey: Preferences.Key<Boolean>
    get() = booleanPreferencesKey(OVERRIDE_ICON_NAME)
val AutomaticallyUpdateKey: Preferences.Key<Boolean>
    get() = booleanPreferencesKey(AUTOMATICALLY_UPDATE_PACK_NAME)
val PrimarySourceKey: Preferences.Key<Int>
    get() = intPreferencesKey(PRIMARY_SOURCE_NAME)
val PrimaryImageEditKey: Preferences.Key<Int>
    get() = intPreferencesKey(PRIMARY_IMAGE_EDIT_NAME)
val PrimaryTextTypeKey: Preferences.Key<Int>
    get() = intPreferencesKey(PRIMARY_TEXT_TYPE_NAME)
val PrimaryIconPackKey: Preferences.Key<String>
    get() = stringPreferencesKey(PRIMARY_ICON_PACK_NAME)
val SecondarySourceKey: Preferences.Key<Int>
    get() = intPreferencesKey(SECONDARY_SOURCE_NAME)
val SecondaryImageEditKey: Preferences.Key<Int>
    get() = intPreferencesKey(SECONDARY_IMAGE_EDIT_NAME)
val SecondaryTextTypeKey: Preferences.Key<Int>
    get() = intPreferencesKey(SECONDARY_TEXT_TYPE_NAME)
val SecondaryIconPackKey: Preferences.Key<String>
    get() = stringPreferencesKey(SECONDARY_ICON_PACK_NAME)

@Composable
fun DataStore<Preferences>.getPreferencesValue(): Preferences {
    return data.collectAsState(initial = emptyPreferences()).value
}

@Composable
fun DataStore<Preferences>.getDefaultIconColor(): Color {
    return if (isDarkModeEnabled()) Color.White else Color.Black
}

fun Preferences.getDefaultIconColor(context: Context): Color {
    return if (isDarkModeEnabled(context)) Color.White else Color.Black
}

@Composable
fun DataStore<Preferences>.getDefaultBackgroundColor(): Color {
    return if (isDarkModeEnabled()) Color.Black else Color.White
}

fun Preferences.getDefaultBackgroundColor(context: Context): Color {
    return if (isDarkModeEnabled(context)) Color.Black else Color.White
}

//Preference type
fun DataStore<Preferences>.getBooleanState(key: Preferences.Key<Boolean>): Flow<Boolean?> {
    return getPreferenceFlow(key)
}

@Composable
fun DataStore<Preferences>.getBooleanValue(
    key: Preferences.Key<Boolean>
    , default: Boolean = false
): Boolean {
    return getPreferenceValue(key, default)
}

suspend fun DataStore<Preferences>.setBooleanValue(key: Preferences.Key<Boolean>, value: Boolean) {
    setPreferenceValue(key, value)
}

fun Preferences.getBooleanValue(key: Preferences.Key<Boolean>, default: Boolean = false): Boolean {
    return this[key] ?: default
}

fun DataStore<Preferences>.getStringState(key: Preferences.Key<String>): Flow<String?> {
    return getPreferenceFlow(key)
}

@Composable
fun DataStore<Preferences>.getStringValue(
    key: Preferences.Key<String>
    , default: String = ""
): String {
    return getPreferenceValue(key, default)
}

suspend fun DataStore<Preferences>.setStringValue(key: Preferences.Key<String>, value: String) {
    setPreferenceValue(key, value)
}

fun Preferences.getStringValue(key: Preferences.Key<String>, default: String = ""): String {
    return this[key] ?: default
}

fun DataStore<Preferences>.getIntState(key: Preferences.Key<Int>): Flow<Int?> {
    return getPreferenceFlow(key)
}

@Composable
fun DataStore<Preferences>.getIntValue(
    key: Preferences.Key<Int>
    , default: Int = 0
): Int {
    return getPreferenceValue(key, default)
}

suspend fun DataStore<Preferences>.setIntValue(key: Preferences.Key<Int>, value: Int) {
    setPreferenceValue(key, value)
}

fun Preferences.getIntValue(key: Preferences.Key<Int>, default: Int = 0): Int {
    return this[key] ?: default
}

//Color
@Composable
fun DataStore<Preferences>.getColorValue(key: Preferences.Key<String>, default: Color): Color {
    val hex = getPreferenceValue(key, default.toHexString())
    return hex.toColor()
}

suspend fun DataStore<Preferences>.setColorValue(key: Preferences.Key<String>, value: Color) {
    setPreferenceValue(key, value.toHexString())
}

fun Preferences.getColorValue(key: Preferences.Key<String>, default: Color): Color {
    val hex = this[key] ?: default.toHexString()
    return hex.toColor()
}

//Enum
@Composable
inline fun <reified T: Enum<T>> DataStore<Preferences>.getEnumValue(
    key: Preferences.Key<Int>
    , default: T
): T {
    val ordinal = getPreferenceValue(key, default.ordinal)
    return enumEntries<T>()[ordinal]
}

suspend inline fun <reified T: Enum<T>> DataStore<Preferences>.setEnumValue(
    key: Preferences.Key<Int>
    , value: T
) {
    setPreferenceValue(key, value.ordinal)
}

inline fun <reified T: Enum<T>> Preferences.getEnumValue(
    key: Preferences.Key<Int>
    , default: T
): T {
    val ordinal = this[key] ?: default.ordinal
    return enumEntries<T>()[ordinal]
}

//Common
fun <T : Any> DataStore<Preferences>.getPreferenceFlow(key: Preferences.Key<T>): Flow<T?> {
    return data.map { settings ->
        settings[key]
    }
}

@Composable
fun <T : Any> DataStore<Preferences>.getPreferenceValue(key: Preferences.Key<T>, default: T): T {
    return getPreferenceFlow(key).collectAsState(initial = default).value ?: default
}

suspend fun <T> DataStore<Preferences>.setPreferenceValue(key: Preferences.Key<T>, value: T) {
    edit { settings ->
        settings[key] = value
    }
}

//Labels
@Composable
fun getDarkModeLabels(): Map<DarkMode, String> {
    return mapOf(DarkMode.FOLLOW_SYSTEM to stringResource(id = R.string.followSystem)
        , DarkMode.DARK to stringResource(id = R.string.darkMode)
        , DarkMode.LIGHT to stringResource(id = R.string.lightMode))
}

@Composable
fun getSourceLabels(): Map<Source, String> {
    return mapOf(Source.NONE to stringResource(id = R.string.none)
        , Source.ICON_PACK to stringResource(id = R.string.iconPack)
        , Source.APPLICATION_ICON to stringResource(id = R.string.applicationIcon)
        , Source.APPLICATION_NAME to stringResource(id = R.string.applicationName))
}

@Composable
fun getImageEditLabels(): Map<ImageEdit, String> {
    return mapOf(ImageEdit.NONE to stringResource(id = R.string.none)
        , ImageEdit.PATH to stringResource(id = R.string.pathDetection)
        , ImageEdit.EDGE to stringResource(id = R.string.edgeDetection)
        , ImageEdit.COLORIZE to stringResource(id = R.string.colorize))
}

@Composable
fun getTextTypeLabels(): Map<TextType, String> {
    return mapOf(TextType.FULL_NAME to stringResource(id = R.string.fullName)
        , TextType.ONE_LETTER to stringResource(id = R.string.firstLetter)
        , TextType.TWO_LETTERS to stringResource(id = R.string.twoLetters))
}

@Composable
fun DataStore<Preferences>.isDarkModeEnabled(): Boolean {
    return isDarkModeEnabled(getEnumValue(DarkModeKey, DARK_MODE_DEFAULT), isSystemInDarkTheme())
}

fun Preferences.isDarkModeEnabled(context: Context): Boolean {
    return isDarkModeEnabled(getEnumValue(DarkModeKey, DARK_MODE_DEFAULT), context.isSystemInDarkTheme())
}

fun Context.isSystemInDarkTheme(): Boolean {
    val uiMode = this.resources.configuration.uiMode
    return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

fun isDarkModeEnabled(darkMode: DarkMode, system: Boolean): Boolean {
    return when (darkMode) {
        DarkMode.FOLLOW_SYSTEM -> system
        DarkMode.DARK -> true
        DarkMode.LIGHT -> false
    }
}

enum class DarkMode {
    FOLLOW_SYSTEM, DARK, LIGHT
}

enum class Source {
    NONE, ICON_PACK, APPLICATION_ICON, APPLICATION_NAME
}

enum class ImageEdit {
    NONE, PATH, EDGE, COLORIZE
}

enum class TextType {
    FULL_NAME, ONE_LETTER, TWO_LETTERS
}