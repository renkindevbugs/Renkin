package dev.alembiconsProject.alembicons

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point for Hilt. [HiltAndroidApp] triggers Hilt's code generation and
 * creates the app-level dependency container that the rest of the app injects from.
 */
@HiltAndroidApp
class RenkinApplication : Application()
