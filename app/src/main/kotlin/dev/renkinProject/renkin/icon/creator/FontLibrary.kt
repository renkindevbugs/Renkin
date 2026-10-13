package dev.renkinProject.renkin.icon.creator

import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The font picker's view of [FontCatalog]: every call that touches font files runs off Main. */
interface FontLibrary {
    /** The bundled default first, then the device's system fonts. */
    suspend fun choices(): List<FontCatalog.FontChoice>
    suspend fun usablePathOrDefault(path: String): String
    suspend fun typeface(path: String): Typeface?
}

object SystemFontLibrary : FontLibrary {
    override suspend fun choices(): List<FontCatalog.FontChoice> =
        withContext(Dispatchers.IO) { listOf(FontCatalog.DEFAULT) + FontCatalog.systemFonts() }

    override suspend fun usablePathOrDefault(path: String): String =
        withContext(Dispatchers.IO) { FontCatalog.usablePathOrDefault(path) }

    override suspend fun typeface(path: String): Typeface? =
        withContext(Dispatchers.IO) { FontCatalog.typeface(path) }
}
