package dev.renkinProject.renkin.apk

import dev.renkinProject.renkin.packages.InstalledIconLoader

import android.content.Context
import android.graphics.Typeface
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.InstalledApplication
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.data.FallbackSource
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconGenerationResult
import dev.renkinProject.renkin.icon.creator.withSource
import dev.renkinProject.renkin.icon.creator.withModifiers
import dev.renkinProject.renkin.icon.creator.withEffect
import dev.renkinProject.renkin.icon.creator.IconGenerator
import dev.renkinProject.renkin.icon.creator.IconImageEditPipeline
import dev.renkinProject.renkin.icon.creator.IconPackContainer
import dev.renkinProject.renkin.packages.ApplicationManager
import dev.renkinProject.renkin.packages.IconPackCatalog
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import dev.renkinProject.renkin.extension.contentHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class IconGenerationSources(
    val primary: () -> Map<InstalledApplication, ResourceDrawable>,
    val secondary: () -> Map<InstalledApplication, ResourceDrawable>,
    val fallbackPack: String
)

/**
 * Runs the actual icon generation: it wires [IconGenerator] together with the pack
 * drawables from [IconPackRepository] and produces the icons. It holds no app-list state —
 * [ApplicationProvider] keeps the preference parsing, calendar trigger and writes the
 * results back, delegating only the generation here.
 */
