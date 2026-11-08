package dev.renkinProject.renkin.apk

import com.reandroid.archive.FileInputSource
import java.io.File
import java.io.OutputStream

// ARSCLib's inherited write allocates a 1 MB buffer per entry and leaves the stream open on error.
internal class StagedFileInputSource(stagedFile: File, name: String) : FileInputSource(stagedFile, name) {
    override fun write(outputStream: OutputStream): Long =
        file.inputStream().use { it.copyTo(outputStream) }
}
