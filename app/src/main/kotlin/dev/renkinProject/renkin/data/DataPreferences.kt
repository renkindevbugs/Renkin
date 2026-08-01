package dev.renkinProject.renkin.data

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.renkinProject.renkin.R
import dev.renkinProject.renkin.extension.toHexString
import dev.renkinProject.renkin.extension.toNullableColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.enums.enumEntries

// DataStore serializes edits internally, but UI-triggered setter coroutines can still race a
// snapshot read started by the next tap. This mutex gives writes and action snapshots one order.
private val preferenceAccessMutex = Mutex()

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
private const val BUILT_PRIMARY_SOURCE_NAME = "BUILT_PRIMARY_SOURCE"
private const val BUILT_PRIMARY_ICON_PACK_NAME = "BUILT_PRIMARY_ICON_PACK"

// Icon-watch periodic check interval, in minutes. 24h by default; the debug build can
// lower it (min 15, WorkManager's periodic floor) to test the watcher quickly.
const val WATCH_CHECK_INTERVAL_DEFAULT = 24 * 60
const val WATCH_CHECK_INTERVAL_MIN = 15

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
// The pack-wide background can carry a gradient of its own, described like the colourizer's.
// [BackgroundColorKey] stays its first colour, so an older build still paints something sane.
val BackgroundColorizerModeKey = intPreferencesKey("BACKGROUND_COLORIZER_MODE")
val BackgroundGradientTypeKey = intPreferencesKey("BACKGROUND_GRADIENT_TYPE")
val BackgroundGradientAngleKey = intPreferencesKey("BACKGROUND_GRADIENT_ANGLE")
val BackgroundGradientColorsKey = stringPreferencesKey("BACKGROUND_GRADIENT_COLORS")
val BackgroundGradientPositionsKey = stringPreferencesKey("BACKGROUND_GRADIENT_POSITIONS")
val ColorizerModeKey = intPreferencesKey("COLORIZER_MODE")
val ColorizerGradientColorKey = stringPreferencesKey("COLORIZER_GRADIENT_COLOR")
// Every gradient stop after the first colour, comma separated. Supersedes the single-colour key
// above, which is still written so an older build (or an older backup) keeps working.
val ColorizerGradientColorsKey = stringPreferencesKey("COLORIZER_GRADIENT_COLORS")
// Where each stop sits, 0..1, comma separated, covering the first colour too — so it holds one
// more value than the colours key. Absent (older installs, older backups) = spread evenly.
val ColorizerGradientPositionsKey = stringPreferencesKey("COLORIZER_GRADIENT_POSITIONS")
val ColorizerGradientAngleKey = intPreferencesKey("COLORIZER_GRADIENT_ANGLE")
val ColorizerGradientTypeKey = intPreferencesKey("COLORIZER_GRADIENT_TYPE")
val CalendarIconsKey = booleanPreferencesKey(RETRIEVE_CALENDAR_ICONS_NAME)
val OverrideIconKey = booleanPreferencesKey(OVERRIDE_ICON_NAME)
val PrimarySourceKey = intPreferencesKey(PRIMARY_SOURCE_NAME)
val PrimaryImageEditKey = intPreferencesKey(PRIMARY_IMAGE_EDIT_NAME)
val PrimaryTextTypeKey = intPreferencesKey(PRIMARY_TEXT_TYPE_NAME)
val PrimaryIconPackKey = stringPreferencesKey(PRIMARY_ICON_PACK_NAME)
// The primary source/pack as of the last successful build. Startup restores these over any
// unbuilt hero-card pick, so the pick only "sticks" once it's built. Absent on legacy/fresh
// installs — startup then leaves the current primary selection untouched.
val BuiltPrimarySourceKey = intPreferencesKey(BUILT_PRIMARY_SOURCE_NAME)
val BuiltPrimaryIconPackKey = stringPreferencesKey(BUILT_PRIMARY_ICON_PACK_NAME)
val SecondarySourceKey = intPreferencesKey(SECONDARY_SOURCE_NAME)
val SecondaryImageEditKey = intPreferencesKey(SECONDARY_IMAGE_EDIT_NAME)
val SecondaryTextTypeKey = intPreferencesKey(SECONDARY_TEXT_TYPE_NAME)
val SecondaryIconPackKey = stringPreferencesKey(SECONDARY_ICON_PACK_NAME)
val FallbackSourceKey = intPreferencesKey(FALLBACK_SOURCE_NAME)
// Absolute path of the TTF/OTF used for text icons; empty = the bundled Arcticons Sans.
val TextFontKey = stringPreferencesKey("TEXT_FONT")
// Pack-wide outline: draw an [OutlineColorKey] contour of [OutlineWidthKey] px around every
// generated icon. Only the Add mode exists globally — Recolor depends on the individual
// icon's artwork, so it stays a per-app Modifier-tab option. Width is an Int px (1..16 at
// the 256px working size): the profile snapshot restore only carries Bool/Int/Long/String.
val OutlineAddKey = booleanPreferencesKey("OUTLINE_ADD")
val OutlineWidthKey = intPreferencesKey("OUTLINE_WIDTH")
val OutlineColorKey = stringPreferencesKey("OUTLINE_COLOR")
// The outline can carry a gradient of its own, described the same way the colourizer's is.
val OutlineColorizerModeKey = intPreferencesKey("OUTLINE_COLORIZER_MODE")
val OutlineGradientTypeKey = intPreferencesKey("OUTLINE_GRADIENT_TYPE")
val OutlineGradientAngleKey = intPreferencesKey("OUTLINE_GRADIENT_ANGLE")
val OutlineGradientColorsKey = stringPreferencesKey("OUTLINE_GRADIENT_COLORS")
val OutlineGradientPositionsKey = stringPreferencesKey("OUTLINE_GRADIENT_POSITIONS")
const val OUTLINE_WIDTH_DEFAULT = 6
const val OUTLINE_WIDTH_MIN = 1
const val OUTLINE_WIDTH_MAX = 16
// Global icon modifiers (the Global options screen): shape, icon scale and colorize applied
// to every icon the refresh generates, alongside the pack-wide outline above. Scales are
// stored as Int percent (50..150) — the profile snapshot restore only carries Bool/Int/Long/
// String. Shape is the IconShape ordinal.
val GlobalShapeKey = intPreferencesKey("GLOBAL_SHAPE")
val GlobalShapeCropKey = booleanPreferencesKey("GLOBAL_SHAPE_CROP")
val GlobalShapeScaleKey = intPreferencesKey("GLOBAL_SHAPE_SCALE")
val GlobalShapeColorKey = stringPreferencesKey("GLOBAL_SHAPE_COLOR")
// The shape's plate is the one surface a background gradient is fully visible on, so it carries
// its own style; GlobalShapeColorKey remains its first colour.
val GlobalShapeColorizerModeKey = intPreferencesKey("GLOBAL_SHAPE_COLORIZER_MODE")
val GlobalShapeGradientTypeKey = intPreferencesKey("GLOBAL_SHAPE_GRADIENT_TYPE")
val GlobalShapeGradientAngleKey = intPreferencesKey("GLOBAL_SHAPE_GRADIENT_ANGLE")
val GlobalShapeGradientColorsKey = stringPreferencesKey("GLOBAL_SHAPE_GRADIENT_COLORS")
val GlobalShapeGradientPositionsKey = stringPreferencesKey("GLOBAL_SHAPE_GRADIENT_POSITIONS")
val GlobalIconScaleKey = intPreferencesKey("GLOBAL_ICON_SCALE")
val GlobalColorizeKey = booleanPreferencesKey("GLOBAL_COLORIZE")
val GlobalColorizeColorKey = stringPreferencesKey("GLOBAL_COLORIZE_COLOR")
val GlobalColorizeFlatKey = booleanPreferencesKey("GLOBAL_COLORIZE_FLAT")
val GlobalColorizeMonochromeKey = booleanPreferencesKey("GLOBAL_COLORIZE_MONOCHROME")
val GlobalColorizeInverseKey = booleanPreferencesKey("GLOBAL_COLORIZE_INVERSE")
val GlobalColorizerModeKey = intPreferencesKey("GLOBAL_COLORIZER_MODE")
val GlobalColorizerGradientColorKey = stringPreferencesKey("GLOBAL_COLORIZER_GRADIENT_COLOR")
val GlobalColorizerGradientColorsKey = stringPreferencesKey("GLOBAL_COLORIZER_GRADIENT_COLORS")
val GlobalColorizerGradientPositionsKey =
    stringPreferencesKey("GLOBAL_COLORIZER_GRADIENT_POSITIONS")
