import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt.android)
}

// Release signing credentials live in local.properties (gitignored), so a signed release
// builds with plain `gradlew assembleRelease`; without them (CI, F-Droid) the release APK
// is simply unsigned, exactly as before.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
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
        versionCode = 50
        versionName = "2026.07.05"

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

    kotlinOptions {
        jvmTarget = "17"
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)
    implementation(libs.imagetracer.compose)
    implementation(libs.cannyedge.compose)

    //Apk related
    implementation(libs.arscLib)
    implementation(libs.ackpine.core)
    implementation(libs.ackpine.ktx)
    implementation(libs.apksigner.compat)

    //Compose
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    debugImplementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.material3.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
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

//Pre build
task("arcticons-font") {
    println("Copy Arcticons Sans font")

    val gitFont = File(rootDir, "Arcticons-Font/ArcticonsSans-Regular.otf")
    val resFont = File(projectDir, "src/main/res/font/arcticonssans_regular.otf")
    gitFont.copyTo(resFont, true)
}

//Disable baseline profile (https://gist.github.com/obfusk/61046e09cee352ae6dd109911534b12e#fix-proposed-by-linsui-disable-baseline-profiles)
tasks.whenTaskAdded {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}
