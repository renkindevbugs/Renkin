package dev.renkinProject.renkin.feature.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguagesTest {

    @Test
    fun everyTranslationFolderIsSelectableAndEveryLanguageHasStrings() {
        val shippedTags = translationFolders().map { tagForQualifier(it.name.removePrefix("values-")) } + "en"
        assertEquals(shippedTags.toSet(), SupportedAppLanguages.map { it.tag }.toSet())
    }

    @Test
    fun languagesAreUniqueAndFlagged() {
        assertEquals(SupportedAppLanguages.size, SupportedAppLanguages.map { it.tag.lowercase() }.toSet().size)
        assertTrue(SupportedAppLanguages.all { it.flag.isNotBlank() && it.nativeName.isNotBlank() })
    }

    @Test
    fun storedTagsResolveToTheirLanguage() {
        assertNull(supportedAppLanguage(""))
        assertEquals("en", supportedAppLanguage("en")?.tag)
        assertEquals("en", supportedAppLanguage("en-GB")?.tag)
        assertNull(supportedAppLanguage("xx"))
    }

    @Test
    fun englishIsTheSourceAndReportsNoTranslationShare() {
        assertNull(supportedAppLanguage("en")?.translatedPercent)
    }

    private fun tagForQualifier(qualifier: String): String = qualifier.replace("-r", "-")
}