val GlobalColorizerGradientAngleKey = intPreferencesKey("GLOBAL_COLORIZER_GRADIENT_ANGLE")
val GlobalColorizerGradientTypeKey = intPreferencesKey("GLOBAL_COLORIZER_GRADIENT_TYPE")
// Which icon categories the global modifiers apply to (the Global options screen's toggle
// buttons): refresh-generated icons (on by default — also gates the globals during a bulk
// refresh), hand-picked (custom) icons, and apps that have no icon yet (those get one
// generated at Save).
val GlobalApplyGeneratedKey = booleanPreferencesKey("GLOBAL_APPLY_GENERATED")
val GlobalApplyExistingKey = booleanPreferencesKey("GLOBAL_APPLY_EXISTING")
val GlobalApplyCustomKey = booleanPreferencesKey("GLOBAL_APPLY_CUSTOM")
val GlobalIncludeEmptyKey = booleanPreferencesKey("GLOBAL_INCLUDE_EMPTY")
const val GLOBAL_SCALE_PERCENT_MIN = 50
const val GLOBAL_SCALE_PERCENT_MAX = 150

fun normalizeGlobalScalePercent(percent: Int): Int =
    percent.coerceIn(GLOBAL_SCALE_PERCENT_MIN, GLOBAL_SCALE_PERCENT_MAX)
