package dev.renkinProject.renkin.data.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnlineIconRepositoryTest {

    @Test
    fun parseCollections_readsMetadataAndSortsByName() {
        val json = """
            {
              "mdi": {"name":"Material Design Icons","total":7447,
                      "license":{"title":"Apache 2.0","spdx":"Apache-2.0"},
                      "samples":["account","home","alert"],
                      "category":"General","palette":false},
              "twemoji": {"name":"Twitter Emoji","total":3000,
                          "license":{"title":"CC BY 4.0"},
                          "samples":["1f600"],"category":"Emoji","palette":true},
              "broken": {"total":0}
            }
        """.trimIndent()

        val sets = OnlineIconRepository.parseCollections(json)!!

        assertEquals(listOf("mdi", "twemoji"), sets.map { it.prefix })
        assertEquals("Apache-2.0", sets[0].license)
        assertEquals("CC BY 4.0", sets[1].license)
        assertTrue(sets[1].palette)
        assertFalse(sets[0].palette)
        assertEquals(listOf("account", "home", "alert"), sets[0].samples)
        assertEquals("Emoji", sets[1].category)
    }

    @Test
    fun parseCollections_rejectsUnreadableJson() {
        assertNull(OnlineIconRepository.parseCollections("not json"))
        assertNull(OnlineIconRepository.parseCollections("{}"))
    }

    @Test
    fun parseCollection_mergesUncategorizedAndCategoriesDeduplicated() {
        val json = """
            {"prefix":"mdi",
             "uncategorized":["zebra","account"],
             "categories":{"Home":["home","account"],"Alert":["alert"]}}
        """.trimIndent()

        val icons = OnlineIconRepository.parseCollection(json)!!

        assertEquals(listOf("account", "alert", "home", "zebra"), icons.map { it.name })
        assertEquals("mdi", icons.first().prefix)
        assertEquals("https://api.iconify.design/mdi/account.svg", icons.first().svgUrl)
    }

    @Test
    fun parseCollection_rejectsMissingPrefixOrEmptySets() {
        assertNull(OnlineIconRepository.parseCollection("""{"uncategorized":["a"]}"""))
        assertNull(OnlineIconRepository.parseCollection("""{"prefix":"mdi"}"""))
    }

    @Test
    fun onlineAttributionLabel_handlesIconifyAndLegacyGitHubUrls() {
        assertEquals(
            "simple-icons",
            onlineAttributionLabel("https://api.iconify.design/simple-icons/firefox.svg")
        )
        // Icons stored by the earlier GitHub-CDN build keep their attribution.
        assertEquals(
            "someone/some-icons",
            onlineAttributionLabel("https://cdn.jsdelivr.net/gh/someone/some-icons@1.2/svg/a.svg")
        )
        assertNull(onlineAttributionLabel("https://example.com/not-a-known-url.svg"))
    }

    @Test
    fun onlineIcon_labelIsTheNameWithSpaces() {
        assertEquals("arrow back up", OnlineIcon("tabler", "arrow-back-up").label)
    }

    @Test
    fun parseSearch_readsPrefixedNamesAndAllowsEmptyResults() {
        val icons = OnlineIconRepository.parseSearch(
            """{"icons":["mdi:home","tabler:anchor","broken","also:",":nope"],"total":2}"""
        )!!

        assertEquals(listOf("mdi" to "home", "tabler" to "anchor"), icons.map { it.prefix to it.name })
        assertEquals(emptyList<OnlineIcon>(), OnlineIconRepository.parseSearch("""{"icons":[]}"""))
        assertNull(OnlineIconRepository.parseSearch("""{"total":0}"""))
        assertNull(OnlineIconRepository.parseSearch("not json"))
    }

    @Test
    fun svgCacheValidationRejectsPartialOrNonSvgMarkup() {
        assertTrue(OnlineIconRepository.isWellFormedSvg("<svg viewBox=\"0 0 1 1\"><path d=\"M0 0\"/></svg>"))
        assertFalse(OnlineIconRepository.isWellFormedSvg("<svg><path>"))
        assertFalse(OnlineIconRepository.isWellFormedSvg("<html></html>"))
    }
}
