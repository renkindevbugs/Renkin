package dev.renkinProject.renkin.data.editor

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.renkinProject.renkin.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class EditorDraftChangedException : IOException("The editor draft checkpoint has changed")

/**
 * Owns one local draft, outside portable/Android backups and confirmed modifier-input GC.
 * Callers capture immutable bytes before saving and validate domain ownership before restoring.
 * Capture/validate under the profile/storage gate, then save bytes outside it. Storage
 * invalidation can take this mutex while holding the gate; this store never acquires the gate.
 */
@Singleton
internal class EditorDraftStore @Inject constructor(@ApplicationContext context: Context) {
    private val base by lazy { context.noBackupFilesDir.canonicalFile }
    private val root by lazy { File(base, "editor-draft") }
    private val catalogFile by lazy { AtomicFile(File(root, "catalog.json")) }
    private val mutex = Mutex()
    private val acceptanceFence = Any()
    private var acceptanceVersion = 0L
    private val resetOwner = Any()
    private var invalidationPending = false

    /** Initializes and fsyncs a persistent generation on first use; all I/O runs on IO. */
    suspend fun checkpoint(): EditorDraftCheckpoint = access { catalog().checkpoint }

    // Artifact corruption still has a readable checkpoint and uses ordinary checkpoint-aware discard.
    suspend fun inspectCheckpoint(includeOwner: Boolean = false): EditorDraftInspection = access {
        try {
            val catalog = catalog()
            EditorDraftInspection.Readable(catalog.checkpoint, if (includeOwner) catalog.draft?.owner else null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val fingerprint = try {
                metadataFingerprint()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val lease = fingerprint?.let {
                EditorDraftResetLease(resetOwner, synchronized(acceptanceFence) { acceptanceVersion }, it)
            }
            EditorDraftInspection.Unreadable(error, lease)
        }
    }

    /** Requires explicit confirmation; a stale error cannot erase a repaired or newer catalog. */
    suspend fun discardUnreadable(lease: EditorDraftResetLease): EditorDraftCheckpoint? = access {
        if (lease.storeOwner !== resetOwner ||
            synchronized(acceptanceFence) { acceptanceVersion } != lease.writeVersion) return@access null
        if (metadataFingerprint() != lease.metadataFingerprint) return@access null
        try {
            catalog()
            return@access null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // AtomicFile may have promoted a backup while retrying. Require the exact observation again.
            if (metadataFingerprint() != lease.metadataFingerprint) return@access null
        }
        rotateGeneration(null).checkpoint
    }

    internal suspend fun acceptanceLease(expected: EditorDraftCheckpoint): EditorDraftAcceptanceLease? = access {
        if (catalog().checkpoint != expected) return@access null
        val version = synchronized(acceptanceFence) { acceptanceVersion }
        EditorDraftAcceptanceLease { publish ->
            synchronized(acceptanceFence) {
                acceptanceVersion == version && publish()
            }
        }
    }

    /**
     * May initialize the catalog and prune orphan files. Verifies owned files without
     * retaining/decoding artwork; all I/O runs on IO, including first-use initialization.
     */
    suspend fun candidate(): EditorDraftCandidate? = access {
        val catalog = catalog()
        val record = catalog.draft
        if (record == null) {
            prune(null)
            return@access null
        }
        for (artifact in record.artifacts) readArtifact(record.id, artifact, retainBytes = false)
        prune(record.id)
        EditorDraftCandidate(catalog.checkpoint, record)
    }

    /** A stale autosave, discard or pre-import writer cannot replace a newer checkpoint. */
    suspend fun save(
        expected: EditorDraftCheckpoint,
        owner: EditorDraftOwner,
        payloads: Map<String, ByteArray>,
        draftId: String = UUID.randomUUID().toString()
    ): EditorDraftCandidate = access {
        val current = catalog()
        requireCheckpoint(current, expected)
        require(draftId != current.draft?.id) { "Editor draft publication requires a new identity" }
        val record = EditorDraftRecord(
            draftId, System.currentTimeMillis(), owner,
            payloads.map { (name, bytes) -> EditorDraftArtifact(name, bytes.size.toLong(), "0".repeat(64)) }
        )
        EditorDraftCodec.validate(EditorDraftCatalog(current.storageGeneration, record))
        expireAcceptanceLeases()
        prune(current.draft?.id)
        val artifacts = record.artifacts.map { artifact ->
            val bytes = payloads.getValue(artifact.name)
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(artifactFile(record.id, artifact.name)).use { output ->
                var offset = 0
                while (offset < bytes.size) {
                    currentCoroutineContext().ensureActive()
                    val count = minOf(DEFAULT_BUFFER_SIZE, bytes.size - offset)
                    output.write(bytes, offset, count)
                    digest.update(bytes, offset, count)
                    offset += count
                }
                output.fd.sync()
            }
            artifact.copy(sha256 = digest.hex())
        }
        val completed = record.copy(artifacts = artifacts)
        val saved = EditorDraftCatalog(current.storageGeneration, completed)
        currentCoroutineContext().ensureActive()
        // Once publication starts, cancellation must not interrupt fsync/rename verification.
        commit(saved)
        prune(completed.id)
        EditorDraftCandidate(saved.checkpoint, completed)
    }

    /** Rechecks the checkpoint and hash after the user has accepted a recovery offer. */
    suspend fun artifact(expected: EditorDraftCheckpoint, name: String): ByteArray = access {
        val current = catalog()
        requireCheckpoint(current, expected)
        val record = current.draft ?: throw EditorDraftChangedException()
        val artifact = record.artifacts.singleOrNull { it.name == name }
            ?: throw IOException("Editor draft artifact is missing")
        requireNotNull(readArtifact(record.id, artifact, retainBytes = true))
    }

    suspend fun discard(expected: EditorDraftCheckpoint): Boolean = access {
        val current = catalog()
        if (current.checkpoint != expected) return@access false
        // Also invalidate writers that captured the earlier empty checkpoint (null -> id -> null).
        rotateGeneration(null)
        true
    }

    /** Expire queued writers even if the deleted profile has not published a draft yet. */
    suspend fun invalidateProfile(profileId: Long) = access {
        val retained = try {
            catalog().draft?.takeUnless { it.owner.profileId == profileId }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            Log.error("EditorDraftStore", "Unreadable draft catalog discarded before profile deletion", error)
            null
        }
        rotateGenerationOrRemoveCache(retained, "Draft invalidation failed before profile deletion; removing draft cache")
        Unit
    }

    /**
     * Must precede elective storage replacement once capture is integrated. Rotating the
     * persistent generation also rejects autosaves already queued before the replacement.
     * It can explicitly abandon corrupt metadata without attempting to decode it.
     */
    suspend fun invalidateStorage() = access {
        rotateGeneration(null)
        Unit
    }

    /** Auxiliary draft storage must never prevent rollback or the user's recovery escape path. */
    suspend fun invalidateForRecovery() = access {
        rotateGenerationOrRemoveCache(null, "Draft invalidation failed during storage recovery; removing draft cache")
    }

    private suspend fun rotateGenerationOrRemoveCache(draft: EditorDraftRecord?, failureMessage: String) {
        invalidationPending = true
        try {
            rotateGeneration(draft)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error("EditorDraftStore", failureMessage, error)
            try {
                removeDraftCache()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cleanupError: Exception) {
                Log.error("EditorDraftStore", "Draft cache could not be removed; draft access remains blocked", cleanupError)
            }
        }
    }

    private suspend fun rotateGeneration(draft: EditorDraftRecord?): EditorDraftCatalog {
        expireAcceptanceLeases()
        ensureRoot()
        val replacement = EditorDraftCatalog(UUID.randomUUID().toString(), draft)
        commit(replacement)
        prune(draft?.id)
        invalidationPending = false
        return replacement
    }

    private suspend fun <T> access(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    // Expire before writes begin, including admitted writes that later fail or are cancelled.
    private fun expireAcceptanceLeases() = synchronized(acceptanceFence) { acceptanceVersion++ }

    private suspend fun catalog(): EditorDraftCatalog {
        // Failed invalidation cannot admit old checkpoints, even if cleanup also failed.
        if (invalidationPending) rotateGeneration(null)
        ensureRoot()
        val paths = metadataPaths().onEach(::requireOwnedFile)
        if (!paths[0].exists() && !paths[1].exists()) {
            val initial = EditorDraftCatalog(UUID.randomUUID().toString(), null)
            commit(initial)
            return initial
        }
        val encoded = catalogFile.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > EditorDraftCodec.MAX_METADATA_BYTES) {
                    throw IOException("Editor draft metadata is too large")
                }
                output.write(buffer, 0, count)
            }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString()
        }
        return try {
            EditorDraftCodec.decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid editor draft metadata", error)
        } catch (error: IllegalStateException) {
            throw IOException("Invalid editor draft metadata", error)
        }
    }