val AppSortOrderKey = intPreferencesKey(APP_SORT_ORDER_NAME)
val AppFilterNoIconKey = booleanPreferencesKey(APP_FILTER_NO_ICON_NAME)
val WatchCheckIntervalKey = intPreferencesKey(WATCH_CHECK_INTERVAL_NAME)
val LastWatchCheckAtKey = longPreferencesKey(LAST_WATCH_CHECK_AT_NAME)

// Which profile's icons/preferences are active. Profiles snapshot/restore the keys below.
val ActiveProfileIdKey = longPreferencesKey("ACTIVE_PROFILE_ID")

// First-run intro dismissed. App-level (not in ProfilePrefKeys): the intro explains the app,
// not a profile, so switching or importing profiles must never bring it back by itself.
val OnboardingSeenKey = booleanPreferencesKey("ONBOARDING_SEEN")

// "Don't show again" for the pre-share warning (that a shared profile needs the source packs
// installed on the other device). App-level: it's about the user's understanding, not a profile.
val HideProfileShareWarningKey = booleanPreferencesKey("HIDE_PROFILE_SHARE_WARNING")

/**
 * The generation preferences that belong to a profile — captured into [Profile.prefsSnapshot]
 * when switching away and restored when switching back, so profiles don't influence each other.
 * App-level settings (dark mode, watch interval, sort/filter) intentionally stay global.
 */
private val ProfileBooleanPrefKeys: List<Preferences.Key<Boolean>> = listOf(
    IncludeVectorKey, MonochromeKey, ExportThemedKey, CalendarIconsKey, OverrideIconKey,
    OutlineAddKey, GlobalShapeCropKey, GlobalColorizeKey, GlobalColorizeFlatKey,
    GlobalColorizeMonochromeKey, GlobalColorizeInverseKey,
    GlobalApplyGeneratedKey, GlobalApplyExistingKey, GlobalApplyCustomKey, GlobalIncludeEmptyKey
)

private val ProfileIntPrefKeys: List<Preferences.Key<Int>> = listOf(
    PrimarySourceKey, PrimaryImageEditKey, PrimaryTextTypeKey,
    SecondarySourceKey, SecondaryImageEditKey, SecondaryTextTypeKey,
    FallbackSourceKey, OutlineWidthKey, BuiltPrimarySourceKey,
    GlobalShapeKey, GlobalShapeScaleKey, GlobalIconScaleKey,
    ColorizerModeKey, ColorizerGradientAngleKey, ColorizerGradientTypeKey,
    BackgroundColorizerModeKey, BackgroundGradientTypeKey, BackgroundGradientAngleKey,
    GlobalShapeColorizerModeKey, GlobalShapeGradientTypeKey, GlobalShapeGradientAngleKey,
    OutlineColorizerModeKey, OutlineGradientTypeKey, OutlineGradientAngleKey,
    GlobalColorizerModeKey, GlobalColorizerGradientAngleKey, GlobalColorizerGradientTypeKey
)

