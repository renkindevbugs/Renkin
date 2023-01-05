package com.kaanelloed.iconeration

import android.content.Context
import brut.directory.ExtFile
import brut.directory.ZipUtils
import brut.util.OS
import java.io.File

class ResourcesBuilder(ctx: Context, private val framework: File) {
    private val aaptPath = File(ctx.applicationInfo.nativeLibraryDir).resolve("libaapt2.so").absolutePath
    private val tmpDir = ctx.cacheDir.resolve("tmp")
    private val buildDir = tmpDir.resolve("build")
    private lateinit var opts: BuildOptions

    fun buildApk(options: BuildOptions, manifest: File, resourceDir: File, assetDir: File, classesFile: File, notCompress: Array<String>, apkFile: File) {
        opts = options

        tmpDir.deleteRecursively()
        tmpDir.mkdirs()

        val notCompressFile = tmpDir.resolve("notCompress.txt")
        notCompressFile.appendText(notCompress.joinToString("\n"))

        val compiledResources = tmpDir.resolve("resources.zip")
        val linkedResources = tmpDir.resolve("linkedResources.zip")

        compileResources(resourceDir, compiledResources)
        linkResources(manifest, compiledResources, notCompressFile, linkedResources)
        fillBuildDir(linkedResources, classesFile, buildDir)

        ZipUtils.zipFolders(buildDir, apkFile, assetDir, notCompress.toList())
        tmpDir.deleteRecursively()
    }

    fun getClassesFromApk(apkFile: File, fileName: String, destDir: File): File {
        val extApkFile = ExtFile(apkFile)
        extApkFile.directory.copyToDir(destDir, fileName)
        extApkFile.close()

        return destDir.resolve(fileName)
    }

    private fun compileResources(resourceDir: File, dest: File) {
        val compileArgs = mutableListOf<String>()
        compileArgs.add(aaptPath)
        compileArgs.add("compile")
        compileArgs.add("--dir")
        compileArgs.add(resourceDir.absolutePath)
        compileArgs.add("--legacy")
        compileArgs.add("-o")
        compileArgs.add(dest.absolutePath)

        OS.exec(compileArgs.toTypedArray())
    }

    private fun linkResources(manifest: File, resourceZip: File, notCompressFile: File, dest: File) {
        val linkArgs = mutableListOf<String>()
        linkArgs.add(aaptPath)
        linkArgs.add("link")
        linkArgs.add("-o")
        linkArgs.add(dest.absolutePath)
        linkArgs.add("--package-id")
        linkArgs.add(opts.packageId)
        linkArgs.add("--min-sdk-version")
        linkArgs.add(opts.minSdk)
        linkArgs.add("--target-sdk-version")
        linkArgs.add(opts.targetSdk)
        linkArgs.add("--version-code")
        linkArgs.add(opts.versionCode)
        linkArgs.add("--version-name")
        linkArgs.add(opts.versionName)
        linkArgs.add("--no-auto-version")
        linkArgs.add("--no-version-vectors")
        linkArgs.add("--no-version-transitions")
        linkArgs.add("--no-resource-deduping")
        linkArgs.add("--allow-reserved-package-id")
        linkArgs.add("-e")
        linkArgs.add(notCompressFile.absolutePath)
        linkArgs.add("-0")
        linkArgs.add("arsc")
        linkArgs.add("-I")
        linkArgs.add(framework.absolutePath)
        linkArgs.add("--manifest")
        linkArgs.add(manifest.absolutePath)
        linkArgs.add(resourceZip.absolutePath)

        OS.exec(linkArgs.toTypedArray())
    }

    private fun fillBuildDir(linkedFile: File, classesFile: File, dest: File) {
        val extLinked = ExtFile(linkedFile)
        extLinked.directory.copyToDir(dest)
        extLinked.close()

        classesFile.copyTo(dest.resolve(classesFile.name))
    }

    class BuildOptions(var packageId: String, var minSdk: String, var targetSdk: String, var versionCode: String, var versionName: String)
}