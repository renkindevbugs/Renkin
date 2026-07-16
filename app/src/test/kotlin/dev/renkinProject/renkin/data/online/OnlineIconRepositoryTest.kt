package dev.renkinProject.renkin.data.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnlineIconRepositoryTest {

    private val library = OnlineIconLibrary(
        id = "test",
        label = "Test",
        license = "MIT",
        owner = "owner",
        repo = "repo",
        pathPrefix = "icons/outline/",
        projectUrl = "https://github.com/owner/repo"
    )

    @Test
    fun parseIndex_keepsOnlySvgFilesDirectlyUnderThePrefixSorted() {
        val json = """
            {"version":"3.1.0","files":[
                {"name":"/icons/outline/zoom.svg","size":1},
                {"name":"/icons/outline/anchor.svg","size":1},
                {"name":"/icons/outline/nested/skip.svg","size":1},
                {"name":"/icons/filled/anchor.svg","size":1},
                {"name":"/icons/outline/readme.md","size":1},
                {"name":"/package.json","size":1}
            ]}
        """.trimIndent()

        val icons = OnlineIconRepository.parseIndex(json, library)

        assertEquals(listOf("anchor", "zoom"), icons?.map { it.slug })
        assertEquals("3.1.0", icons?.first()?.version)
        assertEquals(
            "https://cdn.jsdelivr.net/gh/owner/repo@3.1.0/icons/outline/anchor.svg",
            icons?.first()?.svgUrl
        )
    }

    @Test
    fun parseIndex_rejectsUnreadableJson() {
        assertNull(OnlineIconRepository.parseIndex("not json", library))
        assertNull(OnlineIconRepository.parseIndex("{\"files\":[]}", library))
    }

    @Test
    fun onlineIcon_labelIsTheSlugWithSpaces() {
        val icon = OnlineIcon(library, "arrow-back-up", "1.0.0")
        assertEquals("arrow back up", icon.label)
    }

    @Test
    fun curatedLibraries_excludeSetsThatShipAnAndroidIconPack() {
        // Arcticons/Lawnicons and friends install as normal packs — the online browser is
        // only for sets with no Android pack. Guard against them sneaking into the list.
        val forbidden = listOf("arcticons", "lawnicons")
        OnlineIconLibraries.forEach { library ->
            forbidden.forEach { name ->
                org.junit.Assert.assertFalse(library.repo.lowercase().contains(name))
            }
        }
    }
}