    private suspend fun commit(catalog: EditorDraftCatalog) = withContext(NonCancellable) {
        metadataPaths().forEach(::requireOwnedFile)
        val encoded = EditorDraftCodec.encode(catalog).toByteArray(Charsets.UTF_8)
        val output = catalogFile.startWrite()
        var finished = false
        try {
            output.write(encoded)
            output.fd.sync()
            // Blob directory entries must be durable before a catalog can reference them.
            syncDirectory(root)
            catalogFile.finishWrite(output)
            finished = true
            val actual = catalogFile.openRead().use { input ->
                val bytes = ByteArray(encoded.size)
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    if (count < 0) break
                    offset += count
                }
                offset == bytes.size && input.read() == -1 && bytes.contentEquals(encoded)
            }
            if (!actual) throw IOException("Cannot publish editor draft metadata")
            syncDirectory(root)
            syncDirectory(base)
        } catch (error: Throwable) {
            if (!finished) catalogFile.failWrite(output)
            // Leave new blobs intact: even a final fsync error can follow a successful rename.
            throw error
        }
    }

    private suspend fun readArtifact(id: String, artifact: EditorDraftArtifact, retainBytes: Boolean): ByteArray? {
        val file = artifactFile(id, artifact.name)
        if (!file.isFile || file.length() != artifact.bytes) throw IOException("Editor draft artifact size changed")
        val digest = MessageDigest.getInstance("SHA-256")
        val retained = if (retainBytes) ByteArray(artifact.bytes.toInt()) else null
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        file.inputStream().use { input ->
            var offset = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (offset.toLong() + count > artifact.bytes) throw IOException("Editor draft artifact grew")
                digest.update(buffer, 0, count)
                retained?.let { buffer.copyInto(it, offset, 0, count) }
                offset += count
            }
            if (offset.toLong() != artifact.bytes || digest.hex() != artifact.sha256) {
                throw IOException("Editor draft artifact checksum changed")
            }
        }
        return retained
    }

    private fun artifactFile(id: String, name: String): File = File(root, "$id-$name.blob").also(::requireOwnedFile)

    private fun metadataPaths(): List<File> =
        listOf(catalogFile.baseFile, File(root, "catalog.json.bak"), File(root, "catalog.json.new"))

    private suspend fun metadataFingerprint(): String {
        ensureRoot()
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        for (file in metadataPaths()) {
            currentCoroutineContext().ensureActive()
            requireOwnedFile(file)
            digest.update(file.name.toByteArray(Charsets.UTF_8))
            digest.update(if (file.exists()) 1.toByte() else 0.toByte())
            if (!file.exists()) continue
            if (!file.isFile) throw IOException("Editor draft metadata is not a regular file")
            val length = file.length()
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(length).array())
            var read = 0L
            file.inputStream().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    read += count
                    if (read > length) throw IOException("Editor draft metadata grew during inspection")
                    digest.update(buffer, 0, count)
                }
            }
            if (read != length || file.length() != length) throw IOException("Editor draft metadata changed during inspection")
        }
        return digest.hex()
    }

    private fun requireCheckpoint(catalog: EditorDraftCatalog, expected: EditorDraftCheckpoint) {
        if (catalog.checkpoint != expected) throw EditorDraftChangedException()
    }

    private fun ensureRoot() {
        if (root.canonicalFile != root.absoluteFile) throw IOException("Invalid editor draft directory")
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create editor draft directory")
    }

    private fun requireOwnedFile(file: File) {
        if (file.canonicalFile != file.absoluteFile || file.parentFile != root) {
            throw IOException("Invalid editor draft file path")
        }
    }

    private suspend fun removeDraftCache() {
        // Only the fixed private namespace may be removed. Never traverse a symlink's target.
        if (base.canonicalFile != base.absoluteFile || root.absoluteFile.parentFile != base || root.name != "editor-draft") {
            throw IOException("Invalid editor draft cache location")
        }
        removeDraftEntry(root)
        syncDirectory(base)
    }

    private suspend fun removeDraftEntry(file: File, depth: Int = 0) {
        currentCoroutineContext().ensureActive()
        if (depth > 64 || (file != root && !file.absolutePath.startsWith(root.absolutePath + File.separator))) {
            throw IOException("Invalid editor draft cache entry")
        }
        if (file.canonicalFile == file.absoluteFile && file.isDirectory) {
            val children = file.listFiles() ?: throw IOException("Cannot inspect editor draft cache")
            for (child in children) removeDraftEntry(child, depth + 1)
        }
        if (!file.delete() && file.exists()) throw IOException("Cannot remove editor draft cache entry")
    }

    private suspend fun prune(keep: String?) {
        for (file in root.listFiles().orEmpty()) {
            currentCoroutineContext().ensureActive()
            if (!EditorDraftCodec.ownedArtifactName(file.name) || file.name.take(36) == keep) continue
            try {
                if (file.canonicalFile != file.absoluteFile || !file.isFile || !file.delete()) {
                    Log.info("EditorDraftStore", "Could not remove an unused draft artifact")
                }
            } catch (error: IOException) {
                Log.error("EditorDraftStore", "Could not inspect an unused draft artifact", error)
            }
        }
    }

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    private fun MessageDigest.hex(): String {
        val alphabet = "0123456789abcdef"
        return buildString {
            digest().forEach { byte ->
                val value = byte.toInt() and 0xff
                append(alphabet[value ushr 4])
                append(alphabet[value and 0x0f])
            }
        }
    }
}
