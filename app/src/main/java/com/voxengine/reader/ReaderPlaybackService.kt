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
import androidx.room.withTransaction
import com.voxengine.MainActivity
import com.voxengine.R
import com.voxengine.audio.AudioUtils
import com.voxengine.data.AppDatabase
import com.voxengine.data.ReaderChapterEntity
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

private typealias PlaybackPosition = ReaderPlaybackPlanner.Position
private typealias ChunkKey = ReaderPlaybackPlanner.ChunkKey

data class PlaybackSnapshot(
    val uri: String,
    val chapterIndex: Int,
    val pageIndex: Int,
    val paragraphIndex: Int,
    val isListening: Boolean,
    val isPaused: Boolean
)

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
        val roleProfile = RoleProfileJson.parse(intent.getStringExtra(EXTRA_ROLE_PROFILE_JSON))
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
            retryBaseDelayMs = intent.getIntExtra(EXTRA_RETRY_BASE_DELAY_MS, DEFAULT_RETRY_BASE_DELAY_MS).coerceIn(500, 15_000).toLong(),
            // 分角色朗读档：旁白/对话/具名角色各自的音色与可选风格；未开启时仍透传，由 roleEnabled 控制。
            roleEnabled = intent.getBooleanExtra(EXTRA_ROLE_ENABLED, false),
            roleProfile = roleProfile
        )
        playbackJob?.cancel()
        currentTrack?.releaseSafely()
        isPaused = false
        startForeground(NOTIFICATION_ID, buildNotification("准备播放", isPlaying = true))
        playbackJob = serviceScope.launch { runPlayback() }
        publishPlaybackState(true)
    }

    private suspend fun runPlayback() {
        try {
            runPlaybackSafely()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.appendLog("E", TAG, "Reader playback failed: ${e.message}")
            updateNotification("听书失败: ${e.message ?: "未知错误"}", false)
            isPaused = false
            publishPlaybackState(false)
            state = null
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private suspend fun runPlaybackSafely() {
        val playbackState = state ?: return
        playbackState.speed = withContext(Dispatchers.IO) {
            com.voxengine.data.SettingsRepository(applicationContext).speed.first()
        }
        val engine = EngineRegistry.get(playbackState.engineId)
        if (engine == null) {
            updateNotification("未找到引擎 ${playbackState.engineId}", false)
            return
        }
        val db = AppDatabase.getDatabase(this)
        val chapters = withContext(Dispatchers.IO) {
            ReaderChapterCache.getChapters(playbackState.uri)
                ?: db.readerChapterDao().getChapters(playbackState.uri)
                    .map { it.toTxtChapter() }
                    .takeIf { it.isNotEmpty() }
                    ?.also { ReaderChapterCache.putChapters(playbackState.uri, it) }
                ?: run {
                    val bytes = contentResolver.openInputStream(Uri.parse(playbackState.uri))?.use { it.readBytes() }
                        ?: throw FileNotFoundException(playbackState.uri)
                    TxtNovelParser.parse(TxtNovelParser.decode(bytes)).also { parsedChapters ->
                        ReaderChapterCache.putChapters(playbackState.uri, parsedChapters)
                        db.withTransaction {
                            db.readerChapterDao().deleteByBookUri(playbackState.uri)
                            db.readerChapterDao().insertAll(
                                parsedChapters.mapIndexed { index, chapter ->
                                    ReaderChapterEntity.fromTxtChapter(playbackState.uri, index, chapter)
                                }
                            )
                        }
                    }
                }
        }
        if (chapters.isEmpty()) {
            updateNotification("没有可播放章节", false)
            return
        }
        val customVoice = withContext(Dispatchers.IO) { db.voiceDao().getVoiceByName(playbackState.voice) }
        val defaultConservative = customVoice?.type == "clone" || customVoice?.type == "design"
        // 分角色开启时，旁白/对话/各角色音色可能各异；预取所有可能用到的音色的类型，决定是否需要节流。
        val voiceConservative = buildVoiceConservativeMap(playbackState, db, defaultConservative)

        var position = normalizePosition(chapters, PlaybackPosition(playbackState.chapterIndex, playbackState.pageIndex))
        val startPosition = position
        var finishedChapters = 0
        val audioCache = mutableMapOf<ChunkKey, Deferred<Result<AudioChunk>>>()
        var prefetchTail: Deferred<Result<AudioChunk>>? = null
        var playbackFailed = false
        val nextChapterPrefetchPagesByChapter = mutableMapOf<Int, Int>()

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
            val nextChapterPrefetchPageCount = nextChapterPrefetchPagesByChapter[position.chapterIndex] ?: 0
            prefetchTail = schedulePrefetchWindow(
                chapters = chapters,
                currentPosition = position,
                startParagraphIndex = startParagraphIndex,
                nextChapterPrefetchPageCount = nextChapterPrefetchPageCount,
                playbackState = playbackState,
                engine = engine,
                voiceConservative = voiceConservative,
                audioCache = audioCache,
                prefetchTail = prefetchTail
            )

            val currentChunks = chunkKeysForPlayback(chapters, position, playbackState, startParagraphIndex)
            if (currentChunks.isEmpty()) {
                LogManager.appendLog("E", TAG, "Page synthesis returned no playable chunks")
                updateNotification("当前页没有可播放音频", false)
                break
            }

            updateNotification("${chapter.title} · 第${position.pageIndex + 1}页", true)
            var pageFailed = false
            var lastProgressParagraphIndex = -1
            for (index in currentChunks.indices) {
                val (key, roleChunk) = currentChunks[index]
                val nextKey = currentChunks.getOrNull(index + 1)?.first
                while (currentCoroutineContext().isActive && isPaused) delay(150)
                if (!currentCoroutineContext().isActive) break

                val (resolvedVoice, resolvedStyle) = resolveAssignment(playbackState, roleChunk)
                val conservativeForChunk = voiceConservative[resolvedVoice] ?: defaultConservative
                val preparedResult = audioCache[key]?.await()
                audioCache.remove(key)
                var chunk = preparedResult?.getOrNull()
                if (chunk == null) {
                    val preparedError = preparedResult?.exceptionOrNull()
                    if (preparedError != null) {
                        LogManager.appendLog("W", TAG, "Paragraph " + key.paragraphIndex + "." + key.chunkIndex + " prefetch unavailable, synthesizing inline: " + preparedError.message)
                    } else {
                        LogManager.appendLog("W", TAG, "Paragraph " + key.paragraphIndex + "." + key.chunkIndex + " prefetch missing, synthesizing inline")
                    }
                    updateNotification(chapter.title + " · 第" + (position.pageIndex + 1) + "页 补合成中", true)
                    chunk = try {
                        synthesizeParagraph(
                            engine,
                            roleChunk.text,
                            resolvedVoice,
                            resolvedStyle,
                            key.paragraphIndex,
                            conservativeForChunk,
                            playbackState.conservativeRequestIntervalMs,
                            playbackState.retryCount,
                            playbackState.retryBaseDelayMs
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        LogManager.appendLog("E", TAG, "Paragraph " + key.paragraphIndex + "." + key.chunkIndex + " inline synthesis failed: " + error.message)
                        updateNotification(com.voxengine.util.TtsErrors.friendly(error), false)
                        pageFailed = true
                        playbackFailed = true
                        null
                    }
                }
                if (chunk == null) break
                playbackState.paragraphIndex = chunk.paragraphIndex
                if (chunk.paragraphIndex != lastProgressParagraphIndex) {
                    lastProgressParagraphIndex = chunk.paragraphIndex
                    sendProgress(position.chapterIndex, position.pageIndex, chunk.paragraphIndex)
                }
                runCatching { playAudioChunk(chunk.audioData) }
                    .onFailure { error ->
                        LogManager.appendLog("E", TAG, "Audio playback failed: ${error.message}")
                        updateNotification("音频播放失败: ${error.message ?: "未知错误"}", false)
                        pageFailed = true
                        playbackFailed = true
                    }
                if (pageFailed) break
                db.readerBookDao().updateProgress(playbackState.uri, position.chapterIndex, position.pageIndex, chunk.paragraphIndex)
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
            nextChapterPrefetchPagesByChapter[position.chapterIndex] = nextChapterPrefetchPageCount + 1

            if (nextPosition != null && nextPosition.chapterIndex != position.chapterIndex) {
                finishedChapters += 1
            }
            position = nextPosition
        }

        if (!playbackFailed) {
            updateNotification("听书已结束", false)
            publishPlaybackState(false)
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
        nextChapterPrefetchPageCount: Int,
        playbackState: PlaybackState,
        engine: TTSEngine,
        voiceConservative: Map<String, Boolean>,
        audioCache: MutableMap<ChunkKey, Deferred<Result<AudioChunk>>>,
        prefetchTail: Deferred<Result<AudioChunk>>?
    ): Deferred<Result<AudioChunk>>? {
        val window: List<Pair<ChunkKey, ReaderPlaybackPlanner.RoleChunk>> =
            if (playbackState.roleEnabled) {
                ReaderPlaybackPlanner.buildPrefetchWindowRoleAware(
                    chapters = chapters,
                    currentPosition = currentPosition,
                    startParagraphIndex = startParagraphIndex,
                    nextChapterPrefetchPageCount = nextChapterPrefetchPageCount,
                    pageTargetLength = playbackState.pageTargetLength,
                    maxChunks = ReaderPlaybackPlanner.MAX_PREFETCH_AHEAD,
                    pagesForChapter = pageProvider(chapters, playbackState)
                )
            } else {
                ReaderPlaybackPlanner.buildPrefetchWindow(
                    chapters = chapters,
                    currentPosition = currentPosition,
                    startParagraphIndex = startParagraphIndex,
                    nextChapterPrefetchPageCount = nextChapterPrefetchPageCount,
                    pageTargetLength = playbackState.pageTargetLength,
                    maxChunks = ReaderPlaybackPlanner.MAX_PREFETCH_AHEAD,
                    pagesForChapter = pageProvider(chapters, playbackState)
                ).map { (key, text) ->
                    key to ReaderPlaybackPlanner.RoleChunk(SpeechRole.NARRATION, null, text)
                }
            }
        var tail = prefetchTail
        for ((key, roleChunk) in window) {
            if (audioCache.containsKey(key)) continue
            val (resolvedVoice, resolvedStyle) = resolveAssignment(playbackState, roleChunk)
            val previous = tail
            val deferred = CoroutineScope(currentCoroutineContext()).async(Dispatchers.IO) {
                previous?.await()
                try {
                    Result.success(
                        synthesizeParagraph(
                            engine,
                            roleChunk.text,
                            resolvedVoice,
                            resolvedStyle,
                            key.paragraphIndex,
                            voiceConservative[resolvedVoice] ?: false,
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
            audioCache[key] = deferred
            tail = deferred
        }
        return tail
    }

    private fun pageProvider(
        chapters: List<TxtChapter>,
        playbackState: PlaybackState
    ): (Int) -> List<TxtPage> = { chapterIndex -> pagesForPlayback(chapters, chapterIndex, playbackState) }

    private fun chunkKeysForPlayback(
        chapters: List<TxtChapter>,
        position: PlaybackPosition,
        playbackState: PlaybackState,
        startParagraphIndex: Int
    ): List<Pair<ChunkKey, ReaderPlaybackPlanner.RoleChunk>> {
        // 角色开启：按旁白/对话切分；关闭：退化为单 NARRATION（与历史 chunking 完全一致）。
        return if (playbackState.roleEnabled) {
            ReaderPlaybackPlanner.chunkKeysForPlaybackRoleAware(
                chapters = chapters,
                position = position,
                startParagraphIndex = startParagraphIndex,
                pageTargetLength = playbackState.pageTargetLength,
                pagesForChapter = pageProvider(chapters, playbackState)
            )
        } else {
            ReaderPlaybackPlanner.chunkKeysForPlayback(
                chapters = chapters,
                position = position,
                startParagraphIndex = startParagraphIndex,
                pageTargetLength = playbackState.pageTargetLength,
                pagesForChapter = pageProvider(chapters, playbackState)
            ).map { (key, text) ->
                key to ReaderPlaybackPlanner.RoleChunk(SpeechRole.NARRATION, null, text)
            }
        }
    }

    /** 解析片段应使用的音色与风格。voice 经 [RoleSegmenter.voiceFor]（已测）；风格按槽位取，未设回落主风格。 */
    private fun resolveAssignment(
        playbackState: PlaybackState,
        chunk: ReaderPlaybackPlanner.RoleChunk
    ): Pair<String, String?> {
        val profile = playbackState.roleProfile
        val voice = RoleSegmenter.voiceFor(
            role = chunk.role,
            character = chunk.character,
            narrationVoice = profile.narration.voice,
            dialogueVoice = profile.dialogue.voice,
            characterVoices = profile.characters.mapNotNull { (k, v) -> v.voice?.let { k to it } }.toMap(),
            fallback = playbackState.voice
        )
        val style = when (chunk.role) {
            SpeechRole.NARRATION -> profile.narration.style
            SpeechRole.DIALOGUE -> chunk.character?.let { profile.characters[it]?.style } ?: profile.dialogue.style
        } ?: playbackState.style
        return voice to style
    }

    /**
     * 预取所有可能被用到的音色（默认 + 旁白 + 对话 + 各角色）的"是否克隆/设计"标记。
     * 克隆/设计音色需节流，且现在不同片段可能用不同音色，故按音色名查一次缓存。
     */
    private suspend fun buildVoiceConservativeMap(
        playbackState: PlaybackState,
        db: AppDatabase,
        defaultConservative: Boolean
    ): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val profile = playbackState.roleProfile
        val names = buildSet {
            add(playbackState.voice)
            profile.narration.voice?.let { add(it) }
            profile.dialogue.voice?.let { add(it) }
            addAll(profile.characters.values.mapNotNull { v -> v.voice })
        }
        names.associateWith { name ->
            if (name == playbackState.voice) defaultConservative
            else db.voiceDao().getVoiceByName(name)?.let { it.type == "clone" || it.type == "design" } ?: false
        }
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
        val audioData = com.voxengine.util.RetryPolicy.withRetry(
            retryCount = retryCount,
            baseDelayMs = retryBaseDelayMs,
            beforeAttempt = { if (conservativeSynthesis) throttleConservativeSynthesis(conservativeRequestIntervalMs) },
            onRetry = { attempt, error ->
                LogManager.appendLog("W", TAG, "Paragraph $paragraphIndex synthesis retry $attempt: ${error.message}")
            },
            block = { engine.synthesize(paragraph, voice, style).audioData }
        )
        return AudioChunk(paragraphIndex, audioData)
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
        return ReaderPlaybackPlanner.normalizePosition(
            chapters = chapters,
            position = position,
            pageTargetLength = playbackState.pageTargetLength,
            pagesForChapter = pageProvider(chapters, playbackState)
        )
    }

    private fun nextPosition(chapters: List<TxtChapter>, position: PlaybackPosition): PlaybackPosition? {
        val playbackState = state ?: return null
        return ReaderPlaybackPlanner.nextPosition(
            chapters = chapters,
            position = position,
            pageTargetLength = playbackState.pageTargetLength,
            pagesForChapter = pageProvider(chapters, playbackState)
        )
    }

    private suspend fun playAudioChunk(wavData: ByteArray) = withContext(Dispatchers.IO) {
        val sampleRate = AudioUtils.getWavSampleRate(wavData)
        val channelCount = AudioUtils.getWavChannelCount(wavData)
        val bitsPerSample = AudioUtils.getWavBitsPerSample(wavData)
        val pcmData = AudioUtils.extractPcmData(wavData)
        if (pcmData.isEmpty()) throw IllegalArgumentException("音频数据为空")
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
            val written = track.write(pcmData, 0, pcmData.size)
            if (written != pcmData.size) {
                throw IllegalStateException("AudioTrack write incomplete: " + written + "/" + pcmData.size)
            }
            val speed = state?.speed ?: 1.0f
            if (speed > 0f && kotlin.math.abs(speed - 1.0f) > 0.01f) {
                runCatching { track.playbackParams = track.playbackParams.setSpeed(speed) }
            }
            track.play()
            val playStartedAt = System.currentTimeMillis()
            while (currentCoroutineContext().isActive) {
                val playbackHead = runCatching { track.playbackHeadPosition }.getOrDefault(frameCount)
                if (playbackHead >= frameCount) break
                val playState = runCatching { track.playState }.getOrDefault(AudioTrack.PLAYSTATE_STOPPED)
                val isStarting = playbackHead == 0 && System.currentTimeMillis() - playStartedAt < AUDIO_START_GRACE_MS
                if (playState != AudioTrack.PLAYSTATE_PLAYING && !isPaused && !isStarting) {
                    throw IllegalStateException("AudioTrack stopped before completion: state=" + playState + " head=" + playbackHead + "/" + frameCount)
                }
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
        publishPlaybackState(true)
    }

    private fun stopPlayback(releaseService: Boolean = true) {
        playbackJob?.cancel()
        currentTrack?.releaseSafely()
        playbackJob = null
        currentTrack = null
        isPaused = false
        publishPlaybackState(false)
        state = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (releaseService) stopSelf()
    }

    private fun updateNotification(text: String, isPlaying: Boolean) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text, isPlaying))
    }

    private fun sendProgress(chapterIndex: Int, pageIndex: Int, paragraphIndex: Int) {
        val playbackState = state ?: return
        playbackState.chapterIndex = chapterIndex
        playbackState.pageIndex = pageIndex
        playbackState.paragraphIndex = paragraphIndex
        playbackSnapshotRef.set(
            PlaybackSnapshot(
                uri = playbackState.uri,
                chapterIndex = chapterIndex,
                pageIndex = pageIndex,
                paragraphIndex = paragraphIndex,
                isListening = true,
                isPaused = isPaused
            )
        )
        sendBroadcast(
            Intent(ACTION_PROGRESS)
                .setPackage(packageName)
                .putExtra(EXTRA_URI, playbackState.uri)
                .putExtra(EXTRA_CHAPTER_INDEX, chapterIndex)
                .putExtra(EXTRA_PAGE_INDEX, pageIndex)
                .putExtra(EXTRA_PARAGRAPH_INDEX, paragraphIndex)
        )
    }

    private fun publishPlaybackState(isListening: Boolean) {
        val playbackState = state ?: return
        val snapshot = PlaybackSnapshot(
            uri = playbackState.uri,
            chapterIndex = playbackState.chapterIndex,
            pageIndex = playbackState.pageIndex,
            paragraphIndex = playbackState.paragraphIndex,
            isListening = isListening,
            isPaused = isListening && isPaused
        )
        if (isListening) {
            playbackSnapshotRef.set(snapshot)
        } else {
            playbackSnapshotRef.set(null)
        }
        sendBroadcast(
            Intent(ACTION_PLAYBACK_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_URI, snapshot.uri)
                .putExtra(EXTRA_CHAPTER_INDEX, snapshot.chapterIndex)
                .putExtra(EXTRA_PAGE_INDEX, snapshot.pageIndex)
                .putExtra(EXTRA_PARAGRAPH_INDEX, snapshot.paragraphIndex)
                .putExtra(EXTRA_IS_LISTENING, snapshot.isListening)
                .putExtra(EXTRA_IS_PAUSED, snapshot.isPaused)
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
        val retryBaseDelayMs: Long,
        val roleEnabled: Boolean = false,
        val roleProfile: RoleProfile = RoleProfile(),
        var speed: Float = 1.0f
    )

    private data class AudioChunk(val paragraphIndex: Int, val audioData: ByteArray)

    companion object {
        const val ACTION_START = "com.voxengine.reader.START"
        const val ACTION_PAUSE = "com.voxengine.reader.PAUSE"
        const val ACTION_RESUME = "com.voxengine.reader.RESUME"
        const val ACTION_STOP = "com.voxengine.reader.STOP"
        const val ACTION_PREVIOUS_CHAPTER = "com.voxengine.reader.PREVIOUS_CHAPTER"
        const val ACTION_NEXT_CHAPTER = "com.voxengine.reader.NEXT_CHAPTER"
        const val ACTION_PROGRESS = "com.voxengine.reader.PROGRESS"

        const val ACTION_PLAYBACK_STATE = "com.voxengine.reader.PLAYBACK_STATE"
        const val EXTRA_IS_LISTENING = "is_listening"
        const val EXTRA_IS_PAUSED = "is_paused"

        private val playbackSnapshotRef = java.util.concurrent.atomic.AtomicReference<PlaybackSnapshot?>(null)

        fun getPlaybackSnapshot(uri: String? = null): PlaybackSnapshot? =
            playbackSnapshotRef.get()?.takeIf { uri == null || it.uri == uri }

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
        const val EXTRA_ROLE_ENABLED = "role_enabled"
        const val EXTRA_ROLE_PROFILE_JSON = "role_profile_json"

        private const val TAG = "ReaderPlaybackService"
        private const val DEFAULT_CONSERVATIVE_REQUEST_INTERVAL_MS = 5000
        private const val AUDIO_START_GRACE_MS = 1000L
        private const val DEFAULT_RETRY_COUNT = 3
        private const val DEFAULT_RETRY_BASE_DELAY_MS = 2000
        private const val CHANNEL_ID = "reader_playback"
        private const val NOTIFICATION_ID = 2001
    }
}