private val ProfileStringPrefKeys: List<Preferences.Key<String>> = listOf(
    PrimaryIconPackKey, SecondaryIconPackKey, IconColorKey, BackgroundColorKey,
    TextFontKey, OutlineColorKey, BuiltPrimaryIconPackKey,
    GlobalShapeColorKey, GlobalColorizeColorKey, ColorizerGradientColorKey,
    GlobalColorizerGradientColorKey, ColorizerGradientColorsKey,
    GlobalColorizerGradientColorsKey, OutlineGradientColorsKey,
    ColorizerGradientPositionsKey, GlobalColorizerGradientPositionsKey,
    OutlineGradientPositionsKey,
    BackgroundGradientColorsKey, BackgroundGradientPositionsKey,
    GlobalShapeGradientColorsKey, GlobalShapeGradientPositionsKey
)

val ProfilePrefKeys: List<Preferences.Key<*>> =
    ProfileBooleanPrefKeys + ProfileIntPrefKeys + ProfileStringPrefKeys

/** Commits only the staged Global options keys from [source], under the shared write mutex. */
suspend fun DataStore<Preferences>.persistGlobalModifierPrefs(source: Preferences) {
    preferenceAccessMutex.withLock {
        edit { target ->
            target[GlobalShapeKey] = source.getIntValue(GlobalShapeKey, 0)
            target[GlobalShapeCropKey] = source.getBooleanValue(GlobalShapeCropKey, true)
            target[GlobalShapeScaleKey] = source.getIntValue(GlobalShapeScaleKey, 100)
            target[GlobalShapeColorKey] = source.getStringValue(GlobalShapeColorKey)
            target[GlobalIconScaleKey] = source.getIntValue(GlobalIconScaleKey, 100)
            target[OutlineAddKey] = source.getBooleanValue(OutlineAddKey)
            target[OutlineWidthKey] = source.getIntValue(OutlineWidthKey, OUTLINE_WIDTH_DEFAULT)
            target[OutlineColorKey] = source.getStringValue(OutlineColorKey)
            target[OutlineColorizerModeKey] = source.getIntValue(OutlineColorizerModeKey)
            target[OutlineGradientTypeKey] = source.getIntValue(OutlineGradientTypeKey)
            target[OutlineGradientAngleKey] = source.getIntValue(OutlineGradientAngleKey)
            target[OutlineGradientColorsKey] = source.getStringValue(OutlineGradientColorsKey)
            target[OutlineGradientPositionsKey] =
                source.getStringValue(OutlineGradientPositionsKey)
            target[GlobalColorizeKey] = source.getBooleanValue(GlobalColorizeKey)
            target[GlobalColorizeColorKey] = source.getStringValue(GlobalColorizeColorKey)
            target[GlobalColorizeFlatKey] = source.getBooleanValue(GlobalColorizeFlatKey)
            target[GlobalColorizeMonochromeKey] = source.getBooleanValue(GlobalColorizeMonochromeKey)
            target[GlobalColorizeInverseKey] = source.getBooleanValue(GlobalColorizeInverseKey)
            target[GlobalColorizerModeKey] = source.getIntValue(GlobalColorizerModeKey)
            target[GlobalColorizerGradientColorKey] =
                source.getStringValue(GlobalColorizerGradientColorKey)
            target[GlobalColorizerGradientColorsKey] =
                source.getStringValue(GlobalColorizerGradientColorsKey)
            target[GlobalColorizerGradientPositionsKey] =
                source.getStringValue(GlobalColorizerGradientPositionsKey)
            target[GlobalColorizerGradientAngleKey] =
                source.getIntValue(GlobalColorizerGradientAngleKey)
            target[GlobalColorizerGradientTypeKey] =
                source.getIntValue(GlobalColorizerGradientTypeKey)
            target[GlobalApplyGeneratedKey] = source.getBooleanValue(GlobalApplyGeneratedKey, true)
            target[GlobalApplyExistingKey] = source.getBooleanValue(GlobalApplyExistingKey)
            target[GlobalApplyCustomKey] = source.getBooleanValue(GlobalApplyCustomKey)
            target[GlobalIncludeEmptyKey] = source.getBooleanValue(GlobalIncludeEmptyKey)
        }
    }
}

/** Records the hero source that a successful build actually used as one atomic profile write. */
suspend fun DataStore<Preferences>.persistBuiltPrimaryPrefs(source: Preferences) {
    preferenceAccessMutex.withLock {
        edit { target ->
            target[BuiltPrimarySourceKey] = source.getIntValue(
                PrimarySourceKey, SOURCE_DEFAULT.ordinal
            )
            target[BuiltPrimaryIconPackKey] = source.getStringValue(PrimaryIconPackKey)
        }
    }
}

