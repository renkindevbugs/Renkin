plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

// Measures cold start and application-list scrolling against the app's release-like "benchmark"
// build (application id ...renkin.benchmark, debug key, so it never replaces an installed release).
// With one connected device:
//   ./gradlew -Prenkin.unsignedRelease=true :macrobenchmark:connectedBenchmarkAndroidTest
// Results land in build/outputs/connected_android_test_additional_output/. Baseline Profiles stay
// disabled in :app (ART profile tasks broke F-Droid reproducible builds), so none is generated here.
android {
    namespace = "dev.renkinProject.renkin.macrobenchmark"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = 28
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" }
}
