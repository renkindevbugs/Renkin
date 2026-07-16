package dev.renkinProject.renkin.apk

import android.content.Context
import dev.renkinProject.renkin.data.ImageEdit
import dev.renkinProject.renkin.data.Source
import dev.renkinProject.renkin.drawable.IconPackDrawable
import dev.renkinProject.renkin.data.FallbackSource
import dev.renkinProject.renkin.drawable.ResourceDrawable
import dev.renkinProject.renkin.icon.creator.GenerationOptions
import dev.renkinProject.renkin.icon.creator.IconGenerator
import dev.renkinProject.renkin.icon.creator.IconPackContainer
import dev.renkinProject.renkin.packages.ApplicationManager
import dev.renkinProject.renkin.packages.PackageInfoStruct
import dev.renkinProject.renkin.drawable.toSafeBitmapOrNull
import dev.renkinProject.renkin.extension.contentHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the actual icon generation: it wires [IconGenerator] together with the pack
 * drawables from [IconPackRepository] and produces the icons. It holds no app-list state —
 * [ApplicationProvider] keeps the preference parsing, calendar trigger and writes the
 * results back, delegating only the generation here.
 */
class IconGenerationService(
    private val context: Context,
    private val iconPackRepo: IconPackRepository
) {
    data class ValidatedPackIcon(val icon: IconPackDrawable?, val sourceChanged: Boolean)

    private val appManager: ApplicationManager by lazy { ApplicationManager(context) }

    /** Generates one icon from the primary pack only (preview / single-pack lookups). */
    suspend fun getIcon(
        application: PackageInfoStruct,
        options: GenerationOptions,
        customIcon: ResourceDrawable? = null
    ): IconPackDrawable? = withContext(Dispatchers.Default) {
        var icon: IconPackDrawable? = null

        val pack1 = IconPackContainer(options.primaryIconPack, iconPackRepo.getAppDrawables(options.primaryIconPack))
        val pack2 = IconPackContainer("", emptyMap())

        val builder = IconGenerator(context, options, pack1, pack2)
        builder.generateIcon(application, customIcon) { _, newIcon ->
            icon = newIcon
        }
        // Note: this single-pack preview path keeps the 2-arg callback (no source pack tracking)
        // because its result is a transient preview, never persisted.

        icon
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
        val currentHash = resource?.drawable?.toSafeBitmapOrNull()?.contentHash()
        if (iconSourceChanged(expectedHash, currentHash)) {
            return ValidatedPackIcon(null, sourceChanged = true)
        }
        resource ?: return ValidatedPackIcon(null, sourceChanged = false)
        val packOptions = options.copy(
            primarySource = Source.ICON_PACK,
            primaryImageEdit = ImageEdit.NONE,
            primaryIconPack = packPackage
        )
        return ValidatedPackIcon(getIcon(application, packOptions, resource), sourceChanged = false)
    }

    /** Applies the modifier from [options] to an already-built icon. */
    suspend fun applyModifier(icon: IconPackDrawable, options: GenerationOptions): IconPackDrawable =
        withContext(Dispatchers.Default) {
            val pack = IconPackContainer("", emptyMap())
            val builder = IconGenerator(context, options, pack, pack)
            builder.applyModifier(icon, options.primaryImageEdit)
        }

    /** Regenerates one app's icon from both packs, handing the result to [onResult]. */
    suspend fun refreshIcon(
        application: PackageInfoStruct,
        sourceOptions: GenerationOptions,
        modifierOptions: GenerationOptions?,
        onResult: (PackageInfoStruct, IconPackDrawable?, IconPackDrawable?, sourcePackName: String) -> Unit
    ) = withContext(Dispatchers.Default) {
        val builder = buildGenerator(sourceOptions)
        val modifier = modifierOptions?.let { modifierBuilder(it) }
        builder.generateIcon(application) { app, base, sourcePack ->
            onResult(app, base, base?.let { modifier?.applyModifier(it, modifierOptions!!.primaryImageEdit) ?: it }, sourcePack)
        }
    }

    /** Regenerates every app's icon from both packs, streaming each result to [onResult]. */
    suspend fun refreshIcons(
        applications: List<PackageInfoStruct>,
        sourceOptions: GenerationOptions,
        modifierOptions: GenerationOptions?,
        onResult: (PackageInfoStruct, IconPackDrawable?, IconPackDrawable?, isFallback: Boolean, sourcePackName: String) -> Unit
    ) = withContext(Dispatchers.Default) {
        val builder = buildGenerator(sourceOptions)
        val modifier = modifierOptions?.let { modifierBuilder(it) }
        builder.generateIcons(applications) { app, base, fallback, sourcePack ->
            onResult(
                app,
                base,
                base?.let { modifier?.applyModifier(it, modifierOptions!!.primaryImageEdit) ?: it },
                fallback,
                sourcePack
            )
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

    private fun buildGenerator(options: GenerationOptions): IconGenerator {
        val pack1 = IconPackContainer(options.primaryIconPack, iconPackRepo.getAppDrawables(options.primaryIconPack))
        val pack2 = IconPackContainer(options.secondaryIconPack, iconPackRepo.getAppDrawables(options.secondaryIconPack))
        // The pack whose fallback styling unthemed apps inherit, per the user's choice.
        val fallbackPack = when (options.fallbackSource) {
            FallbackSource.PRIMARY -> options.primaryIconPack
            FallbackSource.SECONDARY -> options.secondaryIconPack
            FallbackSource.NONE -> ""
        }
        val fallback = iconPackRepo.getIconPackFallback(fallbackPack)
        return IconGenerator(context, options, pack1, pack2, fallback, fallbackPack)
    }

    private fun modifierBuilder(options: GenerationOptions): IconGenerator {
        val emptyPack = IconPackContainer("", emptyMap())
        return IconGenerator(context, options, emptyPack, emptyPack)
    }

    suspend fun getIconPackIcons(
        iconPackName: String,
        options: GenerationOptions,
        drawables: List<ResourceDrawable>
    ): Map<ResourceDrawable, IconPackDrawable?> = withContext(Dispatchers.Default) {
        val exportDrawables = mutableMapOf<ResourceDrawable, IconPackDrawable?>()

        val pack = IconPackContainer("", emptyMap())

        val builder = IconGenerator(context, options, pack, pack)
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
