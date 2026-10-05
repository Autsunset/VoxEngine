package com.voxengine.engine

import java.io.File

/** Complete files only; rebuilding this cache after process death preserves generated audio. */
class FileAudioCache(
    private val directory: File,
    private val maxBytes: Long = 2L * 1024 * 1024 * 1024,
    private val ttlMs: Long = 30L * 24 * 60 * 60 * 1000,
    private val now: () -> Long = System::currentTimeMillis
) {
    private var bytes = directory.listFiles()?.filter { it.extension == "wav" }?.sumOf { it.length() } ?: 0L
    private var lastPrunedAt = 0L

    @Synchronized fun contains(key: String): Boolean = File(directory, "$key.wav").let {
        it.isFile && it.length() > 0 && now() - it.lastModified() <= ttlMs
    }

    @Synchronized fun get(key: String): ByteArray? {
        val file = File(directory, "$key.wav")
        if (!file.isFile) return null
        if (now() - file.lastModified() > ttlMs) {
            val size = file.length()
            if (file.delete()) bytes -= size
            return null
        }
        return file.readBytes().takeIf { it.isNotEmpty() }
    }

    @Synchronized fun put(key: String, audio: ByteArray) {
        require(audio.isNotEmpty()) { "不能缓存空音频" }
        directory.mkdirs()
        val file = File(directory, "$key.wav")
        val oldSize = if (file.exists()) file.length() else 0L
        val temporary = File.createTempFile("$key-", ".tmp", directory)
        try {
            temporary.writeBytes(audio)
            if (!temporary.renameTo(file)) error("无法保存音频缓存")
            file.setLastModified(now())
            bytes += audio.size - oldSize
        } finally { temporary.delete() }
        val timestamp = now()
        if (bytes > maxBytes || timestamp - lastPrunedAt >= 30_000) {
            lastPrunedAt = timestamp
            val files = directory.listFiles()?.filter { it.extension == "wav" }?.sortedBy { it.lastModified() }.orEmpty()
            bytes = files.sumOf { it.length() }
            for (entry in files) {
                if (bytes > maxBytes || timestamp - entry.lastModified() > ttlMs) {
                    val size = entry.length()
                    if (entry.delete()) bytes -= size
                }
            }
        }
    }

    @Synchronized fun clear() {
        directory.listFiles()?.forEach { it.delete() }
        bytes = 0
    }
}