/** Serializes the profile-scoped preferences into a JSON snapshot (see [ProfilePrefKeys]). */
fun Preferences.snapshotProfilePrefs(): String {
    val json = org.json.JSONObject()
    for (key in ProfilePrefKeys) {
        this[key]?.let { json.put(key.name, it) }
    }
    return json.toString()
}

/**
 * Restores a [snapshotProfilePrefs] JSON into the store: present keys are written back, absent
 * ones removed — a fresh profile starts from the defaults.
 */
suspend fun DataStore<Preferences>.restoreProfilePrefs(snapshot: String) {
    preferenceAccessMutex.withLock {
        edit { it.replaceProfilePrefs(snapshot) }
    }
}

/**
 * Snapshots the leaving profile, persists that snapshot, restores the target and records its id
 * while ordinary preference setters are blocked. Without one lock around the whole sequence, a
 * UI write can land after the leaving snapshot but before the target restore and be silently lost.
 */
suspend fun DataStore<Preferences>.switchProfilePrefs(
    targetSnapshot: String,
    newProfileId: Long,
    persistLeavingSnapshot: suspend (String) -> Unit
) {
    preferenceAccessMutex.withLock {
        persistLeavingSnapshot(data.first().snapshotProfilePrefs())
        edit { target ->
            target.replaceProfilePrefs(targetSnapshot)
            target[ActiveProfileIdKey] = newProfileId
        }
    }
}

/** Replaces every profile key, removing missing, malformed and wrongly typed values. */
internal fun MutablePreferences.replaceProfilePrefs(snapshot: String) {
    // A corrupt/unparsable snapshot starts from defaults. Crucially, an invalid value must
    // remove the previous profile's value instead of leaking it into the target profile.
    val json = runCatching { org.json.JSONObject(snapshot) }.getOrDefault(org.json.JSONObject())

    for (key in ProfileBooleanPrefKeys) {
        val value = json.opt(key.name)
        if (value is Boolean) this[key] = value else remove(key)
    }
    for (key in ProfileIntPrefKeys) {
        val value = when (val raw = json.opt(key.name)) {
            is Int -> raw
            is Long -> raw.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            else -> null
        }
        if (value != null) this[key] = value else remove(key)
    }
    for (key in ProfileStringPrefKeys) {
        val value = json.opt(key.name)
        if (value is String) this[key] = value else remove(key)
    }
}

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

fun normalizeWatchCheckInterval(minutes: Int): Int =
    minutes.takeIf { it >= WATCH_CHECK_INTERVAL_MIN } ?: WATCH_CHECK_INTERVAL_DEFAULT

fun normalizeOutlineWidth(width: Int): Int = width.coerceIn(OUTLINE_WIDTH_MIN, OUTLINE_WIDTH_MAX)

//Color
@Composable
fun DataStore<Preferences>.getColorValue(key: Preferences.Key<String>, default: Color): Color {
    val hex = getPreferenceValue(key, default.toHexString())
    return hex.toNullableColor() ?: default
}

suspend fun DataStore<Preferences>.setColorValue(key: Preferences.Key<String>, value: Color) {
    setPreferenceValue(key, value.toHexString())
}

fun Preferences.getColorValue(key: Preferences.Key<String>, default: Color): Color {
    val hex = this[key] ?: default.toHexString()
    return hex.toNullableColor() ?: default
}

/**
 * Gradient stops after the first colour, newline-free and comma separated. [legacyKey] holds the
 * single second colour written before multi-stop gradients existed, so profiles saved back then
 * still open with their gradient intact.
 */
fun Preferences.getGradientStops(
    key: Preferences.Key<String>,
    legacyKey: Preferences.Key<String>
): List<Int> {
    val stored = this[key].orEmpty()
        .split(',')
        .mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toNullableColor()?.toArgb() }
    return stored.ifEmpty { listOf(getColorValue(legacyKey, Color.Black).toArgb()) }
}

/** Stop positions as stored; an empty or malformed list means the shader spreads them evenly. */
fun Preferences.getGradientPositions(key: Preferences.Key<String>): List<Float> =
    this[key].orEmpty()
        .split(',')
        .mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toFloatOrNull() }

