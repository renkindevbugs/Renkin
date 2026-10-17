// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.ksp) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.roborazzi) apply false
}

allprojects {
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("ru.solrudev.ackpine:ackpine-core"))
                .using(project(":ackpine-core-compat"))
                .because("Guard the confirmation Activity launch in pinned Ackpine 0.25.4 sources")
        }
    }
}
