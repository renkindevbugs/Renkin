package dev.renkinProject.renkin.data.editor

import dev.renkinProject.renkin.data.DbApplication
import dev.renkinProject.renkin.data.Profile
import dev.renkinProject.renkin.data.ProfileStyleCodec
import dev.renkinProject.renkin.extension.toLowerHex
import java.io.DataOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Hash detached persisted values outside gates; recheck their owner/revision before use. */
internal object EditorDraftFingerprints {
    private const val VERSION = 1
    private const val MAX_TEXT_BYTES = 64 * 1024

    suspend fun profile(profile: Profile): String = withContext(Dispatchers.Default) {
        require(profile.id > 0) { "Invalid editor profile" }
        val canonicalStyle = ProfileStyleCodec.encode(ProfileStyleCodec.decode(profile.profileStyle))
        digest("profile", currentCoroutineContext()) {
            output.writeLong(profile.id)
            text(canonicalStyle, ProfileStyleCodec.MAX_DOCUMENT_BYTES)
        }
    }

    suspend fun icon(profileId: Long, packageName: String, activityName: String, row: DbApplication?): String =
        withContext(Dispatchers.Default) {
            require(profileId > 0 && packageName.isNotEmpty() && activityName.isNotEmpty()) { "Invalid editor component" }
            require(row == null || (row.profileId == profileId && row.packageName == packageName && row.activityName == activityName)) {
                "Editor baseline belongs to another component"
            }
            digest("icon", currentCoroutineContext()) {
                output.writeLong(profileId); text(packageName); text(activityName)
                output.writeBoolean(row != null)
                row?.let {
                    output.writeBoolean(it.isAdaptiveIcon); output.writeBoolean(it.isXml)
                    text(it.drawable, EditorDraftCodec.MAX_ARTIFACT_BYTES.toInt())
                    output.writeBoolean(it.calendarEnabled); text(it.calendarPrefix); text(it.calendarPackName)
                    text(it.sourcePackName); text(it.sourceDrawableName)
                    output.writeBoolean(it.isCustomIcon); output.writeBoolean(it.isLegacyIcon)
                    text(it.baseDrawable, EditorDraftCodec.MAX_ARTIFACT_BYTES.toInt())
                    output.writeBoolean(it.baseIsAdaptiveIcon); output.writeBoolean(it.baseIsXml)
                    text(it.sourceUrl); output.writeBoolean(it.isFallbackIcon)
                    // Preserve the persisted documents verbatim: normalization must not hide a change.
                    text(it.modifierRecipe, 1024 * 1024); text(it.modifierInput)
                }
            }
        }

    private fun digest(domain: String, context: CoroutineContext, write: HashWriter.() -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }
        val writer = HashWriter(DataOutputStream(DigestOutputStream(sink, digest)), context)
        writer.output.writeInt(0x524B4546) // RKEF
        writer.output.writeInt(VERSION)
        writer.text(domain)
        writer.write()
        context.ensureActive()
        return digest.digest().toLowerHex()
    }

    private class HashWriter(val output: DataOutputStream, private val context: CoroutineContext) {
        fun text(value: String, limit: Int = MAX_TEXT_BYTES) {
            require(value.length <= limit) { "Editor baseline value is too large" }
            var bytes = 0L
            var index = 0
            while (index < value.length) {
                if (index % 8192 < 2) context.ensureActive()
                val char = value[index++]
                bytes += when {
                    char.code < 0x80 -> 1
                    char.code < 0x800 -> 2
                    char.isHighSurrogate() -> {
                        require(index < value.length && value[index].isLowSurrogate()) { "Invalid editor baseline Unicode" }
                        index++
                        4
                    }
                    else -> {
                        require(!char.isLowSurrogate()) { "Invalid editor baseline Unicode" }
                        3
                    }
                }
                require(bytes <= limit) { "Editor baseline value is too large" }
            }
            output.writeInt(bytes.toInt())
            val writer = OutputStreamWriter(output, Charsets.UTF_8)
            var offset = 0
            while (offset < value.length) {
                context.ensureActive()
                val count = minOf(8192, value.length - offset)
                writer.write(value, offset, count)
                offset += count
            }
            writer.flush()
        }
    }
}
