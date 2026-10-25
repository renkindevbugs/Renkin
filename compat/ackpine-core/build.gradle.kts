import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.ksp)
}

group = "ru.solrudev.ackpine"
version = "0.25.4-renkin1"

require(libs.versions.ackpineCore.get() == "0.25.4") {
    "Review the Ackpine confirmation guard before changing the upstream version"
}

val upstream = rootProject.layout.projectDirectory.dir("third_party/ackpine-source/ackpine-core")
val confirmationPath = "ru/solrudev/ackpine/impl/installer/activity/SessionBasedInstallConfirmationActivity.kt"
val confirmationSource = upstream.file("src/main/kotlin/$confirmationPath")
val originalSha256 = "57618874014f749700ed52f2fdbb138e38c6a124a4a14e324f5ecd937ddb5dfd"

abstract class PrepareAckpineSources : Sync() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

val prepareSources = tasks.register<PrepareAckpineSources>("prepareAckpineSources") {
    outputDirectory.set(layout.buildDirectory.dir("generated/ackpine/kotlin"))
    into(outputDirectory)
    from(upstream.dir("src/main/kotlin")) { exclude(confirmationPath) }
    from("patched/kotlin")
    inputs.file(confirmationSource).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("originalSha256", originalSha256)
    doFirst {
        check(confirmationSource.asFile.isFile) {
            "Initialize pinned dependencies with git submodule update --init --recursive"
        }
        val bytes = confirmationSource.asFile.readText().replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(actual == originalSha256) { "Ackpine confirmation source changed; review the local guard" }
    }
}

val prepareVerificationFixtures = tasks.register<PrepareAckpineSources>("prepareAckpineVerificationFixtures") {
    outputDirectory.set(layout.buildDirectory.dir("generated/ackpine/verification"))
    into(outputDirectory)
    from(upstream.dir("src/test/kotlin")) {
        include("ru/solrudev/ackpine/impl/testutil/TestCompletableSession.kt")
    }
}

val schemasDirectory = layout.buildDirectory.dir("ackpine-schemas")
val prepareSchemas = tasks.register<Sync>("prepareAckpineSchemas") {
    from(upstream.dir("schemas"))
    into(schemasDirectory)
}

ksp {
    arg("room.schemaLocation", schemasDirectory.get().asFile.path)
}

tasks.matching { it.name.startsWith("ksp") }.configureEach {
    dependsOn(prepareSchemas)
}

android {
    namespace = "ru.solrudev.ackpine.core"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = 23
        consumerProguardFiles(upstream.file("consumer-rules.pro").asFile)
    }
    sourceSets.getByName("main") {
        java.setSrcDirs(emptyList<String>())
        manifest.srcFile(upstream.file("src/main/AndroidManifest.xml"))
        res.setSrcDirs(listOf(upstream.dir("src/main/res")))
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xjvm-default=all", "-Xconsistent-data-class-copy-visibility")
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.moduleName.set("ackpine-core")
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(prepareSources) { it.outputDirectory }
        if (variant.buildType == "debug") {
            variant.sources.java?.addGeneratedSourceDirectory(prepareVerificationFixtures) { it.outputDirectory }
        }
    }
}

dependencies {
    api(libs.ackpine.api)
    api(libs.androidx.startup)
    implementation(libs.ackpine.runtime)
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
