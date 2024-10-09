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

const val DARK_MODE_NAME = "NIGHT_THEME"
const val TYPE_NAME = "GENERATION_TYPE"
const val INCLUDE_VECTOR_NAME = "INCLUDE_VECTOR"
const val MONOCHROME_NAME = "MONOCHROME"
const val EXPORT_THEMED_NAME = "EXPORT_THEMED"
const val ICON_COLOR_NAME = "ICON_COLOR"
const val BACKGROUND_COLOR_NAME = "BACKGROUND_COLOR"
const val COLORIZE_ICON_PACK_NAME = "COLORIZE_ICON_PACK"
const val ICON_PACK_NAME = "ICON_PACK"
const val RETRIEVE_CALENDAR_ICONS = "RETRIEVE_CALENDAR_ICONS"
const val PACKAGE_ADDED_NOTIFICATION = "PACKAGE_ADDED_NOTIFICATION"
const val OVERRIDE_ICON = "OVERRIDE_ICON"
const val AUTOMATICALLY_UPDATE_PACK = "AUTOMATICALLY_UPDATE_PACK"

val DARK_MODE_DEFAULT = DarkMode.FOLLOW_SYSTEM
val TYPE_DEFAULT = GenerationType.PATH

val DarkModeKey: Preferences.Key<Int>
    get() = intPreferencesKey(DARK_MODE_NAME)
val TypeKey: Preferences.Key<Int>
    get() = intPreferencesKey(TYPE_NAME)
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
val ColorizeIconPackKey: Preferences.Key<Boolean>
    get() = booleanPreferencesKey(COLORIZE_ICON_PACK_NAME)
val IconPackKey: Preferences.Key<String>
    get() = stringPreferencesKey(ICON_PACK_NAME)
val CalendarIconsKey: Preferences.Key<Boolean>
    get() = booleanPreferencesKey(RETRIEVE_CALENDAR_ICONS)
val PackageAddedNotificationKey: Preferences.Key<Boolean>
    get() = booleanPreferencesKey(PACKAGE_ADDED_NOTIFICATION)
val OverrideIconKey: Preferences.Key<Boolean>
    get() = booleanPreferencesKey(OVERRIDE_ICON)
val AutomaticallyUpdateKey: Preferences.Key<Boolean>
    get() = booleanPreferencesKey(AUTOMATICALLY_UPDATE_PACK)

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
fun getTypeLabels(): Map<GenerationType, String> {
    return mapOf(GenerationType.PATH to stringResource(id = R.string.pathDetection)
        , GenerationType.EDGE to stringResource(id = R.string.edgeDetection)
        , GenerationType.ONE_LETTER to stringResource(id = R.string.firstLetter)
        , GenerationType.TWO_LETTERS to stringResource(id = R.string.twoLetters)
        , GenerationType.APP_NAME to stringResource(id = R.string.applicationName)
        , GenerationType.ICON_PACK_ONLY to stringResource(id = R.string.iconPackOnly))
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

enum class GenerationType {
    PATH, EDGE, ONE_LETTER, TWO_LETTERS, APP_NAME, ICON_PACK_ONLY
}