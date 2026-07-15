package dev.renkinProject.renkin.apk

import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.toComponentInfo

/** One app's calendar source before it is renamed into the generated pack's namespace. */
internal data class CalendarSelection(
    val application: InstalledApplication,
    val sourcePack: String,
    val sourcePrefix: String
)

internal data class CalendarBuildData<T : Any>(
    val mappings: Map<InstalledApplication, String>,
    val drawables: Map<String, T>
)

private data class CalendarSourceKey(val pack: String, val prefix: String)

/**
 * Loads a calendar set under one canonical 1..31 key space. Packs may name the first nine
 * resources either `prefix1` or `prefix01`; plain names win if a malformed pack contains both.
 * Missing days repeat the first available drawable so a calendar mapping never points at a
 * resource that the generated pack does not contain.
 */
internal fun <T : Any> loadCalendarDays(
    prefix: String,
    load: (name: String) -> T?
): Map<Int, T> {
    val days = mutableMapOf<Int, T>()
    for (day in 1..31) {
        val plain = prefix + day
        val padded = prefix + day.toString().padStart(2, '0')
        val drawable = load(plain) ?: if (padded != plain) load(padded) else null
        if (drawable != null) days[day] = drawable
    }

    val fallback = days.values.firstOrNull() ?: return emptyMap()
    for (day in 1..31) days.putIfAbsent(day, fallback)
    return days
}

/**
 * Merges global and per-app selections and gives every distinct source pack/prefix pair its own
 * generated resource prefix. This prevents two packs that both use names such as `calendar_1`
 * from silently overwriting one another. Later selections override the same launcher component,
 * which is how a per-app choice replaces its global mapping.
 */
internal fun <T : Any> buildCalendarData(
    selections: List<CalendarSelection>,
    loadDays: (sourcePack: String, sourcePrefix: String) -> Map<Int, T>
): CalendarBuildData<T> {
    val selectedByComponent = linkedMapOf<String, CalendarSelection>()
    selections.forEach { selectedByComponent[it.application.toComponentInfo()] = it }

    val loaded = selectedByComponent.values
        .map { CalendarSourceKey(it.sourcePack, it.sourcePrefix) }
        .distinct()
        .sortedWith(compareBy(CalendarSourceKey::pack, CalendarSourceKey::prefix))
        .mapNotNull { key -> loadDays(key.pack, key.prefix).takeIf { it.isNotEmpty() }?.let { key to it } }

    val exportedPrefixes = loaded.mapIndexed { index, (key, _) ->
        key to "renkin_calendar_${index + 1}_"
    }.toMap()

    val mappings = linkedMapOf<InstalledApplication, String>()
    selectedByComponent.values.forEach { selection ->
        val key = CalendarSourceKey(selection.sourcePack, selection.sourcePrefix)
        exportedPrefixes[key]?.let { mappings[selection.application] = it }
    }

    val drawables = linkedMapOf<String, T>()
    loaded.forEach { (key, days) ->
        val exportedPrefix = exportedPrefixes.getValue(key)
        days.forEach { (day, drawable) -> drawables[exportedPrefix + day] = drawable }
    }

    return CalendarBuildData(mappings, drawables)
}
