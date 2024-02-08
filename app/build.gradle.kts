plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.kaanelloed.iconeration"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kaanelloed.iconeration"
        minSdk = 26
        targetSdk = 34
        versionCode = 24
        versionName = "1.3.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                val outputFileName = "Iconeration-v${variant.versionName}.apk"
                output.outputFileName = outputFileName
            }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    //Apk related
    implementation("io.github.reandroid:ARSCLib:1.2.4")
    implementation("org.bouncycastle:bcpkix-jdk15on:1.70")
    implementation("com.android.tools.build:apksig:8.2.2")
    implementation("ru.solrudev.ackpine:ackpine-core:0.5.0")
    implementation("ru.solrudev.ackpine:ackpine-ktx:0.5.0")

    //Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    debugImplementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.material3:material3-android:1.2.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("com.github.skydoves:colorpicker-compose:1.0.7")

    //Data
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    //Test
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
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