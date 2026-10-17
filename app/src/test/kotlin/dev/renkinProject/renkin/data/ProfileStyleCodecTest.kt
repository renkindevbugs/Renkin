package dev.renkinProject.renkin.data

import android.app.Application
import dev.renkinProject.renkin.icon.creator.ApplicationIconVariant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ProfileStyleCodecTest {
    private fun document(source: String = "{}", modifiers: String = "{}", export: String = "{}", policy: String = "{}", built: String = "{}") =
        """{"version":1,"source":$source,"modifiers":$modifiers,"export":$export,"policy":$policy,"builtPrimary":$built}"""

    private fun rejected(encoded: String) {
        assertThrows(encoded.take(180), IllegalArgumentException::class.java) { ProfileStyleCodec.decode(encoded) }
    }

    private fun fontStyle(text: String) = ProfileStyle(source = ProfileSourceStyle(fontPath = text))

    @Test
    fun emptyModelRequiresVersionAndAllRootGroupsWithoutMaterializingDefaults() {
        val encoded = JSONObject(ProfileStyleCodec.encode(ProfileStyle()))
        assertEquals(ProfileStyleSchema.groups + "version", encoded.keys().asSequence().toSet())
        assertEquals(1, encoded.getInt("version"))
        ProfileStyleSchema.groups.forEach { assertEquals(0, encoded.getJSONObject(it).length()) }
        assertEquals(ProfileStyle(), ProfileStyleCodec.decode(document()))
    }

    @Test
    fun rejectsIncompleteOrUnsupportedDocumentsAndNonStrictJson() {
        val valid = document()
        listOf("", "[]", "null", "{}", valid.replace("\"version\":1,", ""),
            valid.replace("\"source\":{},", ""), valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"version\":1", "\"version\":\"1\""), valid.replace("\"version\":1", "\"version\":1.0"),
            valid + "{}", valid + " true", "/*comment*/" + valid, valid.replace('"', '\''),
            valid.replace("\"version\":1", "version:1"), valid.replace("\"source\":{}", "\"source\":[]"),
            valid.replace("\"source\":{}", "\"source\":null")
        ).forEach(::rejected)
    }

    @Test
    fun rejectsUnknownDuplicateAndDottedNamesAtEveryDepth() {
        listOf(
            document().replace("\"version\":1", "\"version\":1,\"version\":1"),
            document().replace("\"version\":1", "\"version\":1,\"future\":{}"),
            document("""{"primary":{},"primary":{}}"""),
            document("""{"primary":{"iconPack":"a","iconPack":"b"}}"""),
            document("""{"primary":{"unknown":1}}"""),
            document("""{"primary.kind":"NONE"}"""),
            document().replace("\"source\":{}", "\"source.primary\":{}"),
            document(modifiers = """{"global":{"outline":{"color":{"bogus":0}}}}""")
        ).forEach(::rejected)
    }

    @Test
    fun rejectsWrongScalarTypesNullFractionsExponentAndIntegerOverflow() {
        listOf("null", "0", "\"false\"", "{}", "[]").forEach { rejected(document(policy = """{"overrideExisting":$it}""")) }
        listOf("null", "false", "0", "{}", "[]").forEach { rejected(document("""{"fontPath":$it}""")) }
        listOf("null", "false", "\"1\"", "1.5", "1e0", "2147483648", "-2147483649", "[]", "{}").forEach {
            rejected(document(modifiers = """{"global":{"iconScalePercent":$it}}"""))
        }
        for (value in listOf(Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals(value, ProfileStyleCodec.decode(document(modifiers = """{"global":{"iconScalePercent":$value}}""")).modifiers.global.iconScalePercent)
        }
    }

    @Test
    fun enumWireFormIsNamedOrAnUnknownLegacyOrdinalWithoutAliases() {
        val named = ProfileStyle(source = ProfileSourceStyle(primary = ProfileSourceSelection(source = ProfileEnumValue.Known(Source.APPLICATION_ICON))))
        assertEquals("APPLICATION_ICON", JSONObject(ProfileStyleCodec.encode(named)).getJSONObject("source").getJSONObject("primary").getString("kind"))
        for (value in listOf("0", "null", "\"future\"", "{}", "[]", "{\"legacyOrdinal\":0}",
            "{\"legacyOrdinal\":-1,\"legacyOrdinal\":-1}", "{\"legacyOrdinal\":-1,\"extra\":1}",
            "{\"extra\":-1}", "{\"legacyOrdinal\":-1.0}", "{\"legacyOrdinal\":\"-1\"}")) {
            rejected(document("""{"primary":{"kind":$value}}"""))
        }
        val alias = ProfileStyle(source = ProfileSourceStyle(primary = ProfileSourceSelection(source = ProfileEnumValue.LegacyOrdinal<Source>(0))))
        assertThrows(IllegalArgumentException::class.java) { ProfileStyleCodec.encode(alias) }
        assertThrows(IllegalArgumentException::class.java) { ProfileStylePreferences.toPreferences(alias) }
    }

    @Test
    fun asciiDocumentAtExactSizeLimitRoundTripsAndOneExtraByteFails() {
        val overhead = ProfileStyleCodec.encode(fontStyle("")).length
        val style = fontStyle("a".repeat(ProfileStyleCodec.MAX_DOCUMENT_BYTES - overhead))
        val encoded = ProfileStyleCodec.encode(style)
        assertEquals(ProfileStyleCodec.MAX_DOCUMENT_CHARS, encoded.length)
        assertEquals(style, ProfileStyleCodec.decode(encoded))
        rejected(encoded + " ")
        assertThrows(IllegalArgumentException::class.java) { ProfileStyleCodec.encode(fontStyle(style.source.fontPath!! + "a")) }
    }

    @Test
    fun utf8ByteLimitIsAppliedEvenBelowTheCharacterLimit() {
        val overhead = ProfileStyleCodec.encode(fontStyle("")).length
        val available = ProfileStyleCodec.MAX_DOCUMENT_BYTES - overhead
        val text = "\u00E9".repeat(available / 2) + "a".repeat(available % 2)
        val style = fontStyle(text)
        val encoded = ProfileStyleCodec.encode(style)
        assertTrue(encoded.length < ProfileStyleCodec.MAX_DOCUMENT_CHARS)
        assertEquals(ProfileStyleCodec.MAX_DOCUMENT_BYTES, encoded.toByteArray(Charsets.UTF_8).size)
        assertEquals(style, ProfileStyleCodec.decode(encoded))
        rejected(document("""{"fontPath":"${text}a"}"""))
        assertThrows(IllegalArgumentException::class.java) { ProfileStyleCodec.encode(fontStyle(text + "a")) }
    }

    @Test
    fun encoderBoundsCombinedStringsAndJsonEscapingGrowth() {
        val half = "a".repeat(ProfileStyleCodec.MAX_DOCUMENT_CHARS / 2 + 1)
        val style = ProfileStyle(source = ProfileSourceStyle(fontPath = half, primary = ProfileSourceSelection(iconPack = half)))
        assertThrows(IllegalArgumentException::class.java) { ProfileStyleCodec.encode(style) }
        assertThrows(IllegalArgumentException::class.java) { ProfileStyleCodec.encode(fontStyle("\n".repeat(ProfileStyleCodec.MAX_DOCUMENT_CHARS / 2))) }
    }

    @Test
    fun unicodePairsRoundTripButUnpairedRawAndEscapedSurrogatesFail() {
        val style = fontStyle("font \uD83C\uDF08 \u00E9")
        assertEquals(style, ProfileStyleCodec.decode(ProfileStyleCodec.encode(style)))
        for (bad in listOf("\uD800", "\uDC00", "a\uD800z")) {
            assertThrows(IllegalArgumentException::class.java) { ProfileStyleCodec.encode(fontStyle(bad)) }
            rejected(document("""{"fontPath":"$bad"}"""))
        }
        rejected(document("""{"fontPath":"\uD800"}"""))
        rejected(document("""{"fontPath":"\uDC00"}"""))
    }

    @Test
    fun foreignDeepTreesAreRejectedAtTheFirstUnknownPath() {
        rejected(document("""{"primary":{"unknown":${"{\"x\":".repeat(2000)}0${"}".repeat(2000)}}}"""))
    }

    @Test
    fun appIconStyleTravelsUnderTheSourceGroup() {
        val style = ProfileStyle(
            source = ProfileSourceStyle(
                applicationIcon = ProfileApplicationIconStyle(
                    variant = ProfileEnumValue.Known(ApplicationIconVariant.MATERIAL_YOU),
                    fullIcon = true,
                    invertMonochrome = false,
                    materialYouScheme = 5,
                    materialYouForeground = ProfileColorStyle(firstColor = "#FFFF0000"),
                    materialYouBackground = ProfileColorStyle(firstColor = "#FF0000FF")
                )
            )
        )

        val encoded = ProfileStyleCodec.encode(style)
        val appIcon = JSONObject(encoded).getJSONObject("source").getJSONObject("applicationIcon")

        assertEquals("MATERIAL_YOU", appIcon.getString("variant"))
        assertEquals(5, appIcon.getInt("materialYouScheme"))
        assertEquals("#FFFF0000", appIcon.getJSONObject("materialYouForeground").getString("firstColor"))
        assertEquals(style, ProfileStyleCodec.decode(encoded))
    }

    @Test
    fun documentsWithoutAppIconStyleStillDecode() {
        assertEquals(ProfileApplicationIconStyle(), ProfileStyleCodec.decode(document()).source.applicationIcon)
    }
}