@Composable
fun DataStore<Preferences>.getGradientPositions(key: Preferences.Key<String>): List<Float> =
    getPreferenceValue(key, "")
        .split(',')
        .mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toFloatOrNull() }

@Composable
fun DataStore<Preferences>.getGradientStops(
    key: Preferences.Key<String>,
    legacyKey: Preferences.Key<String>
): List<Int> {
    val stored = getPreferenceValue(key, "")
        .split(',')
        .mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toNullableColor()?.toArgb() }
    return stored.ifEmpty { listOf(getColorValue(legacyKey, Color.Black).toArgb()) }
}

/**
 * Writes the hero card's icon source and its pack together. Two separate setters could be split
 * by a profile switch (which snapshots and restores the whole key set), leaving one profile with
 * the source and another with the pack.
 */
suspend fun DataStore<Preferences>.setPrimarySource(source: Source, packageName: String?) {
    preferenceAccessMutex.withLock {
        edit { target ->
            target[PrimarySourceKey] = source.ordinal
            packageName?.let { target[PrimaryIconPackKey] = it }
        }
    }
}

/** Startup restore of the last BUILT source/pack — same atomicity requirement. */
suspend fun DataStore<Preferences>.restoreBuiltPrimarySource(snapshot: Preferences) {
    preferenceAccessMutex.withLock {
        edit { target ->
            target[PrimarySourceKey] = snapshot.getIntValue(
                BuiltPrimarySourceKey, SOURCE_DEFAULT.ordinal
            )
            target[PrimaryIconPackKey] = snapshot.getStringValue(BuiltPrimaryIconPackKey)
        }
    }
}

/**
 * The preference keys one colour style lives in. Grouping them lets the whole style be written
 * in a single edit — five separate setters could be split by a profile switch, leaving a gradient
 * with colours from one profile and an angle from another.
 */
data class ColorStyleKeys(
    val mode: Preferences.Key<Int>,
    val gradientType: Preferences.Key<Int>,
    val gradientAngle: Preferences.Key<Int>,
    val firstColor: Preferences.Key<String>,
    val gradientColors: Preferences.Key<String>,
    val gradientPositions: Preferences.Key<String>,
    // Pre-multi-stop key holding the single second colour, kept in sync for older builds.
    val legacyGradientColor: Preferences.Key<String>? = null
)

val ColorizerStyleKeys = ColorStyleKeys(
    mode = ColorizerModeKey,
    gradientType = ColorizerGradientTypeKey,
    gradientAngle = ColorizerGradientAngleKey,
    firstColor = IconColorKey,
    gradientColors = ColorizerGradientColorsKey,
    gradientPositions = ColorizerGradientPositionsKey,
    legacyGradientColor = ColorizerGradientColorKey
)

val BackgroundStyleKeys = ColorStyleKeys(
    mode = BackgroundColorizerModeKey,
    gradientType = BackgroundGradientTypeKey,
    gradientAngle = BackgroundGradientAngleKey,
    firstColor = BackgroundColorKey,
    gradientColors = BackgroundGradientColorsKey,
    gradientPositions = BackgroundGradientPositionsKey
)

val GlobalShapeStyleKeys = ColorStyleKeys(
    mode = GlobalShapeColorizerModeKey,
    gradientType = GlobalShapeGradientTypeKey,
    gradientAngle = GlobalShapeGradientAngleKey,
    firstColor = GlobalShapeColorKey,
    gradientColors = GlobalShapeGradientColorsKey,
    gradientPositions = GlobalShapeGradientPositionsKey
)

val GlobalColorizerStyleKeys = ColorStyleKeys(
    mode = GlobalColorizerModeKey,
    gradientType = GlobalColorizerGradientTypeKey,
    gradientAngle = GlobalColorizerGradientAngleKey,
    firstColor = GlobalColorizeColorKey,
    gradientColors = GlobalColorizerGradientColorsKey,
    gradientPositions = GlobalColorizerGradientPositionsKey,
    legacyGradientColor = GlobalColorizerGradientColorKey
)

// The outline's first colour IS its legacy key, so there is no separate legacy stop to write —
// syncing one would overwrite the first colour with the second (that bug shipped once).
val OutlineStyleKeys = ColorStyleKeys(
    mode = OutlineColorizerModeKey,
    gradientType = OutlineGradientTypeKey,
    gradientAngle = OutlineGradientAngleKey,
    firstColor = OutlineColorKey,
    gradientColors = OutlineGradientColorsKey,
    gradientPositions = OutlineGradientPositionsKey
)

