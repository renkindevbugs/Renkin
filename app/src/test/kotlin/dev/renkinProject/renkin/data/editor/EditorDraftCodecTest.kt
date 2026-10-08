package dev.renkinProject.renkin.data.editor

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class EditorDraftCodecTest {
    private val generation = "12345678-1234-1234-1234-123456789abc"
    private val id = "abcdefab-1234-1234-1234-123456789abc"
    private val owner = EditorDraftOwner(7, "dev.example", "dev.example.Main", "a".repeat(64), "b".repeat(64))
    private val record = EditorDraftRecord(id, Long.MAX_VALUE, owner, listOf(
        EditorDraftArtifact("controls", 2, "c".repeat(64)),
        EditorDraftArtifact("vector-input", 4096, "d".repeat(64))
    ))
    private val catalog get() = EditorDraftCatalog(generation, record)
    private val encoded get() = EditorDraftCodec.encode(catalog)

    private fun rejects(text: String) {
        try {
            EditorDraftCodec.decode(text)
            fail("Invalid metadata was accepted")
        } catch (_: Exception) { }
    }

    private fun rejects(catalog: EditorDraftCatalog) {
        assertThrows(IllegalArgumentException::class.java) { EditorDraftCodec.encode(catalog) }
    }

    @Test fun roundTripsCompleteDraftWithoutLosingLongValuesOrUnicode() {
        val unicode = catalog.copy(draft = record.copy(owner = owner.copy(activityName = "dev.example.Ž活动")))
        assertEquals(unicode, EditorDraftCodec.decode(EditorDraftCodec.encode(unicode)))
    }

    @Test fun roundTripsEmptyCatalogAndPreservesGeneration() {
        val empty = EditorDraftCatalog(generation, null)
        assertEquals(empty, EditorDraftCodec.decode(EditorDraftCodec.encode(empty)))
        assertEquals(EditorDraftCheckpoint(generation, null), empty.checkpoint)
    }

    @Test fun rejectsMissingUnknownDuplicateFieldsAndVersionsAtEveryObjectLevel() {
        val variants = listOf(
            encoded.replace("\"version\":1", "\"version\":2"),
            encoded.replace("\"version\":1,", ""),
            encoded.replace("\"version\":1", "\"version\":1,\"extra\":true"),
            encoded.replace("\"version\":1", "\"version\":1,\"version\":1"),
            encoded.replace("\"savedAt\":", "\"extra\":0,\"savedAt\":"),
            encoded.replace("\"profileId\":7", "\"profileId\":7,\"profileId\":7"),
            encoded.replace("\"bytes\":2", "\"bytes\":2,\"unknown\":0"),
            encoded.replace("\"bytes\":2,", ""),
            encoded.replace("\"name\":\"controls\"", "\"name\":\"controls\",\"name\":\"controls\"")
        )
        variants.forEach(::rejects)
    }

    @Test fun rejectsNullsAndCoercibleButIncorrectScalarTypes() {
        listOf("null", "\"7\"", "7.0", "7e0", "true", "9223372036854775808")
            .forEach { rejects(encoded.replace("\"profileId\":7", "\"profileId\":$it")) }
        rejects(encoded.replace("\"packageName\":\"dev.example\"", "\"packageName\":null"))
        rejects(encoded.replace("\"artifacts\":[", "\"artifacts\":{\"unexpected\":["))
    }

    @Test fun rejectsTrailingContentTruncationAndNonStandardJson() {
        listOf(encoded + " {}", encoded.dropLast(1), encoded.replace("\"version\":1", "\"version\":01"),
            encoded.replace("\"version\":1", "\"version\":/*comment*/1")).forEach(::rejects)
    }

    @Test fun rejectsMalformedUnicodeAndMetadataOverByteLimit() {
        rejects(encoded.replace("dev.example.Main", "dev.example.\\uD800"))
        rejects(encoded + " ".repeat(EditorDraftCodec.MAX_METADATA_BYTES))
        rejects(encoded + "é".repeat(EditorDraftCodec.MAX_METADATA_BYTES / 2))
    }

    @Test fun rejectsInvalidOwnerIdentitiesAndFingerprints() {
        listOf(owner.copy(profileId = 0), owner.copy(packageName = ""), owner.copy(activityName = "x\ny"),
            owner.copy(activityName = "x".repeat(1025)), owner.copy(profileFingerprint = "f".repeat(63)),
            owner.copy(originalIconFingerprint = "F".repeat(64))).forEach {
            rejects(catalog.copy(draft = record.copy(owner = it)))
        }
        rejects(catalog.copy(storageGeneration = "../outside"))
        rejects(catalog.copy(draft = record.copy(id = "../outside")))
        rejects(catalog.copy(draft = record.copy(savedAt = -1)))
    }

    @Test fun rejectsDuplicateMissingAndUnsafeArtifactSlots() {
        val controls = record.artifacts.first()
        listOf(emptyList(), listOf(record.artifacts.last()), listOf(controls, controls),
            listOf(controls, controls.copy(name = "../outside")), listOf(controls.copy(name = "controls/evil")),
            listOf(controls.copy(sha256 = "invalid"))).forEach {
            rejects(catalog.copy(draft = record.copy(artifacts = it)))
        }
    }

    @Test fun enforcesArtifactCountIndividualAndTotalLimitsIncludingBoundaries() {
        val controls = record.artifacts.first()
        val accepted = record.copy(artifacts = listOf(
            controls.copy(bytes = EditorDraftCodec.MAX_CONTROLS_BYTES),
            record.artifacts.last().copy(bytes = EditorDraftCodec.MAX_ARTIFACT_BYTES),
            record.artifacts.last().copy(name = "preview", bytes =
                EditorDraftCodec.MAX_TOTAL_BYTES - EditorDraftCodec.MAX_CONTROLS_BYTES - EditorDraftCodec.MAX_ARTIFACT_BYTES)
        ))
        assertEquals(accepted, EditorDraftCodec.decode(EditorDraftCodec.encode(catalog.copy(draft = accepted))).draft)
        listOf(listOf(controls.copy(bytes = 0)), listOf(controls.copy(bytes = EditorDraftCodec.MAX_CONTROLS_BYTES + 1)),
            listOf(controls, record.artifacts.last().copy(bytes = EditorDraftCodec.MAX_ARTIFACT_BYTES + 1)),
            accepted.artifacts + controls.copy(name = "extra"),
            listOf(controls) + (1..EditorDraftCodec.MAX_ARTIFACTS).map { controls.copy(name = "input-$it") }
        ).forEach { rejects(catalog.copy(draft = record.copy(artifacts = it))) }
    }

    @Test fun onlyRecognizesOwnedFlatBlobNames() {
        assertTrue(EditorDraftCodec.ownedArtifactName("$id-controls.blob"))
        assertFalse(EditorDraftCodec.ownedArtifactName("../$id-controls.blob"))
        assertFalse(EditorDraftCodec.ownedArtifactName("$id-controls.blob.bak"))
        assertFalse(EditorDraftCodec.ownedArtifactName("$id-.blob"))
        assertFalse(EditorDraftCodec.ownedArtifactName("$id-controls/evil.blob"))
    }
}
