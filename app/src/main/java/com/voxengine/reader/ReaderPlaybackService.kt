package com.voxengine.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as PlatformPlaybackState
import android.net.Uri
import android.os.IBinder
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

data class PlaybackSnapshot(
    val uri: String,
    val chapterIndex: Int,
    val pageIndex: Int,
    val paragraphIndex: Int,
    val isListening: Boolean,
    val isPaused: Boolean,
    val isCaching: Boolean = false,
    val chapterOffset: Int? = null
)

class ReaderPlaybackService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playbackJob: Job? = null
    private var currentTrack: AudioTrack? = null
    @Volatile private var isPaused = false
    private var state: PlaybackState? = null
    private val conservativeThrottle = com.voxengine.util.ConservativeThrottle()
    // 非 conservative 音色的预取并发上限（clone/design 仍串行 + 节流）。
    private val prefetchSemaphore = Semaphore(DEFAULT_PREFETCH_CONCURRENCY)
    // 复用 STREAM AudioTrack，避免每段 create/release。
    private var streamTrack: AudioTrack? = null
    private var streamSampleRate: Int = 0
    private var streamChannelConfig: Int = 0
    private var streamEncoding: Int = 0
    private var lastProgressPersistAt: Long = 0L
    private val fallbackPagesByChapter = object : LinkedHashMap<Int, List<TxtPage>>(
        MAX_FALLBACK_PAGE_CHAPTERS,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<TxtPage>>?): Boolean =
            size > MAX_FALLBACK_PAGE_CHAPTERS
    }
    // MediaSession 接收耳机/手表/蓝牙的媒体按键（播放/暂停/上下章），系统按活动会话路由。
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
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
        mediaSession?.run {
            isActive = false
            release()
        }
        mediaSession = null
        serviceScope.cancel()
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
            roleProfile = roleProfile,
            synthesisOptions = ReaderSynthesisOptions.parse(intent.getStringExtra(EXTRA_SYNTHESIS_OPTIONS)),
            cacheOnly = intent.getBooleanExtra(EXTRA_CACHE_ONLY, false)
        )
        playbackJob?.cancel()
        currentTrack = null
        releaseStreamTrack()
        isPaused = false
        lastProgressPersistAt = 0L
        fallbackPagesByChapter.clear()
        startForeground(NOTIFICATION_ID, buildNotification("准备播放", isPlaying = true))
        playbackJob = serviceScope.launch { runPlayback() }
        publishPlaybackState(true)
        updateMediaMetadata(state?.title ?: "VoxEngine 听书")
        updateMediaPlaybackState()
    }

    private suspend fun runPlayback() {
        try {
            runPlaybackSafely()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.appendLog("E", TAG, "Reader playback failed: ${e.message}")
            updateNotification("听书失败: ${com.voxengine.util.TtsErrors.friendly(e)}", false)
            finishPlayback()
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
            finishPlayback()
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
            finishPlayback()
            return
        }
        playbackState.chapterCount = chapters.size
        // 分角色开启时，旁白/对话/各角色音色可能各异；预取所有可能用到的音色的类型，决定是否需要节流。
        val voiceConservative = buildVoiceConservativeMap(playbackState, db)

        val firstChapter = playbackState.chapterIndex.coerceIn(chapters.indices)
        val cacheEnd = minOf(chapters.size, firstChapter + playbackState.synthesisOptions.cacheChapters)
        val initialPages = pagesForPlayback(chapters, firstChapter, playbackState)
        val startOffset = if (playbackState.cacheOnly) 0 else ReaderSpeechPlanner.offsetFor(
            initialPages, playbackState.pageIndex, playbackState.paragraphIndex
        )
        var completedChapters = 0
        for (chapterIndex in firstChapter until (if (playbackState.cacheOnly) cacheEnd else chapters.size)) {
            if (!playbackState.cacheOnly && playbackState.stopAfterChapters > 0 && completedChapters >= playbackState.stopAfterChapters) break
            val chapter = chapters[chapterIndex]
            val allChunks = ReaderSpeechPlanner.build(chapter.content, playbackState.roleEnabled, playbackState.roleProfile, playbackState.synthesisOptions)
            val offset = if (chapterIndex == firstChapter) startOffset else 0
            val chunks = allChunks.filter { it.end > offset }
            coroutineScope {
                val audio = mutableMapOf<Int, Deferred<Result<AudioChunk>>>()
                var tail: Deferred<Result<AudioChunk>>? = null
                fun schedule(start: Int) {
                    val end = ReaderSpeechPlanner.windowEnd(chunks, start, playbackState.synthesisOptions.bufferPages * playbackState.pageTargetLength)
                    for (index in start until end) {
                        if (index in audio) continue
                        val chunk = chunks[index]
                        val (voice, style) = resolveAssignment(playbackState, chunk.speech)
                        val conservative = voiceConservative[voice] == true
                        val previous = tail
                        val deferred = async(Dispatchers.IO) {
                            if (conservative) previous?.await()
                            try {
                                prefetchSemaphore.withPermit {
                                    Result.success(synthesizeParagraph(engine, chunk.speech.text, voice, style,
                                        chunk.paragraphIndex, conservative, playbackState.conservativeRequestIntervalMs,
                                        playbackState.retryCount, playbackState.retryBaseDelayMs, chunk.context))
                                }
                            } catch (e: CancellationException) { throw e
                            } catch (e: Exception) { Result.failure(e) }
                        }
                        audio[index] = deferred
                        tail = deferred
                    }
                }
                try {
                    schedule(0)
                    for ((index, chunk) in chunks.withIndex()) {
                        while (isPaused && currentCoroutineContext().isActive) delay(150)
                        currentCoroutineContext().ensureActive()
                        if (!playbackState.cacheOnly && playbackState.stopAtMillis > 0 && System.currentTimeMillis() >= playbackState.stopAtMillis) break
                        val pages = pagesForPlayback(chapters, chapterIndex, playbackState)
                        val (pageIndex, paragraphIndex) = ReaderSpeechPlanner.displayPosition(pages, chunk.start)
                        sendProgress(chapterIndex, pageIndex, paragraphIndex, chunk.start)
                        updateNotification(if (playbackState.cacheOnly) {
                            "预缓存 ${chapterIndex - firstChapter + 1}/${cacheEnd - firstChapter}章 · ${index + 1}/${chunks.size}段"
                        } else "${chapter.title} · 第${pageIndex + 1}页", true)
                        val preparedResult = audio.remove(index)?.await() ?: error("缺少待合成片段")
                        var prepared = preparedResult.getOrElse { error ->
                            if (playbackState.cacheOnly) throw error
                            LogManager.appendLog("W", TAG, "Prefetch failed; retrying current chunk: ${error.message}")
                            val (voice, style) = resolveAssignment(playbackState, chunk.speech)
                            synthesizeParagraph(engine, chunk.speech.text, voice, style, chunk.paragraphIndex,
                                voiceConservative[voice] == true, playbackState.conservativeRequestIntervalMs,
                                playbackState.retryCount, playbackState.retryBaseDelayMs, chunk.context)
                        }
                        // Refill before playback, allowing network work to overlap this audio.
                        schedule(index + 1)
                        if (playbackState.cacheOnly) {
                            check(prepared.persisted) { "音频无法保存到磁盘，请检查剩余空间后重新预缓存" }
                            continue
                        }
                        if (index == 0 && offset > chunk.start) {
                            // Seeking inside a cached chunk synthesizes only the selected suffix.
                            val skip = ((offset - chunk.start).toLong() * chunk.speech.text.length /
                                (chunk.end - chunk.start).coerceAtLeast(1)).toInt().coerceIn(0, chunk.speech.text.lastIndex)
                            val (voice, style) = resolveAssignment(playbackState, chunk.speech)
                            prepared = synthesizeParagraph(engine, chunk.speech.text.drop(skip), voice, style,
                                chunk.paragraphIndex, voiceConservative[voice] == true,
                                playbackState.conservativeRequestIntervalMs, playbackState.retryCount,
                                playbackState.retryBaseDelayMs, chunk.context)
                        }
                        playAudioChunk(prepared.audioData)
                        maybePersistProgress(db, playbackState.uri, chapterIndex, pageIndex, paragraphIndex)
                        if (playbackState.gapMs > 0 && chunks.getOrNull(index + 1)?.paragraphIndex != chunk.paragraphIndex) delay(playbackState.gapMs)
                    }
                } finally {
                    audio.values.forEach { it.cancel() }
                    audio.clear()
                }
            }
            if (!playbackState.cacheOnly && playbackState.stopAtMillis > 0 && System.currentTimeMillis() >= playbackState.stopAtMillis) break
            completedChapters++
            if (!playbackState.cacheOnly) {
                if (chapterIndex < chapters.lastIndex) persistProgress(db, playbackState.uri, chapterIndex + 1, 0, 0)
                else {
                    val pages = pagesForPlayback(chapters, chapterIndex, playbackState)
                    persistProgress(db, playbackState.uri, chapterIndex, pages.lastIndex.coerceAtLeast(0), pages.lastOrNull()?.paragraphs?.size ?: 0)
                }
            }
        }
        updateNotification(if (playbackState.cacheOnly) "已缓存 $completedChapters 章，可直接听书" else "听书已结束", false)
        finishPlayback()
    }

    private fun finishPlayback() {
        publishPlaybackState(false)
        state = null
        playbackJob = null
        isPaused = false
        currentTrack = null
        releaseStreamTrack()
        fallbackPagesByChapter.clear()
        updateMediaPlaybackState()
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    /** 解析片段应使用的音色与风格。voice 经 [RoleSegmenter.voiceFor]（已测）；风格按槽位取，未设回落主风格。 */
    private fun resolveAssignment(
        playbackState: PlaybackState,
        chunk: ReaderPlaybackPlanner.RoleChunk
    ): Pair<String, String?> {
        val profile = playbackState.roleProfile
        val characterAssignment = chunk.character?.let { profile.characters[it] }
        val voice = RoleSegmenter.voiceForResolvedCharacter(
            role = chunk.role,
            narrationVoice = profile.narration.voice,
            dialogueVoice = profile.dialogue.voice,
            characterVoice = characterAssignment?.voice,
            fallback = playbackState.voice
        )
        val style = when (chunk.role) {
            SpeechRole.NARRATION -> profile.narration.style
            SpeechRole.DIALOGUE -> characterAssignment?.style ?: profile.dialogue.style
        } ?: playbackState.style
        return voice to style
    }

    /**
     * 预取所有可能被用到的音色（默认 + 旁白 + 对话 + 各角色）的"是否克隆/设计"标记。
     * 克隆/设计音色需节流，且现在不同片段可能用不同音色，故按音色名查一次缓存。
     */
    private suspend fun buildVoiceConservativeMap(
        playbackState: PlaybackState,
        db: AppDatabase
    ): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val profile = playbackState.roleProfile
        val names = buildSet {
            add(playbackState.voice)
            profile.narration.voice?.let { add(it) }
            profile.dialogue.voice?.let { add(it) }
            for (assignment in profile.characters.values) assignment.voice?.let { add(it) }
        }
        val typesByName = db.voiceDao()
            .getVoiceTypesByEngineAndNames(playbackState.engineId, names.toList())
            .associate { it.name to it.type }
        names.associateWith { name ->
            typesByName[name] == "clone" || typesByName[name] == "design"
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
        retryBaseDelayMs: Long,
        context: String? = null
    ): AudioChunk {
        if (engine is com.voxengine.engine.mimo.MiMoEngine) {
            engine.getCachedSynthesis(paragraph, voice, style, context)?.let { return AudioChunk(paragraphIndex, it.audioData, it.persisted) }
        }
        val result = com.voxengine.util.RetryPolicy.withRetry(
            retryCount = retryCount,
            baseDelayMs = retryBaseDelayMs,
            beforeAttempt = { if (conservativeSynthesis) conservativeThrottle.waitTurn(conservativeRequestIntervalMs) },
            onRetry = { attempt, error ->
                LogManager.appendLog("W", TAG, "Paragraph $paragraphIndex synthesis retry $attempt: ${error.message}")
            },
            block = { engine.synthesize(paragraph, voice, style, context = context) }
        )
        return AudioChunk(paragraphIndex, result.audioData, result.persisted)
    }

    private fun pagesForPlayback(
        chapters: List<TxtChapter>,
        chapterIndex: Int,
        playbackState: PlaybackState
    ): List<TxtPage> = ReaderMeasuredPageCache.getChapterPages(playbackState.uri, chapterIndex)
        ?: fallbackPagesByChapter.getOrPut(chapterIndex) {
            TxtNovelParser.paginate(chapters[chapterIndex].content, playbackState.pageTargetLength).also {
                LogManager.appendLog("W", TAG, "Reader fallback pagination used: chapter=$chapterIndex pages=${it.size}")
            }
        }

    private suspend fun playAudioChunk(wavData: ByteArray) = withContext(Dispatchers.IO) {
        val wav = AudioUtils.parseWav(wavData)
        val sampleRate = wav.sampleRate
        val channelCount = wav.channelCount
        val bitsPerSample = wav.bitsPerSample
        val pcmData = wav.pcmData
        if (pcmData.isEmpty()) throw IllegalArgumentException("音频数据为空")
        val channelConfig = when (channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> throw IllegalArgumentException("不支持的 WAV 声道数: $channelCount")
        }
        val encoding = when (bitsPerSample) {
            8 -> AudioFormat.ENCODING_PCM_8BIT
            16 -> AudioFormat.ENCODING_PCM_16BIT
            else -> throw IllegalArgumentException("不支持的 WAV 位深: $bitsPerSample")
        }
        val bytesPerFrame = channelCount * (bitsPerSample / 8).coerceAtLeast(1)
        val frameCount = if (bytesPerFrame > 0) pcmData.size / bytesPerFrame else pcmData.size

        val track = obtainStreamTrack(sampleRate, channelConfig, encoding)
        currentTrack = track
        try {
            val speed = state?.speed ?: 1.0f
            if (speed > 0f && kotlin.math.abs(speed - 1.0f) > 0.01f) {
                runCatching { track.playbackParams = track.playbackParams.setSpeed(speed) }
            }
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING && !isPaused) {
                runCatching { track.play() }
            }
            // 写之前记录播放头：STREAM 边写边播，写完再记会少等、截断尾音。
            val startHead = runCatching { track.playbackHeadPosition }.getOrDefault(0)
            val targetHead = startHead + frameCount
            var offset = 0
            while (offset < pcmData.size && currentCoroutineContext().isActive) {
                if (isPaused) {
                    runCatching { track.pause() }
                    while (currentCoroutineContext().isActive && isPaused) delay(100)
                    if (currentCoroutineContext().isActive) runCatching { track.play() }
                }
                val written = track.write(pcmData, offset, pcmData.size - offset)
                if (written < 0) throw IllegalStateException("AudioTrack write error: $written")
                if (written == 0) {
                    delay(10)
                    continue
                }
                offset += written
            }
            val playStartedAt = System.currentTimeMillis()
            while (currentCoroutineContext().isActive) {
                val playbackHead = runCatching { track.playbackHeadPosition }.getOrDefault(targetHead)
                // playbackHeadPosition 为无符号 32 位累加；用差值处理回绕。
                val played = playbackHead - startHead
                if (played >= frameCount) break
                val playState = runCatching { track.playState }.getOrDefault(AudioTrack.PLAYSTATE_STOPPED)
                val isStarting = played <= 0 && System.currentTimeMillis() - playStartedAt < AUDIO_START_GRACE_MS
                if (playState != AudioTrack.PLAYSTATE_PLAYING && !isPaused && !isStarting) {
                    throw IllegalStateException(
                        "AudioTrack stopped before completion: state=" + playState +
                            " played=" + played + "/" + frameCount
                    )
                }
                if (isPaused) {
                    runCatching { track.pause() }
                    while (currentCoroutineContext().isActive && isPaused) delay(100)
                    if (currentCoroutineContext().isActive) runCatching { track.play() }
                }
                delay(50)
            }
        } finally {
            // 不 release：下一段复用同一 STREAM track；停止/换章时 releaseStreamTrack。
            if (currentTrack === track) currentTrack = null
        }
    }

    private fun obtainStreamTrack(
        sampleRate: Int,
        channelConfig: Int,
        encoding: Int
    ): AudioTrack {
        val existing = streamTrack
        if (existing != null &&
            streamSampleRate == sampleRate &&
            streamChannelConfig == channelConfig &&
            streamEncoding == encoding &&
            existing.state == AudioTrack.STATE_INITIALIZED
        ) {
            // 段间不 flush：上一段已等播放头播完，直接续写避免卡顿/爆音。
            return existing
        }
        releaseStreamTrack()
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        // STREAM 缓冲取 min 与约 0.5s 数据量的较大者，兼顾低延迟与写不阻塞。
        val halfSecond = sampleRate * (if (channelConfig == AudioFormat.CHANNEL_OUT_STEREO) 4 else 2) / 2
        val bufferSize = maxOf(minBuffer, halfSecond, 4096)
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
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        streamTrack = track
        streamSampleRate = sampleRate
        streamChannelConfig = channelConfig
        streamEncoding = encoding
        return track
    }

    private fun releaseStreamTrack() {
        streamTrack?.releaseSafely()
        streamTrack = null
        streamSampleRate = 0
        streamChannelConfig = 0
        streamEncoding = 0
    }

    private suspend fun maybePersistProgress(
        db: AppDatabase,
        uri: String,
        chapterIndex: Int,
        pageIndex: Int,
        paragraphIndex: Int
    ) {
        val now = System.currentTimeMillis()
        if (now - lastProgressPersistAt < PROGRESS_PERSIST_INTERVAL_MS) return
        persistProgress(db, uri, chapterIndex, pageIndex, paragraphIndex)
    }

    private suspend fun persistProgress(
        db: AppDatabase,
        uri: String,
        chapterIndex: Int,
        pageIndex: Int,
        paragraphIndex: Int
    ) {
        withContext(Dispatchers.IO) {
            db.readerBookDao().updateProgress(uri, chapterIndex, pageIndex, paragraphIndex)
        }
        lastProgressPersistAt = System.currentTimeMillis()
    }

    private fun pausePlayback() {
        if (state == null || playbackJob == null) return
        isPaused = true
        currentTrack?.let { track -> runCatching { track.pause() } }
        updateNotification("已暂停", false)
        publishPlaybackState(true)
        updateMediaPlaybackState()
    }

    private fun resumePlayback() {
        if (state == null || playbackJob == null) return
        isPaused = false
        currentTrack?.let { track -> runCatching { track.play() } }
        updateNotification("播放中", true)
        publishPlaybackState(true)
        updateMediaPlaybackState()
    }

    private fun moveChapter(delta: Int) {
        val playbackState = state ?: return
        val targetChapter = ReaderPlaybackPlanner.targetChapter(
            playbackState.chapterIndex,
            delta,
            playbackState.chapterCount
        ) ?: return
        playbackState.chapterIndex = targetChapter
        playbackState.pageIndex = 0
        playbackState.paragraphIndex = 0
        playbackJob?.cancel()
        currentTrack = null
        releaseStreamTrack()
        isPaused = false
        lastProgressPersistAt = 0L
        playbackJob = serviceScope.launch { runPlayback() }
        publishPlaybackState(true)
        updateMediaPlaybackState()
    }

    private fun stopPlayback(releaseService: Boolean = true) {
        playbackJob?.cancel()
        currentTrack = null
        releaseStreamTrack()
        playbackJob = null
        isPaused = false
        publishPlaybackState(false)
        state = null
        fallbackPagesByChapter.clear()
        updateMediaPlaybackState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (releaseService) stopSelf()
    }

    private fun updateNotification(text: String, isPlaying: Boolean) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text, isPlaying))
    }

    private fun sendProgress(chapterIndex: Int, pageIndex: Int, paragraphIndex: Int, chapterOffset: Int? = null) {
        val playbackState = state ?: return
        playbackState.chapterIndex = chapterIndex
        playbackState.pageIndex = pageIndex
        playbackState.paragraphIndex = paragraphIndex
        playbackState.chapterOffset = chapterOffset
        playbackSnapshotRef.set(
            PlaybackSnapshot(
                uri = playbackState.uri,
                chapterIndex = chapterIndex,
                pageIndex = pageIndex,
                paragraphIndex = paragraphIndex,
                isListening = true,
                isPaused = isPaused,
                isCaching = playbackState.cacheOnly,
                chapterOffset = chapterOffset
            )
        )
        sendBroadcast(
            Intent(ACTION_PROGRESS)
                .setPackage(packageName)
                .putExtra(EXTRA_URI, playbackState.uri)
                .putExtra(EXTRA_CACHE_ONLY, playbackState.cacheOnly)
                .putExtra(EXTRA_CHAPTER_OFFSET, chapterOffset ?: -1)
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
            isPaused = isListening && isPaused,
            isCaching = playbackState.cacheOnly,
            chapterOffset = playbackState.chapterOffset
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
                .putExtra(EXTRA_CACHE_ONLY, snapshot.isCaching)
                .putExtra(EXTRA_CHAPTER_OFFSET, snapshot.chapterOffset ?: -1)
        )
    }

    private fun buildNotification(text: String, isPlaying: Boolean): Notification {
        val playPauseAction = mediaAction(
            if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
            if (isPaused) "继续" else "暂停",
            serviceIntent(if (isPaused) ACTION_RESUME else ACTION_PAUSE, 1)
        )
        return Notification.Builder(this, CHANNEL_ID)
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
            .addAction(playPauseAction)
            .addAction(mediaAction(android.R.drawable.ic_media_previous, "上一章", serviceIntent(ACTION_PREVIOUS_CHAPTER, 2)))
            .addAction(mediaAction(android.R.drawable.ic_media_next, "下一章", serviceIntent(ACTION_NEXT_CHAPTER, 3)))
            .addAction(mediaAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", serviceIntent(ACTION_STOP, 4)))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setPriority(Notification.PRIORITY_DEFAULT)
            .build()
    }

    private fun mediaAction(icon: Int, title: String, intent: PendingIntent): Notification.Action =
        Notification.Action.Builder(icon, title, intent).build()

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, ReaderPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_reader),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.channel_reader_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * 建立 MediaSession：耳机/手表/蓝牙的媒体按键由系统路由到当前活动会话。
     * 会话回调映射到既有的暂停/继续/上下章/停止逻辑，与通知栏按钮走同一路径。
     * 会话在 onCreate 建好并保持活动；通知通过 MediaStyle 携带其 token，
     * 系统据此把媒体按键投递到本会话并在锁屏/手表上显示控件。
     */
    private fun setupMediaSession() {
        val session = MediaSession(this, TAG)
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() = resumePlayback()
                override fun onPause() = pausePlayback()
                override fun onStop() = stopPlayback()
                override fun onSkipToNext() = moveChapter(1)
                override fun onSkipToPrevious() = moveChapter(-1)
            }
        )
        session.isActive = true
        mediaSession = session
        updateMediaPlaybackState()
    }

    /** 同步 PlaybackState：耳机/手表据此把按键路由到本会话，并显示正确的播放/暂停图标。 */
    private fun updateMediaPlaybackState() {
        val session = mediaSession ?: return
        val stateCode = when {
            state == null || playbackJob == null -> PlatformPlaybackState.STATE_STOPPED
            isPaused -> PlatformPlaybackState.STATE_PAUSED
            else -> PlatformPlaybackState.STATE_PLAYING
        }
        session.setPlaybackState(
            PlatformPlaybackState.Builder()
                .setActions(
                    PlatformPlaybackState.ACTION_PLAY or
                        PlatformPlaybackState.ACTION_PAUSE or
                        PlatformPlaybackState.ACTION_PLAY_PAUSE or
                        PlatformPlaybackState.ACTION_STOP or
                        PlatformPlaybackState.ACTION_SKIP_TO_NEXT or
                        PlatformPlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(stateCode, PlatformPlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
        )
    }

    /** 设置书名，供锁屏/手表的媒体控件展示。 */
    private fun updateMediaMetadata(title: String) {
        mediaSession?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .build()
        )
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
        val synthesisOptions: ReaderSynthesisOptions = ReaderSynthesisOptions(),
        val cacheOnly: Boolean = false,
        var speed: Float = 1.0f,
        var chapterCount: Int = 0,
        var chapterOffset: Int? = null
    )

    private data class AudioChunk(val paragraphIndex: Int, val audioData: ByteArray, val persisted: Boolean)

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

        const val EXTRA_CHAPTER_OFFSET = "chapter_offset"
        const val EXTRA_SYNTHESIS_OPTIONS = "synthesis_options"
        const val EXTRA_CACHE_ONLY = "cache_only"
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
        private const val DEFAULT_PREFETCH_CONCURRENCY = 3
        private const val PROGRESS_PERSIST_INTERVAL_MS = 3000L
        private const val MAX_FALLBACK_PAGE_CHAPTERS = 3
        private const val CHANNEL_ID = "reader_playback"
        private const val NOTIFICATION_ID = 2001
    }
}
