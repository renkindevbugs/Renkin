package dev.renkinProject.renkin.data

import android.util.AtomicFile
import java.io.IOException

private const val VERIFY_BUFFER_BYTES = 64 * 1024

/**
 * Writes [bytes] durably and confirms the published file holds exactly them: AtomicFile logs
 * some rename failures instead of throwing. [beforePublish] runs after the data is synced and
 * before the rename can make it visible. Directory syncs after publication stay with callers.
 */
internal fun AtomicFile.writeVerified(bytes: ByteArray, beforePublish: () -> Unit = {}) {
    val output = startWrite()
    var finished = false
    try {
        output.write(bytes)
        output.fd.sync()
        beforePublish()
        finishWrite(output)
        finished = true
        if (!baseFile.isFile || !holdsExactly(bytes)) throw IOException("Cannot publish ${baseFile.name}")
    } catch (error: Throwable) {
        if (!finished) failWrite(output)
        throw error
    }
}

// Streams the comparison so large files are never read into memory a second time.
private fun AtomicFile.holdsExactly(expected: ByteArray): Boolean = openRead().use { input ->
    val buffer = ByteArray(VERIFY_BUFFER_BYTES)
    var offset = 0
    while (offset < expected.size) {
        val count = input.read(buffer, 0, minOf(buffer.size, expected.size - offset))
        if (count < 0) return false
        for (index in 0 until count) {
            if (buffer[index] != expected[offset + index]) return false
        }
        offset += count
    }
    input.read() == -1
}
