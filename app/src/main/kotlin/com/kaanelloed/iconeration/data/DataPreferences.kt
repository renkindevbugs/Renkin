package com.kaanelloed.iconeration.data

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kaanelloed.iconeration.R
import com.kaanelloed.iconeration.ui.toColor
import com.kaanelloed.iconeration.ui.toHexString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

const val DARK_MODE_NAME = "NIGHT_THEME"
const val TYPE_NAME = "GENERATION_TYPE"
const val INCLUDE_VECTOR_NAME = "INCLUDE_VECTOR"
const val MONOCHROME_NAME = "MONOCHROME"
const val EXPORT_THEMED_NAME = "EXPORT_THEMED"
const val ICON_COLOR_NAME = "ICON_COLOR"
const val BACKGROUND_COLOR_NAME = "BACKGROUND_COLOR"
const val COLORIZE_ICON_PACK_NAME = "COLORIZE_ICON_PACK"

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

//Dark Mode
fun DataStore<Preferences>.getDarkMode(): Flow<Int?> {
    return getPrefs(DarkModeKey)
}

@Composable
fun DataStore<Preferences>.getDarkModeValue(): DarkMode {
    val index = getDarkMode().collectAsState(initial = 0).value ?: DARK_MODE_DEFAULT.ordinal
    return DarkMode.entries[index]
}

suspend fun DataStore<Preferences>.setDarkMode(value: Int) {
    setPrefs(DarkModeKey, value)
}

suspend fun DataStore<Preferences>.setDarkMode(value: DarkMode) {
    setDarkMode(value.ordinal)
}

//Generation Type
fun DataStore<Preferences>.getType(): Flow<Int?> {
    return getPrefs(TypeKey)
}

@Composable
fun DataStore<Preferences>.getTypeValue(): GenerationType {
    val index = getType().collectAsState(initial = 0).value ?: TYPE_DEFAULT.ordinal
    return GenerationType.entries[index]
}


suspend fun DataStore<Preferences>.setType(value: Int) {
    setPrefs(TypeKey, value)
}

suspend fun DataStore<Preferences>.setType(value: GenerationType) {
    setType(value.ordinal)
}

//Vector
fun DataStore<Preferences>.getIncludeVector(): Flow<Boolean?> {
    return getPrefs(IncludeVectorKey)
}

@Composable
fun DataStore<Preferences>.getIncludeVectorValue(): Boolean {
    return getIncludeVector().collectAsState(initial = false).value ?: false
}

suspend fun DataStore<Preferences>.setIncludeVector(value: Boolean) {
    setPrefs(IncludeVectorKey, value)
}

//Monochrome
fun DataStore<Preferences>.getMonochrome(): Flow<Boolean?> {
    return getPrefs(MonochromeKey)
}

@Composable
fun DataStore<Preferences>.getMonochromeValue(): Boolean {
    return getMonochrome().collectAsState(initial = false).value ?: false
}

suspend fun DataStore<Preferences>.setMonochrome(value: Boolean) {
    setPrefs(MonochromeKey, value)
}

//Themed
fun DataStore<Preferences>.getExportThemed(): Flow<Boolean?> {
    return getPrefs(ExportThemedKey)
}

@Composable
fun DataStore<Preferences>.getExportThemedValue(): Boolean {
    return getExportThemed().collectAsState(initial = false).value ?: false
}

suspend fun DataStore<Preferences>.setExportThemed(value: Boolean) {
    setPrefs(ExportThemedKey, value)
}

//Icon Color
@Composable
fun DataStore<Preferences>.getDefaultIconColor(): Color {
    return if (isDarkModeEnabled()) Color.White else Color.Black
}

fun DataStore<Preferences>.getIconColor(): Flow<String?> {
    return getPrefs(IconColorKey)
}

@Composable
fun DataStore<Preferences>.getIconColorValue(): Color {
    val default = getDefaultIconColor()
    val hex = getIconColor().collectAsState(initial = getDefaultIconColor().toHexString()).value
    return hex?.toColor() ?: default
}

suspend fun DataStore<Preferences>.setIconColor(value: String) {
    setPrefs(IconColorKey, value)
}

suspend fun DataStore<Preferences>.setIconColor(value: Color) {
    setPrefs(IconColorKey, value.toHexString())
}

//Background Color
@Composable
fun DataStore<Preferences>.getDefaultBackgroundColor(): Color {
    return if (isDarkModeEnabled()) Color.Black else Color.White
}

fun DataStore<Preferences>.getBackgroundColor(): Flow<String?> {
    return getPrefs(BackgroundColorKey)
}

@Composable
fun DataStore<Preferences>.getBackgroundColorValue(): Color {
    val default = getDefaultBackgroundColor()
    val hex = getBackgroundColor().collectAsState(initial = default.toHexString()).value
    return hex?.toColor() ?: default
}

suspend fun DataStore<Preferences>.setBackgroundColor(value: String) {
    setPrefs(BackgroundColorKey, value)
}

suspend fun DataStore<Preferences>.setBackgroundColor(value: Color) {
    setPrefs(BackgroundColorKey, value.toHexString())
}

//Colorize Icon Pack
fun DataStore<Preferences>.getColorizeIconPack(): Flow<Boolean?> {
    return getPrefs(ColorizeIconPackKey)
}

@Composable
fun DataStore<Preferences>.getColorizeIconPackValue(): Boolean {
    return getColorizeIconPack().collectAsState(initial = false).value ?: false
}

suspend fun DataStore<Preferences>.setColorizeIconPack(value: Boolean) {
    setPrefs(ColorizeIconPackKey, value)
}

fun <T : Any> DataStore<Preferences>.getPrefs(key: Preferences.Key<T>): Flow<T?> {
    return data.map { settings ->
        settings[key]
    }
}

suspend fun <T> DataStore<Preferences>.setPrefs(key: Preferences.Key<T>, value: T) {
    edit { settings ->
        settings[key] = value
    }
}

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
        , GenerationType.APP_NAME to stringResource(id = R.string.applicationName))
}

@Composable
fun DataStore<Preferences>.isDarkModeEnabled(): Boolean {
    return when (getDarkModeValue()) {
        DarkMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        DarkMode.DARK -> true
        DarkMode.LIGHT -> false
    }
}

enum class DarkMode {
    FOLLOW_SYSTEM, DARK, LIGHT
}

enum class GenerationType {
    PATH, EDGE, ONE_LETTER, TWO_LETTERS, APP_NAME
}