package dev.alembiconsProject.alembicons.apk

import android.content.Context
import dev.alembiconsProject.alembicons.data.ImageEdit
import dev.alembiconsProject.alembicons.data.Source
import dev.alembiconsProject.alembicons.drawable.IconPackDrawable
import dev.alembiconsProject.alembicons.drawable.ResourceDrawable
import dev.alembiconsProject.alembicons.icon.creator.GenerationOptions
import dev.alembiconsProject.alembicons.icon.creator.IconGenerator
import dev.alembiconsProject.alembicons.icon.creator.IconPackContainer
import dev.alembiconsProject.alembicons.packages.ApplicationManager
import dev.alembiconsProject.alembicons.packages.PackageInfoStruct
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

        icon
    }

    /** Resolves a pack's drawable by name and builds the icon it provides for [application]. */
    suspend fun getIconFromPackDrawable(
        application: PackageInfoStruct,
        packPackage: String,
        drawableName: String,
        options: GenerationOptions
    ): IconPackDrawable? {
        val ids = appManager.getIconPackDrawableIds(packPackage, listOf(drawableName))
        val resource = appManager.getIconPackDrawables(packPackage, ids).firstOrNull() ?: return null
        val packOptions = options.copy(
            primarySource = Source.ICON_PACK,
            primaryImageEdit = ImageEdit.NONE,
            primaryIconPack = packPackage
        )
        return getIcon(application, packOptions, resource)
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
        options: GenerationOptions,
        onResult: (PackageInfoStruct, IconPackDrawable?) -> Unit
    ) = withContext(Dispatchers.Default) {
        val builder = buildGenerator(options)
        builder.generateIcon(application, onResult)
    }

    /** Regenerates every app's icon from both packs, streaming each result to [onResult]. */
    suspend fun refreshIcons(
        applications: List<PackageInfoStruct>,
        options: GenerationOptions,
        onResult: (PackageInfoStruct, IconPackDrawable?, isFallback: Boolean) -> Unit
    ) = withContext(Dispatchers.Default) {
        val builder = buildGenerator(options)
        builder.generateIcons(applications, onResult)
    }

    private fun buildGenerator(options: GenerationOptions): IconGenerator {
        val pack1 = IconPackContainer(options.primaryIconPack, iconPackRepo.getAppDrawables(options.primaryIconPack))
        val pack2 = IconPackContainer(options.secondaryIconPack, iconPackRepo.getAppDrawables(options.secondaryIconPack))
        val fallback = iconPackRepo.getIconPackFallback(options.primaryIconPack)
        return IconGenerator(context, options, pack1, pack2, fallback)
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
