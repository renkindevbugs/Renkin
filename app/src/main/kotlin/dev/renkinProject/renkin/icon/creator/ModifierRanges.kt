package dev.renkinProject.renkin.icon.creator

// One place for the limits that sliders, recipes, presets and draft recovery must agree on;
// a value outside them is rejected or clamped, so they differing would silently lose settings.

/** Icon scale and shape plate scale. */
val MODIFIER_SCALE_RANGE = 0.5f..1.5f

/** Material You pack line-thickness multiplier. */
val MATERIAL_YOU_STROKE_SCALE_RANGE = 0.5f..2f
