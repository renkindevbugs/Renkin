package dev.renkinProject.renkin.apk

import androidx.datastore.preferences.core.Preferences
import dev.renkinProject.renkin.icon.creator.GenerationOptions

/** The applied Global style an editor can keep on top of its result: [recipe] to describe it, [options] to render it. */
data class EditorGlobalLayer(val recipe: Preferences, val options: GenerationOptions)
