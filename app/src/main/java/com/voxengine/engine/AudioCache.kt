package com.voxengine.engine

import android.util.LruCache
import com.voxengine.util.HexEncoding
import java.security.MessageDigest
import java.io.File

/**
 * 音频缓存管理器
 * 对相同引擎+文本+音色+风格的原始合成结果缓存，避免重复 API 调用
 */
object AudioCache {
    private const val MAX_CACHE_BYTES = 32 * 1024 * 1024 // 32MB，按字节封顶避免大 WAV 撑爆内存
    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 分钟
    private const val CACHE_VERSION = "reader-tts-v5"
    private val diskCache by lazy {
        FileAudioCache(File(com.voxengine.VoxEngineApplication.instance.cacheDir, "reader_audio"))
    }

    private data class CacheEntry(
        val audioData: ByteArray,
        val timestamp: Long
    )

    private val cache = object : LruCache<String, CacheEntry>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: CacheEntry): Int = value.audioData.size
    }

    // MessageDigest 非线程安全，ThreadLocal 避免热路径每次 getInstance
    private val md5Digest = ThreadLocal.withInitial { MessageDigest.getInstance("MD5") }

    /**
     * 生成缓存键
     */
    fun generateKey(
        text: String,
        voice: String,
        style: String?,
        engineId: String,
        voiceFingerprint: String = voice,
        temperature: Float? = null,
        context: String? = null
    ): String {
        val raw = "$CACHE_VERSION|$engineId|$voiceFingerprint|$text|${style ?: ""}|t=${temperature ?: ""}|context=${context.orEmpty()}"
        return md5(raw)
    }

    /**
     * 获取缓存的音频数据
     */
    fun get(key: String): ByteArray? {
        val entry = cache.get(key)

        // 检查是否过期
        if (entry != null && System.currentTimeMillis() - entry.timestamp <= CACHE_TTL_MS) return entry.audioData
        if (entry != null) {
            cache.remove(key)
        }
        return runCatching {
            diskCache.get(key)?.also { cache.put(key, CacheEntry(it, System.currentTimeMillis())) }
        }.getOrNull()
    }

    fun isPersisted(key: String): Boolean = runCatching { diskCache.contains(key) }.getOrDefault(false)

    fun put(key: String, audioData: ByteArray): Boolean {
        cache.put(key, CacheEntry(audioData, System.currentTimeMillis()))
        return runCatching { diskCache.put(key, audioData); diskCache.contains(key) }.getOrDefault(false)
    }

    /**
     * 清空缓存
     */
    fun clear() {
        cache.evictAll()
        runCatching { diskCache.clear() }
    }

    /**
     * 获取缓存大小
     */
    fun size(): Int = cache.size()

    private fun md5(input: String): String {
        val md = md5Digest.get()!!
        md.reset()
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return HexEncoding.lower(digest)
    }
}
