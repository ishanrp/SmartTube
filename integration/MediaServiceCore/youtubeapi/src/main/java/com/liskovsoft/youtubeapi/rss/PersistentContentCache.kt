package com.liskovsoft.youtubeapi.rss

import android.util.AtomicFile
import com.google.gson.Gson
import com.liskovsoft.youtubeapi.app.AppService
import java.io.File
import java.security.MessageDigest

internal data class PersistentCacheStats(
    val entryCount: Int,
    val bytes: Long,
    val freshEntries: Int,
    val staleEntries: Int,
    val veryStaleEntries: Int
)

internal class PersistentContentCache<T : Any>(
    namespace: String,
    private val type: Class<T>,
    private val retentionMs: Long
) {
    private val gson = Gson()
    private val directory: File by lazy {
        File(AppService.instance().context.filesDir, "content-cache/$namespace").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    @Synchronized
    fun load(key: String): T? {
        val target = fileForKey(key)
        val atomicFile = AtomicFile(target)

        if (!target.isFile && !File(target.path + ".bak").isFile) {
            return null
        }

        return try {
            atomicFile.openRead().bufferedReader().use { reader ->
                gson.fromJson(reader, type)
            }
        } catch (e: Exception) {
            atomicFile.delete()
            null
        }
    }

    @Synchronized
    fun save(key: String, value: T) {
        val atomicFile = AtomicFile(fileForKey(key))
        var stream: java.io.FileOutputStream? = null

        try {
            stream = atomicFile.startWrite()
            val writer = stream.writer(Charsets.UTF_8)
            gson.toJson(value, writer)
            writer.flush()
            atomicFile.finishWrite(stream)
        } catch (e: Exception) {
            if (stream != null) {
                atomicFile.failWrite(stream)
            }
            e.printStackTrace()
        }
    }

    @Synchronized
    fun delete(key: String) {
        AtomicFile(fileForKey(key)).delete()
    }

    @Synchronized
    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }

    @Synchronized
    fun prune(nowMs: Long = System.currentTimeMillis()) {
        directory.listFiles()?.forEach { file ->
            if (nowMs - file.lastModified() > retentionMs) {
                file.delete()
            }
        }
    }

    @Synchronized
    fun stats(
        freshTtlMs: Long,
        staleTtlMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): PersistentCacheStats {
        var count = 0
        var bytes = 0L
        var fresh = 0
        var stale = 0
        var veryStale = 0

        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".json")) {
                count++
                bytes += file.length()

                val age = nowMs - file.lastModified()
                when {
                    age <= freshTtlMs -> fresh++
                    age <= staleTtlMs -> stale++
                    else -> veryStale++
                }
            }
        }

        return PersistentCacheStats(count, bytes, fresh, stale, veryStale)
    }

    private fun fileForKey(key: String): File {
        return File(directory, sha256(key) + ".json")
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(digest.size * 2)

        for (byte in digest) {
            builder.append(String.format("%02x", byte.toInt() and 0xff))
        }

        return builder.toString()
    }
}
