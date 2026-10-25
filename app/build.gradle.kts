@file:OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.roborazzi)
}

val revisionOverride = providers.gradleProperty("renkin.revision")
val sourceRevision = revisionOverride.orElse(
    providers.exec {
        workingDir(rootDir)
        commandLine("git", "rev-parse", "--short=12", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }
).get()
require(sourceRevision.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid renkin.revision" }
val sourceDirty = if (revisionOverride.isPresent) false else providers.exec {
    workingDir(rootDir)
    commandLine("git", "status", "--porcelain", "--untracked-files=no", "--ignore-submodules=untracked")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.isNotBlank() }.get()
val buildRevision = sourceRevision + if (sourceDirty) "-dirty" else ""

// Verification must not load signing credentials even on a configured developer machine.
val unsignedRelease = providers.gradleProperty("renkin.unsignedRelease")
    .map { it.toBooleanStrict() }
    .orElse(false)
    .get()

// Plain assembleRelease keeps local signing; CI explicitly requests an unsigned release.
val localProps = Properties().apply {
    if (!unsignedRelease) {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
}

android {
    namespace = "dev.renkinProject.renkin"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // Renamed from the fork's com.kaanelloed.iconeration (2026-07): a brand-new app
        // identity — existing installs of the old id do not update into this one.
        applicationId = "dev.renkinProject.renkin"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Renkin's own year.month.patch scheme, started for the first release under the new
        // app id (the 2025.02.00 / 44 values were inherited from the Alembicons fork).
        versionCode = 55
        versionName = "2026.09.02"
        buildConfigField("String", "SOURCE_REVISION", "\"$buildRevision\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                val outputFileName = "Renkin-v${variant.versionName}.apk"
                output.outputFileName = outputFileName
            }
    }

    signingConfigs {
        localProps.getProperty("renkin.keystore")?.let { keystorePath ->
            create("release") {
                storeFile = file(keystorePath)
                storePassword = localProps.getProperty("renkin.keystore.password")
                keyAlias = localProps.getProperty("renkin.key.alias")
                keyPassword = localProps.getProperty("renkin.key.password")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    lint {
        baseline = file("lint-baseline.xml")
        // Known issues live in the baseline; any new warning fails lint.
        warningsAsErrors = true
        // Version-freshness checks change whenever a library publishes, not when the code does.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)
    implementation(libs.imagetracer.compose)
    implementation(libs.cannyedge.compose)

    //Apk related
    implementation(libs.arscLib)
    implementation(libs.ackpine.core)
    implementation(libs.ackpine.ktx)
    implementation(libs.ackpine.shizuku)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.apksigner.compat)

    //Compose
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    debugImplementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.material3.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.window)
    implementation(libs.colorpicker.compose)
    implementation("androidx.compose.material:material-icons-extended")

    //Data
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    //Dependency injection (Hilt)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    //Svg
    implementation(libs.android.svg)

    //Compat
    coreLibraryDesugaring(libs.android.tools.desugar.jdk.libs.nio)

    //Test
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    // Compose UI tests run locally on Robolectric (no device): the BOM pins the versions,
    // ui-test-manifest provides the host activity createComposeRule needs.
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // debugImplementation (not test): the host ComponentActivity must be merged into the
    // debug manifest for Robolectric to resolve it.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

roborazzi {
    outputDir.set(file("src/test/screenshots"))
    compare {
        outputDir.set(file("build/outputs/screenshots_comparison"))
    }
}

tasks.withType<Test>().configureEach {
    inputs.files(fileTree("src/test/screenshots") { include("**/*.png") })
        .withPropertyName("renderingReferences")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg(RoomSchemaArgProvider(File(projectDir, "schemas")))
}

class RoomSchemaArgProvider(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val schemaDir: File
) : CommandLineArgumentProvider {

    override fun asArguments(): Iterable<String> {
        return listOf("room.schemaLocation=${schemaDir.path}")
    }
}

// Compose stability diagnostics on demand: -Prenkin.composeReports=true writes classes.txt and
// composables.txt for every compiled variant into app/build/compose_compiler.
if (providers.gradleProperty("renkin.composeReports").orNull == "true") {
    composeCompiler {
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
        metricsDestination = layout.buildDirectory.dir("compose_compiler")
    }
}

abstract class GeneratedFontResources : Sync() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

// The Arcticons-Font submodule is the only copy of the font; it reaches the APK as a generated
// resource, so configuring Gradle never writes into src/.
val arcticonsFont = rootProject.layout.projectDirectory.file("Arcticons-Font/ArcticonsSans-Regular.otf")
val prepareArcticonsFont = tasks.register<GeneratedFontResources>("prepareArcticonsFont") {
    outputDirectory.set(layout.buildDirectory.dir("generated/arcticonsFont/res"))
    into(outputDirectory)
    from(arcticonsFont) {
        into("font")
        rename { "arcticonssans_regular.otf" }
    }
    doFirst {
        check(arcticonsFont.asFile.isFile) { "Initialize the Arcticons-Font submodule with git submodule update --init" }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(prepareArcticonsFont) { it.outputDirectory }
    }
}

//Disable baseline profile (https://gist.github.com/obfusk/61046e09cee352ae6dd109911534b12e#fix-proposed-by-linsui-disable-baseline-profiles)
tasks.whenTaskAdded {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}
