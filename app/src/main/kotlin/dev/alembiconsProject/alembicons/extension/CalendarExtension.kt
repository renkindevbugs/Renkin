package dev.alembiconsProject.alembicons.extension

// Compiled once: a calendar day drawable is "<prefix>_<1-2 digits>", e.g. "google_cal_26".
private val CALENDAR_DAY_REGEX = Regex("^(.+_)\\d{1,2}$")

/**
 * The day-rotation prefix of a calendar drawable name — the name minus its trailing 1–2 digits
 * (`"google_cal_26"` → `"google_cal_"`), or null when it doesn't end in 1–2 digits after an
 * underscore (so it isn't a calendar-day candidate).
 */
fun String.calendarPrefixOrNull(): String? = CALENDAR_DAY_REGEX.find(this)?.groupValues?.get(1)

/** Whether this drawable name could be a calendar day (ends in 1–2 digits after an underscore). */
fun String.isCalendarDayName(): Boolean = calendarPrefixOrNull() != null
