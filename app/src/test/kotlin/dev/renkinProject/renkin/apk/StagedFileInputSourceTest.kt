package dev.renkinProject.renkin.apk

import com.reandroid.apk.ApkModule
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.random.Random

class StagedFileInputSourceTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun writeCopiesTheWholeFileAndReleasesIt() {
        val bytes = Random(7).nextBytes(3 * 1024 * 1024 + 17)
        val file = folder.newFile("icon.webp").apply { writeBytes(bytes) }
        val out = ByteArrayOutputStream()

        val written = StagedFileInputSource(file, "res/icon.webp").write(out)

        assertEquals(bytes.size.toLong(), written)
        assertArrayEquals(bytes, out.toByteArray())
        assertTrue("The staged file must not stay open after it is written", file.delete())
    }

    @Test
    fun apkStoresTheStagedBytesUncompressedAndTheLastSourceOfAPathWins() {
        val first = folder.newFile("first.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val second = folder.newFile("second.webp").apply { writeBytes(byteArrayOf(4, 5, 6, 7)) }
        val module = ApkModule()
        module.add(StagedFileInputSource(first, "res/icon.webp"))
        module.add(StagedFileInputSource(second, "res/icon.webp"))
        module.uncompressedFiles.addCommonExtensions()
        val apk = folder.root.resolve("out.apk")

        module.writeApk(apk)

        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("res/icon.webp")
            assertEquals(ZipEntry.STORED, entry.method)
            assertArrayEquals(byteArrayOf(4, 5, 6, 7), zip.getInputStream(entry).use { it.readBytes() })
        }
    }
}
