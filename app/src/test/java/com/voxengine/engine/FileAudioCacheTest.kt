package com.voxengine.engine

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileAudioCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun generatedAudioSurvivesNewCacheInstanceAndCanBeCleared() {
        val folder = temporary.newFolder()
        FileAudioCache(folder).put("audio", byteArrayOf(1, 2, 3))
        val reopened = FileAudioCache(folder)
        assertArrayEquals(byteArrayOf(1, 2, 3), reopened.get("audio"))
        reopened.clear()
        assertNull(reopened.get("audio"))
    }

    @Test fun sizeBoundEvictsOldestAndExpiryPreventsStalePlayback() {
        val folder = temporary.newFolder()
        var time = 100_000L
        val cache = FileAudioCache(folder, maxBytes = 5, ttlMs = 100, now = { time })
        cache.put("first", byteArrayOf(1, 2, 3))
        time++
        cache.put("second", byteArrayOf(4, 5, 6))
        assertNull(cache.get("first"))
        assertArrayEquals(byteArrayOf(4, 5, 6), cache.get("second"))
        time += 101
        assertNull(cache.get("second"))
        assertTrue(folder.listFiles()!!.none { it.extension == "tmp" })
    }
}