suspend fun DataStore<Preferences>.setColorStyle(
    keys: ColorStyleKeys,
    mode: Int,
    gradientType: Int,
    gradientAngle: Int,
    firstColor: Color,
    gradientStops: List<Int>,
    // Covers the first colour too, so it is one longer than [gradientStops]; empty = even spread.
    gradientPositions: List<Float> = emptyList()
) {
    preferenceAccessMutex.withLock {
        edit { target ->
            target.setColorStyle(
                keys, mode, gradientType, gradientAngle,
                firstColor, gradientStops, gradientPositions
            )
        }
    }
}

/** Writes one complete colour style into an existing atomic preferences edit or staged copy. */
fun MutablePreferences.setColorStyle(
    keys: ColorStyleKeys,
    mode: Int,
    gradientType: Int,
    gradientAngle: Int,
    firstColor: Color,
    gradientStops: List<Int>,
    gradientPositions: List<Float> = emptyList()
) {
    this[keys.mode] = mode
    this[keys.gradientType] = gradientType
    this[keys.gradientAngle] = gradientAngle
    this[keys.firstColor] = firstColor.toHexString()
    this[keys.gradientColors] = gradientStops.joinToString(",") { Color(it).toHexString() }
    this[keys.gradientPositions] = gradientPositions.joinToString(",")
    keys.legacyGradientColor
        ?.takeIf { it != keys.firstColor }
        ?.let { legacy ->
            gradientStops.firstOrNull()?.let { this[legacy] = Color(it).toHexString() }
        }
}


//Enum
@Composable
inline fun <reified T: Enum<T>> DataStore<Preferences>.getEnumValue(
    key: Preferences.Key<Int>
    , default: T
): T {
    val ordinal = getPreferenceValue(key, default.ordinal)
    // Stored ordinals can outlive the enum (corrupt store, profile snapshot from a build with
    // more values) — fall back to the default instead of crashing on an out-of-range index.
    return enumEntries<T>().getOrNull(ordinal) ?: default
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
    // Same out-of-range guard as the DataStore variant above.
    return enumEntries<T>().getOrNull(ordinal) ?: default
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
    preferenceAccessMutex.withLock {
        edit { settings ->
            settings[key] = value
        }
    }
}

/** Reads a snapshot only after every preference write that started before it has completed. */
suspend fun DataStore<Preferences>.getPreferencesAfterPendingWrites(): Preferences =
    preferenceAccessMutex.withLock { data.first() }

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
fun getImageEditLabels(includeSegments: Boolean = false): Map<ImageEdit, String> {
    val base = mapOf(ImageEdit.NONE to stringResource(id = R.string.none)
        , ImageEdit.PATH to stringResource(id = R.string.pathDetection)
        , ImageEdit.EDGE to stringResource(id = R.string.edgeDetection)
        , ImageEdit.COLORIZE to stringResource(id = R.string.colorize)
        , ImageEdit.REMOVE_BACKGROUND to stringResource(id = R.string.removeBackground))
    // Segments are picked on one icon's own artwork, so only the edit dialog offers them.
    return if (includeSegments) {
        base + (ImageEdit.COLORIZE_SEGMENTS to stringResource(id = R.string.colorizeSegments))
    } else base
}

@Composable
fun getTextTypeLabels(includeCustom: Boolean = false): Map<TextType, String> {
    val base = mapOf(TextType.FULL_NAME to stringResource(id = R.string.fullName)
        , TextType.ONE_LETTER to stringResource(id = R.string.firstLetter)
        , TextType.TWO_LETTERS to stringResource(id = R.string.twoLetters))
    return if (includeCustom) base + (TextType.CUSTOM to stringResource(id = R.string.textTypeCustom)) else base
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
    // New entries go at the END: ordinals are persisted by index. COLORIZE_SEGMENTS is the
    // per-app "colourize only these regions" modifier, so the pack-wide dropdowns skip it.
    NONE, PATH, EDGE, COLORIZE, REMOVE_BACKGROUND, COLORIZE_SEGMENTS
}

enum class TextType {
    // CUSTOM stays last so existing stored ordinals (persisted by index) keep their value.
    // It only makes sense per app (the edit dialog); the global dropdowns don't offer it.
    FULL_NAME, ONE_LETTER, TWO_LETTERS, CUSTOM
}
