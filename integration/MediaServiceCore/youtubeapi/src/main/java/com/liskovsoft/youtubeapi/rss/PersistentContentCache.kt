package com.liskovsoft.youtubeapi.rss

import com.google.gson.Gson
import com.liskovsoft.youtubeapi.app.AppService
import java.io.File
import java.security.MessageDigest

internal data class PersistentCacheStats(
    val entryCount: Int,
    val bytes: Long
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
        val file = fileForKey(key)
        if (!file.isFile) {
            return null
        }

        return try {
            gson.fromJson(file.readText(), type)
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    @Synchronized
    fun save(key: String, value: T) {
        val target = fileForKey(key)
        val temp = File(target.parentFile, target.name + ".tmp")

        try {
            temp.writeText(gson.toJson(value))

            if (target.exists()) {
                target.delete()
            }

            if (!temp.renameTo(target)) {
                target.writeText(temp.readText())
                temp.delete()
            }
        } catch (e: Exception) {
            temp.delete()
            e.printStackTrace()
        }
    }

    @Synchronized
    fun delete(key: String) {
        fileForKey(key).delete()
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
    fun loadAll(): List<T> {
        val result = mutableListOf<T>()

        directory.listFiles()?.forEach { file ->
            if (!file.isFile || file.name.endsWith(".tmp")) {
                return@forEach
            }

            try {
                gson.fromJson(file.readText(), type)?.let { result.add(it) }
            } catch (e: Exception) {
                file.delete()
            }
        }

        return result
    }

    @Synchronized
    fun stats(): PersistentCacheStats {
        var count = 0
        var bytes = 0L

        directory.listFiles()?.forEach { file ->
            if (file.isFile && !file.name.endsWith(".tmp")) {
                count++
                bytes += file.length()
            }
        }

        return PersistentCacheStats(count, bytes)
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
