package dev.renkinProject.renkin.feature.language

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Guards contributed translations. Missing strings are fine (Android falls back to English),
 * but a changed format placeholder crashes `getString(id, args)` at runtime, and a key that no
 * longer exists in English fails the release lint.
 */
class TranslationResourcesTest {
    private val english = readStrings(File(RES_DIR, "values/strings.xml"))

    @Test
    fun translationsOnlyContainCurrentTranslatableKeys() {
        for (folder in translationFolders()) {
            val translated = readStrings(File(folder, "strings.xml"))
            val unknown = translated.keys.filter { english[it]?.translatable != true }
            assertTrue("${folder.name} has stale or untranslatable keys: $unknown", unknown.isEmpty())
        }
    }

    @Test
    fun translationsKeepEveryFormatPlaceholder() {
        for (folder in translationFolders()) {
            for ((key, translation) in readStrings(File(folder, "strings.xml"))) {
                val source = english[key] ?: continue
                if (source.isPlural) {
                    val allowed = source.texts.flatMap(::placeholders).toSet()
                    val extra = translation.texts.flatMap(::placeholders).filterNot { it in allowed }
                    assertTrue("${folder.name}/$key adds placeholders $extra", extra.isEmpty())
                } else {
                    assertEquals("${folder.name}/$key", placeholders(source.texts.single()),
                        placeholders(translation.texts.single()))
                }
            }
        }
    }

    @Test
    fun placeholderParsingIgnoresEscapedPercentSigns() {
        assertEquals(listOf("%1\$s", "%2\$d"), placeholders("%1\$s · %2\$d%% translated"))
    }

    private class StringResource(val texts: List<String>, val translatable: Boolean, val isPlural: Boolean)

    private fun readStrings(file: File): Map<String, StringResource> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val entries = mutableMapOf<String, StringResource>()
        for (node in root.childNodes.elements()) {
            val translatable = node.getAttribute("translatable") != "false"
            when (node.tagName) {
                "string" -> entries[node.getAttribute("name")] =
                    StringResource(listOf(node.textContent), translatable, isPlural = false)
                "plurals" -> entries[node.getAttribute("name")] = StringResource(
                    node.getElementsByTagName("item").let { items -> (0 until items.length).map { items.item(it).textContent } },
                    translatable, isPlural = true)
            }
        }
        return entries
    }

    private fun org.w3c.dom.NodeList.elements(): List<Element> =
        (0 until length).map(::item).filterIsInstance<Element>()

    private fun placeholders(text: String): List<String> =
        PLACEHOLDER.findAll(text).map { it.value }.filter { it != "%%" }.sorted().toList()

    private companion object {
        val PLACEHOLDER = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXc%]""")
    }
}

internal const val RES_DIR = "src/main/res"

/** `values-*` folders holding a translation, as `generateLocaleConfig` sees them. */
internal fun translationFolders(): List<File> =
    File(RES_DIR).listFiles().orEmpty()
        .filter { it.name.matches(Regex("values-[a-z]{2,3}(-r[A-Z]{2})?")) && File(it, "strings.xml").isFile }
        .sortedBy { it.name }
