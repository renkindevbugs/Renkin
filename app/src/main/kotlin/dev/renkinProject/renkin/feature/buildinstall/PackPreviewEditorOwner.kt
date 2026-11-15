package dev.renkinProject.renkin.feature.buildinstall

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import dev.renkinProject.renkin.MainViewModel

/** The live home owner: constructing another MainViewModel would reload and drop unsaved icons. */
@Composable
internal fun iconEditorViewModel(): MainViewModel = hiltViewModel()
