package dev.alembiconsProject.alembicons.data

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
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.alembiconsProject.alembicons.R
import dev.alembiconsProject.alembicons.extension.toColor
import dev.alembiconsProject.alembicons.extension.toHexString
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
private const val OVERRIDE_ICON_NAME = "OVERRIDE_ICON"
private const val PRIMARY_SOURCE_NAME = "PRIMARY_SOURCE"
private const val PRIMARY_IMAGE_EDIT_NAME = "PRIMARY_IMAGE_EDIT"
private const val PRIMARY_TEXT_TYPE_NAME = "PRIMARY_TEXT_TYPE"
private const val PRIMARY_ICON_PACK_NAME = "PRIMARY_ICON_PACK"
private const val SECONDARY_SOURCE_NAME = "SECONDARY_SOURCE"
private const val SECONDARY_IMAGE_EDIT_NAME = "SECONDARY_IMAGE_EDIT"
private const val SECONDARY_TEXT_TYPE_NAME = "SECONDARY_TEXT_TYPE"
private const val SECONDARY_ICON_PACK_NAME = "SECONDARY_ICON_PACK"
private const val APP_SORT_ORDER_NAME = "APP_SORT_ORDER"
private const val APP_FILTER_NO_ICON_NAME = "APP_FILTER_NO_ICON"
private const val WATCH_CHECK_INTERVAL_NAME = "WATCH_CHECK_INTERVAL_MINUTES"
private const val LAST_WATCH_CHECK_AT_NAME = "LAST_WATCH_CHECK_AT"
private const val FALLBACK_SOURCE_NAME = "FALLBACK_SOURCE"

// Icon-watch periodic check interval, in minutes. 24h by default; the debug build can
// lower it (min 15, WorkManager's periodic floor) to test the watcher quickly.
const val WATCH_CHECK_INTERVAL_DEFAULT = 24 * 60

val DARK_MODE_DEFAULT = DarkMode.FOLLOW_SYSTEM
val SOURCE_DEFAULT = Source.NONE
val IMAGE_EDIT_DEFAULT = ImageEdit.NONE
val TEXT_TYPE_DEFAULT = TextType.FULL_NAME
val FALLBACK_SOURCE_DEFAULT = FallbackSource.NONE

val DarkModeKey = intPreferencesKey(DARK_MODE_NAME)
val IncludeVectorKey = booleanPreferencesKey(INCLUDE_VECTOR_NAME)
val MonochromeKey = booleanPreferencesKey(MONOCHROME_NAME)
val ExportThemedKey = booleanPreferencesKey(EXPORT_THEMED_NAME)
val IconColorKey = stringPreferencesKey(ICON_COLOR_NAME)
val BackgroundColorKey = stringPreferencesKey(BACKGROUND_COLOR_NAME)
val CalendarIconsKey = booleanPreferencesKey(RETRIEVE_CALENDAR_ICONS_NAME)
val OverrideIconKey = booleanPreferencesKey(OVERRIDE_ICON_NAME)
val PrimarySourceKey = intPreferencesKey(PRIMARY_SOURCE_NAME)
val PrimaryImageEditKey = intPreferencesKey(PRIMARY_IMAGE_EDIT_NAME)
val PrimaryTextTypeKey = intPreferencesKey(PRIMARY_TEXT_TYPE_NAME)
val PrimaryIconPackKey = stringPreferencesKey(PRIMARY_ICON_PACK_NAME)
val SecondarySourceKey = intPreferencesKey(SECONDARY_SOURCE_NAME)
val SecondaryImageEditKey = intPreferencesKey(SECONDARY_IMAGE_EDIT_NAME)
val SecondaryTextTypeKey = intPreferencesKey(SECONDARY_TEXT_TYPE_NAME)
val SecondaryIconPackKey = stringPreferencesKey(SECONDARY_ICON_PACK_NAME)
val FallbackSourceKey = intPreferencesKey(FALLBACK_SOURCE_NAME)
val AppSortOrderKey = intPreferencesKey(APP_SORT_ORDER_NAME)
val AppFilterNoIconKey = booleanPreferencesKey(APP_FILTER_NO_ICON_NAME)
val WatchCheckIntervalKey = intPreferencesKey(WATCH_CHECK_INTERVAL_NAME)
val LastWatchCheckAtKey = longPreferencesKey(LAST_WATCH_CHECK_AT_NAME)

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

// Convenience: the configured icon / background colour, falling back to the theme default.
// Bundles the key + matching default so callers don't repeat that pairing.

@Composable
fun DataStore<Preferences>.getIconColor(): Color =
    getColorValue(IconColorKey, getDefaultIconColor())

fun Preferences.getIconColor(context: Context): Color =
    getColorValue(IconColorKey, getDefaultIconColor(context))

@Composable
fun DataStore<Preferences>.getBackgroundColor(): Color =
    getColorValue(BackgroundColorKey, getDefaultBackgroundColor())

fun Preferences.getBackgroundColor(context: Context): Color =
    getColorValue(BackgroundColorKey, getDefaultBackgroundColor(context))

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

@Composable
fun DataStore<Preferences>.getLongValue(
    key: Preferences.Key<Long>
    , default: Long = 0L
): Long {
    return getPreferenceValue(key, default)
}

suspend fun DataStore<Preferences>.setLongValue(key: Preferences.Key<Long>, value: Long) {
    setPreferenceValue(key, value)
}

fun Preferences.getLongValue(key: Preferences.Key<Long>, default: Long = 0L): Long {
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

/** Which pack's fallback styling to apply to apps neither pack themes (issue #121). */
enum class FallbackSource {
    NONE, PRIMARY, SECONDARY
}

enum class ImageEdit {
    NONE, PATH, EDGE, COLORIZE
}

enum class TextType {
    FULL_NAME, ONE_LETTER, TWO_LETTERS
}