class IconGenerationService(
    private val context: Context,
    private val iconPackRepo: IconPackRepository,
    private val iconPackCatalog: IconPackCatalog,
    private val installedIcons: InstalledIconLoader =
        InstalledIconLoader(context)
) {
    data class ValidatedPackIcon(val icon: IconPackDrawable?, val sourceChanged: Boolean)

    private val appManager: ApplicationManager by lazy { ApplicationManager(context) }

    /** Generates one icon from the primary pack only (preview / single-pack lookups). */
    suspend fun getIcon(
        application: PackageInfoStruct,
        options: GenerationOptions,
        customIcon: ResourceDrawable? = null
    ): IconPackDrawable? = generatePreview(application, options, customIcon, retainInput = false).icon

    suspend fun getIconPreview(
        application: PackageInfoStruct,
        options: GenerationOptions,
        customIcon: ResourceDrawable?,
        ownedTypeface: Typeface? = null
    ): IconGenerationResult = generatePreview(application, options, customIcon, retainInput = true, ownedTypeface = ownedTypeface)

    private suspend fun generatePreview(
        application: PackageInfoStruct,
        options: GenerationOptions,
        customIcon: ResourceDrawable?,
        retainInput: Boolean,
        ownedTypeface: Typeface? = null
    ): IconGenerationResult = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()

        val installedApplication = application.toInstalledApplication()
        val appDrawable = if (options.source.primarySource == Source.ICON_PACK && customIcon == null) {
            iconPackRepo.getAppDrawable(installedApplication, options.source.primaryIconPack)
        } else null
        val pack1 = IconPackContainer(
            options.source.primaryIconPack,
            appDrawable?.let { mapOf(installedApplication to it) } ?: emptyMap()
        )
        val pack2 = IconPackContainer("", emptyMap())

        val builder = IconGenerator(
            context,
            options,
            pack1,
            pack2,
            iconPackCatalog = iconPackCatalog,
            ownedTypeface = ownedTypeface,
            installedIcons = installedIcons
        )
        val preview = builder.generatePreview(application, customIcon, retainInput)
        currentCoroutineContext().ensureActive()
        preview
    }

    /** Resolves a pack's drawable by name and builds the icon it provides for [application]. */
    suspend fun getIconFromPackDrawable(
        application: PackageInfoStruct,
        packPackage: String,
        drawableName: String,
        options: GenerationOptions
    ): IconPackDrawable? = getValidatedIconFromPackDrawable(
        application, packPackage, drawableName, expectedHash = null, options = options
    ).icon

    /** Resolves once, verifies the raw pack artwork when requested, then builds its preview. */
    suspend fun getValidatedIconFromPackDrawable(
        application: PackageInfoStruct,
        packPackage: String,
        drawableName: String,
        expectedHash: String?,
        options: GenerationOptions
    ): ValidatedPackIcon {
        val resource = appManager.getIconPackDrawableEntries(packPackage, listOf(drawableName))
            .firstOrNull()
            ?.resource
        // Only a caller that verifies the artwork pays for the PNG encode + SHA-256.
        val currentHash = expectedHash?.let { resource?.drawable?.toSafeBitmapOrNull()?.contentHash() }
        if (iconSourceChanged(expectedHash, currentHash)) {
            return ValidatedPackIcon(null, sourceChanged = true)
        }
        resource ?: return ValidatedPackIcon(null, sourceChanged = false)
        val packOptions = options.withSource {
            copy(primarySource = Source.ICON_PACK, primaryIconPack = packPackage)
        }.withModifiers {
            withEffect { copy(imageEdit = ImageEdit.NONE) }
        }
        return ValidatedPackIcon(getIcon(application, packOptions, resource), sourceChanged = false)
    }

    /** Applies the modifier from [options] to an already-built icon. */
    suspend fun applyModifier(icon: IconPackDrawable, options: GenerationOptions): IconPackDrawable =
        withContext(Dispatchers.Default) {
            modifierPipeline(options).applyPrimary(icon)
        }

    /** Regenerates one app's icon from both packs, handing the result to [onResult]. */
    internal suspend fun refreshIcon(
        application: PackageInfoStruct,
        sourceOptions: GenerationOptions,
        modifierOptions: GenerationOptions?,
        sources: IconGenerationSources,
        onResult: (PackageInfoStruct, IconPackDrawable?, IconPackDrawable?, sourcePackName: String) -> Unit
    ) = withContext(Dispatchers.Default) {
        val builder = buildGenerator(sourceOptions, sources)
        val modifier = modifierOptions?.let { modifierPipeline(it) }
        val generationContext = currentCoroutineContext()
        builder.generateIcon(application) { app, base, sourcePack ->
            generationContext.ensureActive()
            val rendered = base?.let { modifier?.applyPrimary(it) ?: it }
            generationContext.ensureActive()
            onResult(app, base, rendered, sourcePack)
        }
    }

    internal data class GeneratedIconResult(
        val key: String,
        val base: IconPackDrawable?,
        val rendered: IconPackDrawable?,
        val isFallback: Boolean,
        val sourcePack: String
    )

    internal suspend fun refreshIcons(
        applications: List<PackageInfoStruct>,
        sourceOptions: GenerationOptions,
        modifierOptions: GenerationOptions?,
        sources: IconGenerationSources,
        onProgress: (done: Int, total: Int) -> Unit,
        onBatch: suspend (List<GeneratedIconResult>) -> Unit
    ) = withContext(Dispatchers.Default) {
        onProgress(0, applications.size)
        if (applications.isEmpty()) return@withContext
        val builder = buildGenerator(sourceOptions, sources)
        val modifier = modifierOptions?.let { modifierPipeline(it) }
        val generationContext = currentCoroutineContext()
        var done = 0
        for (batch in applications.asSequence().chunked(20)) {
            generationContext.ensureActive()
            val results = mutableListOf<GeneratedIconResult>()
            builder.generateIcons(batch) { app, base, fallback, sourcePack ->
                generationContext.ensureActive()
                val rendered = base?.let { modifier?.applyPrimary(it) ?: it }
                generationContext.ensureActive()
                results += GeneratedIconResult(app.key, base, rendered, fallback, sourcePack)
            }
            generationContext.ensureActive()
            onBatch(results)
            done += batch.size
            onProgress(done, applications.size)
        }
    }

    /** Previews the fallback styling for [options]' source on each of [samples]. */
    suspend fun fallbackPreview(
        options: GenerationOptions,
        samples: List<PackageInfoStruct>
    ): List<IconPackDrawable> = withContext(Dispatchers.Default) {
        val builder = buildGenerator(options)
        samples.mapNotNull { builder.fallbackIcon(it) }
    }

    internal fun captureRefreshSources(options: GenerationOptions): IconGenerationSources {
        val fallbackPack = when (options.source.fallbackSource) {
            FallbackSource.PRIMARY -> options.source.primaryIconPack
            FallbackSource.SECONDARY -> options.source.secondaryIconPack
            FallbackSource.NONE -> ""
        }
        return IconGenerationSources(
            iconPackRepo.captureAppDrawables(options.source.primaryIconPack),
            iconPackRepo.captureAppDrawables(options.source.secondaryIconPack),
            fallbackPack
        )
    }

    private fun buildGenerator(
        options: GenerationOptions,
        sources: IconGenerationSources = captureRefreshSources(options)
    ): IconGenerator {
        val pack1 = IconPackContainer(options.source.primaryIconPack, sources.primary())
        val pack2 = IconPackContainer(options.source.secondaryIconPack, sources.secondary())
        val fallback = iconPackRepo.getIconPackFallback(sources.fallbackPack)
        return IconGenerator(
            context,
            options,
            pack1,
            pack2,
            fallback,
            sources.fallbackPack,
            iconPackCatalog,
            installedIcons = installedIcons
        )
    }

    private fun modifierPipeline(options: GenerationOptions) =
        IconImageEditPipeline(context.resources, options)

    suspend fun getIconPackIcons(
        iconPackName: String,
        options: GenerationOptions,
        drawables: List<ResourceDrawable>
    ): Map<ResourceDrawable, IconPackDrawable?> = withContext(Dispatchers.Default) {
        val exportDrawables = mutableMapOf<ResourceDrawable, IconPackDrawable?>()

        val pack = IconPackContainer("", emptyMap())

        val builder = IconGenerator(
            context,
            options,
            pack,
            pack,
            iconPackCatalog = iconPackCatalog
        )
        for (drawable in drawables) {
            // One broken icon must not take the whole pack down (#119)
            exportDrawables[drawable] = try {
                builder.colorizeFromIconPack(iconPackName, drawable)
            } catch (_: Exception) {
                null
            }
        }

        exportDrawables
    }
}

internal fun iconSourceChanged(expectedHash: String?, currentHash: String?): Boolean =
    expectedHash != null && expectedHash != currentHash
