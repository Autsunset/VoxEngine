package com.voxengine.reader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.voxengine.MainActivity
import com.voxengine.R
import com.voxengine.audio.AudioUtils
import com.voxengine.data.AppDatabase
import com.voxengine.engine.EngineRegistry
import com.voxengine.engine.TTSEngine
import com.voxengine.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

class ReaderPlaybackService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playbackJob: Job? = null
    private var currentTrack: AudioTrack? = null
    private var isPaused = false
    private var state: PlaybackState? = null
    private var lastConservativeSynthesisAt = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startPlayback(intent)
            ACTION_PAUSE -> pausePlayback()
            ACTION_RESUME -> resumePlayback()
            ACTION_STOP -> stopPlayback()
            ACTION_PREVIOUS_CHAPTER -> moveChapter(-1)
            ACTION_NEXT_CHAPTER -> moveChapter(1)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopPlayback(releaseService = false)
        super.onDestroy()
    }

    private fun startPlayback(intent: Intent) {
        val uri = intent.getStringExtra(EXTRA_URI) ?: return
        state = PlaybackState(
            uri = uri,
            title = intent.getStringExtra(EXTRA_TITLE) ?: "本地小说",
            voice = intent.getStringExtra(EXTRA_VOICE) ?: "冰糖",
            style = intent.getStringExtra(EXTRA_STYLE)?.ifBlank { null },
            engineId = intent.getStringExtra(EXTRA_ENGINE_ID) ?: "mimo",
            chapterIndex = intent.getIntExtra(EXTRA_CHAPTER_INDEX, 0),
            pageIndex = intent.getIntExtra(EXTRA_PAGE_INDEX, 0),
            paragraphIndex = intent.getIntExtra(EXTRA_PARAGRAPH_INDEX, 0).coerceAtLeast(0),
            pageTargetLength = intent.getIntExtra(EXTRA_PAGE_TARGET_LENGTH, 220).coerceIn(90, 520),
            gapMs = intent.getLongExtra(EXTRA_GAP_MS, 700L).coerceAtLeast(0L),
            stopAtMillis = intent.getIntExtra(EXTRA_SLEEP_MINUTES, 0).let { minutes ->
                if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
            },
            stopAfterChapters = intent.getIntExtra(EXTRA_STOP_AFTER_CHAPTERS, 0),
            conservativeRequestIntervalMs = intent.getIntExtra(
                EXTRA_CONSERVATIVE_REQUEST_INTERVAL_MS,
                DEFAULT_CONSERVATIVE_REQUEST_INTERVAL_MS
            ).coerceIn(500, 30_000).toLong(),
            retryCount = intent.getIntExtra(EXTRA_RETRY_COUNT, DEFAULT_RETRY_COUNT).coerceIn(0, 8),
            retryBaseDelayMs = intent.getIntExtra(EXTRA_RETRY_BASE_DELAY_MS, DEFAULT_RETRY_BASE_DELAY_MS).coerceIn(500, 15_000).toLong()
        )
        playbackJob?.cancel()
        currentTrack?.releaseSafely()
        isPaused = false
        startForeground(NOTIFICATION_ID, buildNotification("准备播放", isPlaying = true))
        playbackJob = serviceScope.launch { runPlayback() }
    }

    private suspend fun runPlayback() {
        try {
            runPlaybackSafely()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.appendLog("E", TAG, "Reader playback failed: ${e.message}")
            updateNotification("听书失败: ${e.message ?: "未知错误"}", false)
            state = null
            isPaused = false
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private suspend fun runPlaybackSafely() {
        val playbackState = state ?: return
        val engine = EngineRegistry.get(playbackState.engineId)
        if (engine == null) {
            updateNotification("未找到引擎 ${playbackState.engineId}", false)
            return
        }
        val db = AppDatabase.getDatabase(this)
        val chapters = withContext(Dispatchers.IO) {
            val bytes = contentResolver.openInputStream(Uri.parse(playbackState.uri))?.use { it.readBytes() }
                ?: throw FileNotFoundException(playbackState.uri)
            TxtNovelParser.parse(TxtNovelParser.decode(bytes))
        }
        if (chapters.isEmpty()) {
            updateNotification("没有可播放章节", false)
            return
        }
        val customVoice = withContext(Dispatchers.IO) { db.voiceDao().getVoiceByName(playbackState.voice) }
        val conservativeSynthesis = customVoice?.type == "clone" || customVoice?.type == "design"

        var position = normalizePosition(chapters, PlaybackPosition(playbackState.chapterIndex, playbackState.pageIndex))
        val startPosition = position
        var finishedChapters = 0
        val audioCache = mutableMapOf<ChunkKey, Deferred<Result<AudioChunk>>>()
        var prefetchTail: Deferred<Result<AudioChunk>>? = null
        var playbackFailed = false

        while (currentCoroutineContext().isActive && position != null) {
            if (playbackState.stopAtMillis > 0 && System.currentTimeMillis() >= playbackState.stopAtMillis) break
            if (playbackState.stopAfterChapters > 0 && finishedChapters >= playbackState.stopAfterChapters) break

            playbackState.chapterIndex = position.chapterIndex
            playbackState.pageIndex = position.pageIndex
            playbackState.paragraphIndex = if (position == startPosition) playbackState.paragraphIndex else 0
            sendProgress(position.chapterIndex, position.pageIndex, playbackState.paragraphIndex)
            val chapter = chapters[position.chapterIndex]
            val pages = pagesForPlayback(chapters, position.chapterIndex, playbackState)
            val page = pages.getOrNull(position.pageIndex) ?: break
            val startParagraphIndex = if (position == startPosition) playbackState.paragraphIndex else 0
            updateNotification("${chapter.title} · 第${position.pageIndex + 1}页 合成中", true)

            val nextPosition = nextPosition(chapters, position)
            prefetchTail = schedulePrefetchWindow(
                chapters = chapters,
                currentPosition = position,
                startParagraphIndex = startParagraphIndex,
                nextPosition = nextPosition,
                playbackState = playbackState,
                engine = engine,
                conservativeSynthesis = conservativeSynthesis,
                audioCache = audioCache,
                prefetchTail = prefetchTail
            )

            val currentKeys = chunkKeysForPlayback(chapters, position, playbackState, startParagraphIndex).map { it.first }
            if (currentKeys.isEmpty()) {
                LogManager.appendLog("E", TAG, "Page synthesis returned no playable chunks")
                updateNotification("当前页没有可播放音频", false)
                break
            }

            updateNotification("${chapter.title} · 第${position.pageIndex + 1}页", true)
            var pageFailed = false
            var lastProgressParagraphIndex = -1
            for (index in currentKeys.indices) {
                val key = currentKeys[index]
                val nextKey = currentKeys.getOrNull(index + 1)
                while (currentCoroutineContext().isActive && isPaused) delay(150)
                if (!currentCoroutineContext().isActive) break
                val result = audioCache[key]?.await() ?: Result.failure(IllegalStateException("音频缓存不存在"))
                val chunk = result
                    .onFailure { error ->
                        LogManager.appendLog("E", TAG, "Paragraph ${key.paragraphIndex}.${key.chunkIndex} synthesis failed: ${error.message}")
                        updateNotification(friendlySynthesisError(error), false)
                        audioCache.remove(key)
                        pageFailed = true
                        playbackFailed = true
                    }
                    .getOrNull() ?: break
                playbackState.paragraphIndex = chunk.paragraphIndex
                if (chunk.paragraphIndex != lastProgressParagraphIndex) {
                    lastProgressParagraphIndex = chunk.paragraphIndex
                    sendProgress(position.chapterIndex, position.pageIndex, chunk.paragraphIndex)
                    db.readerBookDao().updateProgress(playbackState.uri, position.chapterIndex, position.pageIndex, chunk.paragraphIndex)
                }
                runCatching { playAudioChunk(chunk.audioData) }
                    .onFailure { error ->
                        LogManager.appendLog("E", TAG, "Audio playback failed: ${error.message}")
                        updateNotification("音频播放失败: ${error.message ?: "未知错误"}", false)
                        pageFailed = true
                        playbackFailed = true
                    }
                if (pageFailed) break
                audioCache.remove(key)
                if (playbackState.gapMs > 0 && nextKey?.paragraphIndex != key.paragraphIndex) {
                    delay(playbackState.gapMs)
                }
            }
            if (pageFailed) {
                audioCache.values.forEach { it.cancel() }
                audioCache.clear()
                break
            }
            db.readerBookDao().updateProgress(playbackState.uri, position.chapterIndex, position.pageIndex, 0)

            if (nextPosition != null && nextPosition.chapterIndex != position.chapterIndex) {
                finishedChapters += 1
            }
            position = nextPosition
        }

        if (!playbackFailed) {
            updateNotification("听书已结束", false)
        }
        state = null
        isPaused = false
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private suspend fun schedulePrefetchWindow(
        chapters: List<TxtChapter>,
        currentPosition: PlaybackPosition,
        startParagraphIndex: Int,
        nextPosition: PlaybackPosition?,
        playbackState: PlaybackState,
        engine: TTSEngine,
        conservativeSynthesis: Boolean,
        audioCache: MutableMap<ChunkKey, Deferred<Result<AudioChunk>>>,
        prefetchTail: Deferred<Result<AudioChunk>>?
    ): Deferred<Result<AudioChunk>>? {
        val window = buildList {
            addAll(chunkKeysForPlayback(chapters, currentPosition, playbackState, startParagraphIndex))
            if (nextPosition != null) {
                addAll(chunkKeysForPlayback(chapters, nextPosition, playbackState, 0))
            }
        }
        var tail = prefetchTail
        for ((key, chunkText) in window) {
            val existing = audioCache[key]
            if (existing != null) {
                tail = existing
                continue
            }
            val previous = tail
            val deferred = CoroutineScope(currentCoroutineContext()).async(Dispatchers.IO) {
                val previousResult = previous?.await()
                if (previousResult != null && previousResult.isFailure) {
                    Result.failure(previousResult.exceptionOrNull() ?: IllegalStateException("上一段合成失败"))
                } else {
                    try {
                        Result.success(
                            synthesizeParagraph(
                                engine,
                                chunkText,
                                playbackState.voice,
                                playbackState.style,
                                key.paragraphIndex,
                                conservativeSynthesis,
                                playbackState.conservativeRequestIntervalMs,
                                playbackState.retryCount,
                                playbackState.retryBaseDelayMs
                            )
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                }
            }
            audioCache[key] = deferred
            tail = deferred
        }
        return tail
    }

    private fun chunkKeysForPlayback(
        chapters: List<TxtChapter>,
        position: PlaybackPosition,
        playbackState: PlaybackState,
        startParagraphIndex: Int
    ): List<Pair<ChunkKey, String>> {
        val page = pagesForPlayback(chapters, position.chapterIndex, playbackState).getOrNull(position.pageIndex)
            ?: return emptyList()
        val startIndex = startParagraphIndex.coerceIn(0, page.paragraphs.size)
        return page.paragraphs.drop(startIndex).flatMapIndexed { offset, paragraph ->
            val paragraphIndex = startIndex + offset
            splitTextForTts(paragraph).mapIndexed { chunkIndex, chunk ->
                ChunkKey(position, paragraphIndex, chunkIndex) to chunk
            }
        }
    }

    private fun splitTextForTts(text: String): List<String> {
        if (text.length <= MAX_TTS_CHUNK_CHARS) return listOf(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = chooseTtsSplitEnd(text, start)
            chunks += text.substring(start, end)
            start = end
        }
        return chunks.ifEmpty { listOf(text) }.also { parts ->
            check(parts.joinToString(separator = "") == text) { "TTS split changed source text" }
        }
    }

    private fun chooseTtsSplitEnd(text: String, start: Int): Int {
        val maxEnd = minOf(text.length, start + MAX_TTS_CHUNK_CHARS)
        if (maxEnd == text.length) return text.length
        val minEnd = minOf(text.length, start + MIN_TTS_CHUNK_CHARS)
        val sentenceEnd = findLastSplitChar(text, minEnd, maxEnd, SENTENCE_END_CHARS)
        if (sentenceEnd > start) return sentenceEnd
        val softEnd = findLastSplitChar(text, minEnd, maxEnd, SOFT_SPLIT_CHARS)
        if (softEnd > start) return softEnd
        return maxEnd
    }

    private fun findLastSplitChar(text: String, minEnd: Int, maxEnd: Int, chars: String): Int {
        for (index in maxEnd - 1 downTo minEnd) {
            if (text[index] in chars) return index + 1
        }
        return -1
    }

    private suspend fun synthesizeParagraph(
        engine: TTSEngine,
        paragraph: String,
        voice: String,
        style: String?,
        paragraphIndex: Int,
        conservativeSynthesis: Boolean,
        conservativeRequestIntervalMs: Long,
        retryCount: Int,
        retryBaseDelayMs: Long
    ): AudioChunk {
        var attempt = 0
        var lastError: Throwable? = null
        val maxAttempts = 1 + retryCount
        while (attempt < maxAttempts) {
            try {
                if (attempt > 0) delay(retryBaseDelayMs * attempt * attempt)
                if (conservativeSynthesis) throttleConservativeSynthesis(conservativeRequestIntervalMs)
                return AudioChunk(paragraphIndex, engine.synthesize(paragraph, voice, style).audioData)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastError = error
                val retryable = error.message?.contains("429") == true || error is IOException
                if (!retryable || attempt >= maxAttempts - 1) {
                    LogManager.appendLog("E", TAG, "Paragraph $paragraphIndex synthesis failed: ${error.message}")
                    throw error
                }
                LogManager.appendLog("W", TAG, "Paragraph $paragraphIndex synthesis retry ${attempt + 1}: ${error.message}")
                attempt += 1
            }
        }
        throw lastError ?: IllegalStateException("段落合成失败")
    }

    private fun friendlySynthesisError(error: Throwable): String {
        val message = error.message.orEmpty()
        return if (message.contains("429")) {
            "合成过快被限流，请调大段落间隔或缩短克隆音频后再试"
        } else {
            "段落合成失败: ${message.ifBlank { "未知错误" }}"
        }
    }

    private suspend fun throttleConservativeSynthesis(intervalMs: Long) {
        val elapsed = System.currentTimeMillis() - lastConservativeSynthesisAt
        if (elapsed in 0 until intervalMs) {
            delay(intervalMs - elapsed)
        }
        lastConservativeSynthesisAt = System.currentTimeMillis()
    }

    private fun pagesForPlayback(
        chapters: List<TxtChapter>,
        chapterIndex: Int,
        playbackState: PlaybackState
    ): List<TxtPage> = ReaderMeasuredPageCache.getChapterPages(playbackState.uri, chapterIndex)
        ?: TxtNovelParser.paginate(chapters[chapterIndex].content, playbackState.pageTargetLength).also {
            LogManager.appendLog("W", TAG, "Reader fallback pagination used: chapter=$chapterIndex pages=${it.size}")
        }

    private fun normalizePosition(chapters: List<TxtChapter>, position: PlaybackPosition): PlaybackPosition? {
        val playbackState = state ?: return null
        if (chapters.isEmpty()) return null
        var chapterIndex = position.chapterIndex.coerceIn(0, chapters.lastIndex)
        while (chapterIndex <= chapters.lastIndex) {
            val pages = pagesForPlayback(chapters, chapterIndex, playbackState)
            if (pages.isNotEmpty()) {
                return PlaybackPosition(chapterIndex, position.pageIndex.coerceIn(0, pages.lastIndex))
            }
            chapterIndex += 1
        }
        return null
    }

    private fun nextPosition(chapters: List<TxtChapter>, position: PlaybackPosition): PlaybackPosition? {
        val playbackState = state ?: return null
        val pages = pagesForPlayback(chapters, position.chapterIndex, playbackState)
        if (position.pageIndex < pages.lastIndex) return PlaybackPosition(position.chapterIndex, position.pageIndex + 1)
        val nextChapter = position.chapterIndex + 1
        if (nextChapter > chapters.lastIndex) return null
        return normalizePosition(chapters, PlaybackPosition(nextChapter, 0))
    }

    private suspend fun playAudioChunk(wavData: ByteArray) = withContext(Dispatchers.IO) {
        val sampleRate = AudioUtils.getWavSampleRate(wavData)
        val channelCount = AudioUtils.getWavChannelCount(wavData)
        val bitsPerSample = AudioUtils.getWavBitsPerSample(wavData)
        val pcmData = AudioUtils.extractPcmData(wavData)
        val channelConfig = if (channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val encoding = if (bitsPerSample == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT
        val bytesPerFrame = channelCount * (bitsPerSample / 8).coerceAtLeast(1)
        val frameCount = if (bytesPerFrame > 0) pcmData.size / bytesPerFrame else pcmData.size
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(encoding)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(bufferSize, pcmData.size))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        currentTrack = track
        try {
            track.write(pcmData, 0, pcmData.size)
            track.play()
            while (currentCoroutineContext().isActive) {
                val stillPlaying = runCatching {
                    track.playState == AudioTrack.PLAYSTATE_PLAYING && track.playbackHeadPosition < frameCount
                }.getOrDefault(false)
                if (!stillPlaying) break
                if (isPaused) {
                    runCatching { track.pause() }
                    while (currentCoroutineContext().isActive && isPaused) Thread.sleep(100)
                    if (currentCoroutineContext().isActive) runCatching { track.play() }
                }
                Thread.sleep(50)
            }
        } finally {
            currentTrack = null
            track.releaseSafely()
        }
    }

    private fun pausePlayback() {
        if (state == null || playbackJob == null) return
        isPaused = true
        currentTrack?.let { track -> runCatching { track.pause() } }
        updateNotification("已暂停", false)
    }

    private fun resumePlayback() {
        if (state == null || playbackJob == null) return
        isPaused = false
        currentTrack?.let { track -> runCatching { track.play() } }
        updateNotification("播放中", true)
    }

    private fun moveChapter(delta: Int) {
        val playbackState = state ?: return
        playbackState.chapterIndex = (playbackState.chapterIndex + delta).coerceAtLeast(0)
        playbackState.pageIndex = 0
        playbackState.paragraphIndex = 0
        playbackJob?.cancel()
        currentTrack?.releaseSafely()
        isPaused = false
        playbackJob = serviceScope.launch { runPlayback() }
    }

    private fun stopPlayback(releaseService: Boolean = true) {
        playbackJob?.cancel()
        currentTrack?.releaseSafely()
        playbackJob = null
        currentTrack = null
        state = null
        isPaused = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (releaseService) stopSelf()
    }

    private fun updateNotification(text: String, isPlaying: Boolean) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text, isPlaying))
    }

    private fun sendProgress(chapterIndex: Int, pageIndex: Int, paragraphIndex: Int) {
        val playbackState = state ?: return
        sendBroadcast(
            Intent(ACTION_PROGRESS)
                .setPackage(packageName)
                .putExtra(EXTRA_URI, playbackState.uri)
                .putExtra(EXTRA_CHAPTER_INDEX, chapterIndex)
                .putExtra(EXTRA_PAGE_INDEX, pageIndex)
                .putExtra(EXTRA_PARAGRAPH_INDEX, paragraphIndex)
        )
    }

    private fun buildNotification(text: String, isPlaying: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(state?.title ?: "VoxEngine 听书")
            .setContentText(text)
            .setOngoing(isPlaying)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "继续" else "暂停",
                serviceIntent(if (isPaused) ACTION_RESUME else ACTION_PAUSE, 1)
            )
            .addAction(android.R.drawable.ic_media_previous, "上一章", serviceIntent(ACTION_PREVIOUS_CHAPTER, 2))
            .addAction(android.R.drawable.ic_media_next, "下一章", serviceIntent(ACTION_NEXT_CHAPTER, 3))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", serviceIntent(ACTION_STOP, 4))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, ReaderPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_reader),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.channel_reader_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun AudioTrack.releaseSafely() {
        runCatching { stop() }
        runCatching { release() }
    }

    private data class PlaybackState(
        val uri: String,
        val title: String,
        val voice: String,
        val style: String?,
        val engineId: String,
        var chapterIndex: Int,
        var pageIndex: Int,
        var paragraphIndex: Int,
        val pageTargetLength: Int,
        val gapMs: Long,
        val stopAtMillis: Long,
        val stopAfterChapters: Int,
        val conservativeRequestIntervalMs: Long,
        val retryCount: Int,
        val retryBaseDelayMs: Long
    )

    private data class PlaybackPosition(val chapterIndex: Int, val pageIndex: Int)
    private data class ChunkKey(val position: PlaybackPosition, val paragraphIndex: Int, val chunkIndex: Int)
    private data class AudioChunk(val paragraphIndex: Int, val audioData: ByteArray)

    companion object {
        const val ACTION_START = "com.voxengine.reader.START"
        const val ACTION_PAUSE = "com.voxengine.reader.PAUSE"
        const val ACTION_RESUME = "com.voxengine.reader.RESUME"
        const val ACTION_STOP = "com.voxengine.reader.STOP"
        const val ACTION_PREVIOUS_CHAPTER = "com.voxengine.reader.PREVIOUS_CHAPTER"
        const val ACTION_NEXT_CHAPTER = "com.voxengine.reader.NEXT_CHAPTER"
        const val ACTION_PROGRESS = "com.voxengine.reader.PROGRESS"

        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_STYLE = "style"
        const val EXTRA_ENGINE_ID = "engine_id"
        const val EXTRA_CHAPTER_INDEX = "chapter_index"
        const val EXTRA_PAGE_INDEX = "page_index"
        const val EXTRA_PARAGRAPH_INDEX = "paragraph_index"
        const val EXTRA_PAGE_TARGET_LENGTH = "page_target_length"
        const val EXTRA_GAP_MS = "gap_ms"
        const val EXTRA_SLEEP_MINUTES = "sleep_minutes"
        const val EXTRA_STOP_AFTER_CHAPTERS = "stop_after_chapters"
        const val EXTRA_CONSERVATIVE_REQUEST_INTERVAL_MS = "conservative_request_interval_ms"
        const val EXTRA_RETRY_COUNT = "retry_count"
        const val EXTRA_RETRY_BASE_DELAY_MS = "retry_base_delay_ms"

        private const val TAG = "ReaderPlaybackService"
        private const val DEFAULT_CONSERVATIVE_REQUEST_INTERVAL_MS = 5000
        private const val DEFAULT_RETRY_COUNT = 3
        private const val DEFAULT_RETRY_BASE_DELAY_MS = 2000
        private const val MIN_TTS_CHUNK_CHARS = 40
        private const val MAX_TTS_CHUNK_CHARS = 180
        private const val SENTENCE_END_CHARS = "。！？；.!?;"
        private const val SOFT_SPLIT_CHARS = "，、,：:"
        private const val CHANNEL_ID = "reader_playback"
        private const val NOTIFICATION_ID = 2001
    }
}
