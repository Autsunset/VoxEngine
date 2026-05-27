package com.voxengine.engine

import android.util.LruCache
import java.security.MessageDigest

/**
 * 音频缓存管理器
 * 对相同文本+音色+风格+语速的合成结果缓存，避免重复 API 调用
 */
object AudioCache {
    private const val MAX_CACHE_SIZE = 50
    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 分钟

    private data class CacheEntry(
        val audioData: ByteArray,
        val timestamp: Long
    )

    private val cache = LruCache<String, CacheEntry>(MAX_CACHE_SIZE)

    /**
     * 生成缓存键
     */
    fun generateKey(text: String, voice: String, style: String?): String {
        val raw = "$text|$voice|${style ?: ""}"
        return md5(raw)
    }

    /**
     * 获取缓存的音频数据
     */
    fun get(key: String): ByteArray? {
        val entry = cache.get(key) ?: return null
        
        // 检查是否过期
        if (System.currentTimeMillis() - entry.timestamp > CACHE_TTL_MS) {
            cache.remove(key)
            return null
        }
        
        return entry.audioData
    }

    /**
     * 存储音频数据到缓存
     */
    fun put(key: String, audioData: ByteArray) {
        cache.put(key, CacheEntry(audioData, System.currentTimeMillis()))
    }

    /**
     * 清空缓存
     */
    fun clear() {
        cache.evictAll()
    }

    /**
     * 获取缓存大小
     */
    fun size(): Int = cache.size()

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
