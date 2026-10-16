package dev.renkinProject.renkin.data.transfer

import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.IconModifierInputStore
import dev.renkinProject.renkin.icon.creator.StoredIconModifierInput
import dev.renkinProject.renkin.util.Log
import java.io.File

internal class BackupModifierInputPreparation(private val available: Map<String, File>) {
    private val verified = mutableMapOf<String, Boolean>()
    val files = mutableMapOf<String, File>()

    suspend fun prepare(icon: DbApplication): DbApplication {
        val artwork = StoredIconModifierInput.artwork(icon.modifierInput)
        val name = artwork?.let { "${it.hash}.input" }
        val source = name?.let(available::get)
        val valid = if (artwork == null || name == null || source == null) false else {
            verified[name] ?: IconModifierInputStore.validFile(source, artwork.hash).also { verified[name] = it }
        }
        return if (valid) {
            files[requireNotNull(name)] = requireNotNull(source)
            icon
        } else {
            if (icon.modifierInput.isNotEmpty()) Log.info("BackupManager", "Discarded missing or invalid optional modifier input")
            icon.copy(modifierInput = "")
        }
    }
}
