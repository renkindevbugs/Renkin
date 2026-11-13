package dev.renkinProject.renkin.icon.creator

import android.content.Context
import android.content.pm.PackageManager
import android.util.AtomicFile
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.os.ConfigurationCompat
import dev.renkinProject.renkin.data.IconPack
import dev.renkinProject.renkin.data.writeVerified
import dev.renkinProject.renkin.extension.toLowerHex
import dev.renkinProject.renkin.packages.PackageResourceResolver
import dev.renkinProject.renkin.packages.PackBrowserDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

internal class PackBrowserIndex(context: Context, private val appMan: PackBrowserDataSource) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Any()
    private val parsePermits = Semaphore(2)
    private val prewarmPermits = Semaphore(2)
    private val entries = mutableMapOf<String, Deferred<List<String>>>()
    private val directory = File(this.context.cacheDir, "pack-browser-index-v1")
    private var epoch = 0L
    private var closed = false
    private var diskReset: Deferred<Boolean>? = null
    private var prewarmJob: Job? = null

    suspend fun identity(pack: IconPack): String = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        currentIdentity(pack)
    }

    internal fun configurationSignature(includePalette: Boolean = true): String {
        val configuration = context.resources.configuration
        val night = PackageResourceResolver.isNightDisplayed(configuration)
        val palette = if (includePalette && android.os.Build.VERSION.SDK_INT >= 31) {
            listOf(android.R.color.system_accent1_500, android.R.color.system_accent2_500,
                android.R.color.system_accent3_500, android.R.color.system_neutral1_500,
                android.R.color.system_neutral2_500).joinToString(",") { context.getColor(it).toString() }
        } else ""
        // Only what selects a pack resource: Configuration.toString() also carries a sequence
        // number and window bounds that change on every rotation and would drop every cache.
        val locales = ConfigurationCompat.getLocales(configuration).toLanguageTags()
        return "$locales|$night|${configuration.densityDpi}|$palette"
    }

    private fun currentIdentity(pack: IconPack, includePalette: Boolean = true): String {
        val revision = try {
            val info = context.packageManager.getPackageInfo(pack.packageName, 0)
            "${PackageInfoCompat.getLongVersionCode(info)}|${info.lastUpdateTime}|${info.applicationInfo?.sourceDir}"
        } catch (_: PackageManager.NameNotFoundException) {
            "missing|${pack.versionCode}"
        }
        return "$FORMAT|${pack.packageName}|$revision|${configurationSignature(includePalette)}|${pack.changesWithMaterialYouColors}"
    }

    private suspend fun metadataIdentity(pack: IconPack) =
        withContext(Dispatchers.IO) { currentIdentity(pack, includePalette = false) }

    suspend fun names(pack: IconPack): List<String> {
        val requestedEpoch = synchronized(stateLock) {
            checkCurrent(epoch)
            epoch
        }
        val key = metadataIdentity(pack)
        val request = synchronized(stateLock) {
            checkCurrent(requestedEpoch)
            val requestedReset = diskReset
            entries[key] ?: scope.async(start = CoroutineStart.LAZY) {
                load(pack, key, requestedEpoch, requestedReset)
            }.also { deferred ->
                entries[key] = deferred
                deferred.invokeOnCompletion { error ->
                    if (error != null) synchronized(stateLock) {
                        if (entries[key] === deferred) entries.remove(key)
                    }
                }
            }
        }
        request.start()
        val result = request.await()
        verifyCurrent(pack, key, requestedEpoch)
        synchronized(stateLock) {
            val prefix = "$FORMAT|${pack.packageName}|"
            entries.entries.removeAll { it.key.startsWith(prefix) && it.key != key && it.value.isCompleted }
        }
        return result
    }

    fun prewarm(packs: List<IconPack>) {
        synchronized(stateLock) {
            if (closed) return
            prewarmJob?.cancel()
            prewarmJob = scope.launch {
                coroutineScope {
                    packs.forEach { pack ->
                        launch {
                            prewarmPermits.withPermit {
                                try {
                                    names(pack)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    // Interactive requests may retry a pack that failed to parse.
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun clear() {
        synchronized(stateLock) {
            if (closed) return
            epoch++
            prewarmJob?.cancel()
            val old = entries.values.toList()
            entries.clear()
            old.forEach { it.cancel() }
            val previousReset = diskReset
            diskReset = scope.async(Dispatchers.IO) {
                previousReset?.await()
                diskLock.withLock {
                    try {
                        val files = directory.listFiles()
                        if (files == null) !directory.exists() else files.all { file ->
                            file.isFile && (file.delete() || !file.exists())
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        false
                    }
                }
            }
        }
    }

    fun close() {
        synchronized(stateLock) {
            closed = true
            epoch++
            entries.clear()
        }
        scope.cancel()
    }

    private suspend fun load(
        pack: IconPack,
        key: String,
        requestedEpoch: Long,
        reset: Deferred<Boolean>?
    ): List<String> {
        val diskAllowed = reset?.await() != false
        verifyCurrent(pack, key, requestedEpoch)
        val cached = if (diskAllowed) readDisk(key, requestedEpoch) else null
        val result = cached ?: parsePermits.withPermit {
            coroutineContext.ensureActive()
            val names = appMan.getIconPackDrawableNames(pack.packageName)
            coroutineContext.ensureActive()
            names.distinct().sortedWith(NaturalNameOrder)
        }
        verifyCurrent(pack, key, requestedEpoch)
        if (cached == null) scope.launch {
            try {
                verifyCurrent(pack, key, requestedEpoch)
                writeDisk(pack, key, result, requestedEpoch)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A cache-only task must not fail the interactive request or its owner.
            }
        }
        return result
    }

    private suspend fun verifyCurrent(pack: IconPack, key: String, requestedEpoch: Long) {
        coroutineContext.ensureActive()
        synchronized(stateLock) { checkCurrent(requestedEpoch) }
        if (metadataIdentity(pack) != key) throw CancellationException("Icon pack resources changed")
        synchronized(stateLock) { checkCurrent(requestedEpoch) }
    }

    private fun checkCurrent(requestedEpoch: Long) {
        if (closed || requestedEpoch != epoch) throw CancellationException("Icon pack index invalidated")
    }

    private fun file(key: String): AtomicFile {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).toLowerHex()
        return AtomicFile(File(directory, "$digest.bin"))
    }

    private suspend fun readDisk(key: String, requestedEpoch: Long): List<String>? =
        withContext(Dispatchers.IO) {
            diskLock.withLock {
                synchronized(stateLock) { checkCurrent(requestedEpoch) }
                try {
                    file(key).openRead().use { stream ->
                        if (stream.channel.size() !in 1..MAX_FILE_BYTES.toLong()) return@withLock null
                        DataInputStream(stream).use { input ->
                            if (input.readInt() != MAGIC || input.readInt() != FORMAT) return@withLock null
                            if (input.readBoundedString(MAX_IDENTITY_BYTES) != key) return@withLock null
                            val count = input.readInt()
                            if (count !in 0..MAX_NAMES) return@withLock null
                            val names = ArrayList<String>(count)
                            repeat(count) {
                                coroutineContext.ensureActive()
                                val name = input.readBoundedString(MAX_NAME_BYTES)
                                if (names.isNotEmpty() && NaturalNameOrder.compare(names.last(), name) >= 0) {
                                    throw IOException("Unsorted icon pack index")
                                }
                                names.add(name)
                            }
                            if (input.read() != -1) return@withLock null
                            names
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            }
        }

    private suspend fun writeDisk(pack: IconPack, key: String, names: List<String>, requestedEpoch: Long) =
        withContext(Dispatchers.IO) {
            diskLock.withLock {
                synchronized(stateLock) { checkCurrent(requestedEpoch) }
                try {
                    if (names.size > MAX_NAMES) return@withLock
                    val bytes = ByteArrayOutputStream()
                    DataOutputStream(bytes).use { output ->
                        output.writeInt(MAGIC)
                        output.writeInt(FORMAT)
                        output.writeBoundedString(key, MAX_IDENTITY_BYTES)
                        output.writeInt(names.size)
                        names.forEach { name ->
                            coroutineContext.ensureActive()
                            output.writeBoundedString(name, MAX_NAME_BYTES)
                            if (bytes.size() > MAX_FILE_BYTES) throw IOException("Icon pack index too large")
                        }
                    }
                    if (!directory.isDirectory && !directory.mkdirs()) return@withLock
                    val requestContext = coroutineContext
                    file(key).writeVerified(bytes.toByteArray()) {
                        requestContext.ensureActive()
                        synchronized(stateLock) { checkCurrent(requestedEpoch) }
                        if (currentIdentity(pack, includePalette = false) != key) throw CancellationException("Icon pack resources changed")
                    }
                    trimDisk(file(key).baseFile)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Metadata persistence is optional; a usable RAM index is still returned.
                }
            }
        }

    private fun DataInputStream.readBoundedString(max: Int): String {
        val size = readInt()
        if (size !in 0..max) throw IOException("Invalid icon pack index string length")
        val bytes = ByteArray(size)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun trimDisk(current: File) {
        val files = directory.listFiles()?.filter { it.isFile && it.extension == "bin" }
            ?.sortedBy { it.lastModified() }.orEmpty()
        var bytes = files.sumOf { it.length() }
        for (file in files) {
            if (bytes <= MAX_CACHE_BYTES) break
            if (file != current) {
                val size = file.length()
                if (file.delete()) bytes -= size
            }
        }
    }

    private fun DataOutputStream.writeBoundedString(value: String, max: Int) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > max) throw IOException("Icon pack index string too large")
        writeInt(bytes.size)
        write(bytes)
    }

    private companion object {
        // AtomicFile reads can restore backups, so even separate editor owners serialize disk access.
        val diskLock = Mutex()
        const val FORMAT = 1
        const val MAGIC = 0x524b5049
        const val MAX_NAMES = 200_000
        const val MAX_NAME_BYTES = 4096
        const val MAX_IDENTITY_BYTES = 16_384
        const val MAX_FILE_BYTES = 32 * 1024 * 1024
        const val MAX_CACHE_BYTES = 64L * 1024 * 1024
    }
}
