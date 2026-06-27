package com.voxengine.ui.screens

import android.app.Application
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.voxengine.data.AppDatabase
import com.voxengine.data.ReaderBookEntity
import com.voxengine.data.ReaderChapterEntity
import com.voxengine.data.SettingsRepository
import com.voxengine.engine.EngineRegistry
import com.voxengine.engine.VoiceInfo
import com.voxengine.reader.PlaybackSnapshot
import com.voxengine.reader.ReaderChapterCache
import com.voxengine.reader.ReaderMeasuredPageCache
import com.voxengine.reader.ReaderPlaybackService
import com.voxengine.reader.TxtChapter
import com.voxengine.reader.TxtNovelParser
import com.voxengine.reader.TxtPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/**
 * 听书界面的状态与业务逻辑。把原先散落在 ReaderScreen composable 里的解析、翻页、进度、
 * 听书服务控制、播放快照同步、设置项等迁出，使其在配置变更（旋屏）后存活、可复用、可测。
 *
 * 仅承载与组合无关的状态；依赖测量视口的分页（TextMeasurer/BoxWithConstraints）仍由 UI 完成，
 * 测得结果经 [onPagesMeasured] 回灌。菜单显隐等纯 UI 状态保留在 composable。
 */
class ReaderViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getDatabase(app)
    private val settings = SettingsRepository(app)

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    val books: StateFlow<List<ReaderBookEntity>> = db.readerBookDao().getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var currentEngineId: String = "mimo"
    private var apiKey: String = ""

    init {
        viewModelScope.launch { settings.currentEngine.collect { onEngineChanged(it) } }
        viewModelScope.launch { settings.apiKey.collect { apiKey = it; recomputeConfigured() } }
        viewModelScope.launch {
            settings.defaultVoice.collect { voice ->
                if (voice.isNotBlank()) _uiState.update { it.copy(selectedVoiceId = voice) }
                reconcileSelectedVoice()
            }
        }
        viewModelScope.launch {
            settings.defaultStyle.collect { style ->
                _uiState.update { it.copy(selectedStyle = style.takeIf { s -> s != "无" }.orEmpty()) }
            }
        }
        // 设置项：持久值变化时同步到 UiState（作为草稿值，编辑后由对应 commit 持久化）。
        viewModelScope.launch { settings.readerParagraphGapMs.collect { v -> _uiState.update { it.copy(readerGapMs = v) } } }
        viewModelScope.launch { settings.readerSleepMinutes.collect { v -> _uiState.update { it.copy(readerSleepMinutes = v) } } }
        viewModelScope.launch { settings.readerStopAfterChapters.collect { v -> _uiState.update { it.copy(readerStopAfterChapters = v) } } }
        viewModelScope.launch { settings.readerConservativeRequestIntervalMs.collect { v -> _uiState.update { it.copy(conservativeRequestIntervalMs = v) } } }
        viewModelScope.launch { settings.readerRetryCount.collect { v -> _uiState.update { it.copy(retryCount = v) } } }
        viewModelScope.launch { settings.readerRetryBaseDelayMs.collect { v -> _uiState.update { it.copy(retryBaseDelayMs = v) } } }
        // 分角色朗读配置：持久值变化时同步到 UiState（草稿值，编辑后由 commit 持久化）。
        viewModelScope.launch { settings.readerRoleEnabled.collect { v -> _uiState.update { it.copy(roleEnabled = v) } } }
        viewModelScope.launch {
            settings.readerRoleProfileJson.collect { json ->
                _uiState.update { it.copy(roleProfile = com.voxengine.reader.RoleProfileJson.parse(json)) }
            }
        }
    }

    private suspend fun onEngineChanged(engineId: String) {
        currentEngineId = engineId
        val loaded = runCatching { EngineRegistry.get(engineId)?.getVoices() }.getOrNull() ?: emptyList()
        _uiState.update { it.copy(voices = loaded) }
        reconcileSelectedVoice()
        recomputeConfigured()
    }

    /** Edge 免费无需配置；其余引擎（MiMo）需要 API Key。等价于原 isConfigured()，但不在组合期 runBlocking。 */
    private fun recomputeConfigured() {
        val configured = when (currentEngineId) {
            "edge" -> true
            else -> apiKey.isNotBlank()
        }
        _uiState.update { it.copy(isEngineConfigured = configured) }
    }

    private fun reconcileSelectedVoice() {
        _uiState.update { s ->
            val selected = s.voices.firstOrNull { it.id == s.selectedVoiceId || it.name == s.selectedVoiceId }
            when {
                selected != null -> s.copy(selectedVoiceId = selected.id, selectedVoiceName = selected.name)
                s.voices.isNotEmpty() -> s.copy(selectedVoiceId = s.voices.first().id, selectedVoiceName = s.voices.first().name)
                else -> s
            }
        }
    }

    // ---- 书架 ----

    fun importBooks(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var firstBook: ReaderBookEntity? = null
            uris.forEach { uri ->
                val title = queryDisplayName(uri) ?: "本地小说.txt"
                runCatching {
                    getApplication<Application>().contentResolver
                        .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val book = ReaderBookEntity(uri = uri.toString(), title = title)
                withContext(Dispatchers.IO) {
                    db.withTransaction {
                        db.readerChapterDao().deleteByBookUri(book.uri)
                        db.readerBookDao().upsert(book)
                    }
                    ReaderChapterCache.clearBook(book.uri)
                    ReaderMeasuredPageCache.clearBook(book.uri)
                }
                if (firstBook == null) firstBook = book
            }
            if (_uiState.value.currentBook == null) firstBook?.let { openBook(it) }
        }
    }

    fun deleteBook(book: ReaderBookEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            db.withTransaction {
                db.readerChapterDao().deleteByBookUri(book.uri)
                db.readerBookDao().deleteByUri(book.uri)
            }
            ReaderChapterCache.clearBook(book.uri)
            ReaderMeasuredPageCache.clearBook(book.uri)
        }
    }

    fun openBook(book: ReaderBookEntity) {
        _uiState.update {
            it.copy(currentBook = book, isListening = false, isPaused = false, selectedParagraphIndex = null)
        }
        loadBook()
    }

    fun closeBook() {
        _uiState.update { it.copy(currentBook = null, isListening = false, isPaused = false, selectedParagraphIndex = null) }
    }

    private fun loadBook() {
        val book = _uiState.value.currentBook ?: return
        _uiState.update { it.copy(isLoadingBook = true, statusText = "正在解析 ${book.title}") }
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    ReaderChapterCache.getChapters(book.uri)
                        ?: db.readerChapterDao().getChapters(book.uri)
                            .map { it.toTxtChapter() }
                            .takeIf { it.isNotEmpty() }
                            ?.also { ReaderChapterCache.putChapters(book.uri, it) }
                        ?: run {
                            val bytes = getApplication<Application>().contentResolver
                                .openInputStream(Uri.parse(book.uri))?.use { it.readBytes() }
                                ?: throw FileNotFoundException(book.uri)
                            TxtNovelParser.parse(TxtNovelParser.decode(bytes)).also { parsedChapters ->
                                ReaderChapterCache.putChapters(book.uri, parsedChapters)
                                db.withTransaction {
                                    db.readerChapterDao().deleteByBookUri(book.uri)
                                    db.readerChapterDao().insertAll(
                                        parsedChapters.mapIndexed { index, chapter ->
                                            ReaderChapterEntity.fromTxtChapter(book.uri, index, chapter)
                                        }
                                    )
                                }
                            }
                        }
                }
            }
            parsed.onSuccess { list ->
                val chapterIndex = book.lastChapterIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
                val pages = list.getOrNull(chapterIndex)?.content
                    ?.let { TxtNovelParser.paginate(it, _uiState.value.pageTargetLength) }.orEmpty()
                val pageIndex = book.lastPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                _uiState.update {
                    it.copy(
                        chapters = list,
                        chapterIndex = chapterIndex,
                        pages = pages,
                        pageIndex = pageIndex,
                        selectedParagraphIndex = null,
                        statusText = "已载入 ${list.size} 章",
                        isLoadingBook = false
                    )
                }
                // 解析完成后同步一次播放快照（原 LaunchedEffect(currentBook?.uri, chapters.size)）。
                syncPlaybackSnapshot(ReaderPlaybackService.getPlaybackSnapshot(book.uri))
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        chapters = emptyList(),
                        pages = emptyList(),
                        chapterIndex = 0,
                        pageIndex = 0,
                        statusText = "打开失败: ${error.message}",
                        isLoadingBook = false
                    )
                }
            }
        }
    }

    // ---- 翻页 / 定位 ----

    private fun pagesFor(chapter: Int): List<TxtPage> {
        val state = _uiState.value
        val uri = state.currentBook?.uri
        if (uri != null) ReaderMeasuredPageCache.getChapterPages(uri, chapter)?.let { return it }
        return state.chapters.getOrNull(chapter)?.content
            ?.let { TxtNovelParser.paginate(it, state.pageTargetLength) }.orEmpty()
    }

    fun saveProgress() {
        val state = _uiState.value
        val book = state.currentBook ?: return
        viewModelScope.launch(Dispatchers.IO) {
            db.readerBookDao().updateProgress(book.uri, state.chapterIndex, state.pageIndex, 0)
        }
    }

    fun setPosition(chapter: Int, page: Int, forward: Boolean = true) {
        val state = _uiState.value
        if (chapter !in state.chapters.indices) return
        val nextPages = pagesFor(chapter)
        val nextPageIndex = page.coerceIn(0, (nextPages.size - 1).coerceAtLeast(0))
        val animate = chapter != state.chapterIndex || nextPageIndex != state.pageIndex
        _uiState.update {
            it.copy(
                chapterIndex = chapter,
                pages = nextPages,
                pageIndex = nextPageIndex,
                selectedParagraphIndex = null,
                pageAnimationForward = if (animate) forward else it.pageAnimationForward,
                pageAnimationKey = if (animate) it.pageAnimationKey + 1L else it.pageAnimationKey
            )
        }
        saveProgress()
    }

    fun previousPage() {
        val state = _uiState.value
        when {
            state.pageIndex > 0 -> {
                _uiState.update {
                    it.copy(
                        pageAnimationForward = false,
                        pageAnimationKey = it.pageAnimationKey + 1L,
                        pageIndex = it.pageIndex - 1,
                        selectedParagraphIndex = null
                    )
                }
                saveProgress()
            }
            state.chapterIndex > 0 -> {
                val previousPages = pagesFor(state.chapterIndex - 1)
                _uiState.update {
                    it.copy(
                        pageAnimationForward = false,
                        pageAnimationKey = it.pageAnimationKey + 1L,
                        chapterIndex = it.chapterIndex - 1,
                        pages = previousPages,
                        pageIndex = (previousPages.size - 1).coerceAtLeast(0),
                        selectedParagraphIndex = null
                    )
                }
                saveProgress()
            }
        }
    }

    fun nextPage() {
        val state = _uiState.value
        when {
            state.pageIndex < state.pages.lastIndex -> {
                _uiState.update {
                    it.copy(
                        pageAnimationForward = true,
                        pageAnimationKey = it.pageAnimationKey + 1L,
                        pageIndex = it.pageIndex + 1,
                        selectedParagraphIndex = null
                    )
                }
                saveProgress()
            }
            state.chapterIndex < state.chapters.lastIndex -> setPosition(state.chapterIndex + 1, 0, forward = true)
        }
    }

    fun previousChapter() {
        val state = _uiState.value
        if (state.chapterIndex <= 0) return
        if (state.isListening) sendPlayback(ReaderPlaybackService.ACTION_PREVIOUS_CHAPTER)
        setPosition(state.chapterIndex - 1, 0, forward = false)
    }

    fun nextChapter() {
        val state = _uiState.value
        if (state.chapterIndex >= state.chapters.lastIndex) return
        if (state.isListening) sendPlayback(ReaderPlaybackService.ACTION_NEXT_CHAPTER)
        setPosition(state.chapterIndex + 1, 0, forward = true)
    }

    /** UI 完成视口测量后回灌：写入测量分页缓存，并在测得的是当前章时更新展示分页。 */
    fun onPagesMeasured(measuredChapterIndex: Int, measuredPages: List<TxtPage>) {
        val book = _uiState.value.currentBook ?: return
        ReaderMeasuredPageCache.putChapterPages(book.uri, measuredChapterIndex, measuredPages)
        _uiState.update { state ->
            if (measuredChapterIndex == state.chapterIndex && measuredPages != state.pages) {
                state.copy(
                    pages = measuredPages,
                    pageIndex = state.pageIndex.coerceIn(0, (measuredPages.size - 1).coerceAtLeast(0))
                )
            } else {
                state
            }
        }
    }

    fun onPageTargetChanged(length: Int) {
        _uiState.update { it.copy(pageTargetLength = length) }
    }

    fun selectParagraph(index: Int) {
        _uiState.update { it.copy(selectedParagraphIndex = index, statusText = "已选中该段") }
    }

    fun clearSelectedParagraph() {
        _uiState.update { it.copy(selectedParagraphIndex = null) }
    }

    fun setStatus(text: String) {
        _uiState.update { it.copy(statusText = text) }
    }

    // ---- 听书控制 ----

    fun startListening(paragraphIndex: Int = 0) {
        val state = _uiState.value
        val book = state.currentBook ?: return
        val activeSnapshot = ReaderPlaybackService.getPlaybackSnapshot(book.uri)
        if (activeSnapshot?.isListening == true) {
            syncPlaybackSnapshot(activeSnapshot)
            return
        }
        if (state.pages.isNotEmpty()) {
            ReaderMeasuredPageCache.putChapterPages(book.uri, state.chapterIndex, state.pages)
        }
        val intent = Intent(getApplication(), ReaderPlaybackService::class.java).apply {
            action = ReaderPlaybackService.ACTION_START
            putExtra(ReaderPlaybackService.EXTRA_URI, book.uri)
            putExtra(ReaderPlaybackService.EXTRA_TITLE, book.title)
            putExtra(ReaderPlaybackService.EXTRA_VOICE, state.selectedVoiceId)
            putExtra(ReaderPlaybackService.EXTRA_STYLE, state.selectedStyle)
            putExtra(ReaderPlaybackService.EXTRA_ENGINE_ID, currentEngineId)
            putExtra(ReaderPlaybackService.EXTRA_CHAPTER_INDEX, state.chapterIndex)
            putExtra(ReaderPlaybackService.EXTRA_PAGE_INDEX, state.pageIndex)
            putExtra(ReaderPlaybackService.EXTRA_PARAGRAPH_INDEX, paragraphIndex.coerceAtLeast(0))
            putExtra(ReaderPlaybackService.EXTRA_PAGE_TARGET_LENGTH, state.pageTargetLength)
            putExtra(ReaderPlaybackService.EXTRA_GAP_MS, state.readerGapMs.toLong())
            putExtra(ReaderPlaybackService.EXTRA_SLEEP_MINUTES, state.readerSleepMinutes)
            putExtra(ReaderPlaybackService.EXTRA_STOP_AFTER_CHAPTERS, state.readerStopAfterChapters)
            putExtra(ReaderPlaybackService.EXTRA_CONSERVATIVE_REQUEST_INTERVAL_MS, state.conservativeRequestIntervalMs)
            putExtra(ReaderPlaybackService.EXTRA_RETRY_COUNT, state.retryCount)
            putExtra(ReaderPlaybackService.EXTRA_RETRY_BASE_DELAY_MS, state.retryBaseDelayMs)
            // 分角色朗读配置透传给服务。未开启时服务忽略，全书用主音色与主风格。
            putExtra(ReaderPlaybackService.EXTRA_ROLE_ENABLED, state.roleEnabled)
            putExtra(ReaderPlaybackService.EXTRA_ROLE_PROFILE_JSON, com.voxengine.reader.RoleProfileJson.serialize(state.roleProfile))
        }
        ContextCompat.startForegroundService(getApplication(), intent)
        _uiState.update {
            it.copy(
                isListening = true,
                isPaused = false,
                selectedParagraphIndex = null,
                statusText = if (paragraphIndex > 0) "已从选中段落开始听书" else "听书已开始"
            )
        }
    }

    fun pauseListening() {
        val state = _uiState.value
        if (!state.isListening || state.isPaused) return
        sendPlayback(ReaderPlaybackService.ACTION_PAUSE)
        _uiState.update { it.copy(isPaused = true, statusText = "听书已暂停") }
    }

    fun resumeListening() {
        val state = _uiState.value
        if (!state.isListening || !state.isPaused) return
        sendPlayback(ReaderPlaybackService.ACTION_RESUME)
        _uiState.update { it.copy(isPaused = false, statusText = "听书播放中") }
    }

    fun stopListening() {
        if (!_uiState.value.isListening) return
        sendPlayback(ReaderPlaybackService.ACTION_STOP)
        _uiState.update { it.copy(isListening = false, isPaused = false, statusText = "听书已停止") }
    }

    fun syncPlaybackSnapshot(snapshot: PlaybackSnapshot?) {
        val book = _uiState.value.currentBook ?: return
        if (snapshot == null || snapshot.uri != book.uri || !snapshot.isListening) {
            _uiState.update { it.copy(isListening = false, isPaused = false) }
            return
        }
        val state = _uiState.value
        _uiState.update {
            it.copy(
                isListening = true,
                isPaused = snapshot.isPaused,
                statusText = if (snapshot.isPaused) "听书已暂停" else "听书播放中"
            )
        }
        if (snapshot.chapterIndex in state.chapters.indices &&
            (snapshot.chapterIndex != state.chapterIndex || snapshot.pageIndex != state.pageIndex)
        ) {
            setPosition(
                snapshot.chapterIndex,
                snapshot.pageIndex,
                forward = snapshot.chapterIndex > state.chapterIndex || snapshot.pageIndex > state.pageIndex
            )
        }
    }

    private fun sendPlayback(action: String) {
        val intent = Intent(getApplication(), ReaderPlaybackService::class.java).setAction(action)
        if (action == ReaderPlaybackService.ACTION_START) {
            ContextCompat.startForegroundService(getApplication(), intent)
        } else {
            getApplication<Application>().startService(intent)
        }
    }

    // ---- 音色 / 风格 / 听书设置 ----

    fun selectVoice(voice: VoiceInfo) {
        _uiState.update { it.copy(selectedVoiceId = voice.id, selectedVoiceName = voice.name) }
        viewModelScope.launch { settings.updateDefaultVoice(voice.id) }
    }

    fun setStyle(style: String) {
        _uiState.update { it.copy(selectedStyle = style) }
        viewModelScope.launch { settings.updateDefaultStyle(style.ifBlank { "无" }) }
    }

    // ---- 分角色朗读 ----
    // 所有改动都更新 roleProfile 并整体持久化为 JSON。voice 为 null/空 → 回落主音色；style 空串 → 回落主风格。

    fun onRoleEnabledChange(enabled: Boolean) {
        _uiState.update { it.copy(roleEnabled = enabled) }
        viewModelScope.launch { settings.updateReaderRoleEnabled(enabled) }
    }

    fun setNarrationVoice(voice: com.voxengine.engine.VoiceInfo?) =
        updateRoleProfile { it.copy(narration = it.narration.copy(voice = voice?.id?.takeIf { v -> v.isNotBlank() })) }

    fun setNarrationStyle(style: String) =
        updateRoleProfile { it.copy(narration = it.narration.copy(style = style.takeIf { s -> s.isNotBlank() })) }

    fun setDialogueVoice(voice: com.voxengine.engine.VoiceInfo?) =
        updateRoleProfile { it.copy(dialogue = it.dialogue.copy(voice = voice?.id?.takeIf { v -> v.isNotBlank() })) }

    fun setDialogueStyle(style: String) =
        updateRoleProfile { it.copy(dialogue = it.dialogue.copy(style = style.takeIf { s -> s.isNotBlank() })) }

    /** 新增或更新一个角色（按名字覆盖）。voice 必填，style 可选。 */
    fun saveCharacterVoice(name: String, voice: String?, style: String?) {
        val key = name.trim()
        if (key.isBlank() || voice.isNullOrBlank()) return
        updateRoleProfile {
            it.copy(characters = it.characters + (key to com.voxengine.reader.RoleVoiceStyle(voice, style?.takeIf { s -> s.isNotBlank() })))
        }
    }

    fun removeCharacterVoice(name: String) =
        updateRoleProfile { it.copy(characters = it.characters - name) }

    private fun updateRoleProfile(transform: (com.voxengine.reader.RoleProfile) -> com.voxengine.reader.RoleProfile) {
        val updated = transform(_uiState.value.roleProfile)
        _uiState.update { it.copy(roleProfile = updated) }
        viewModelScope.launch { settings.updateReaderRoleProfileJson(com.voxengine.reader.RoleProfileJson.serialize(updated)) }
    }

    fun onGapChange(value: Int) = _uiState.update { it.copy(readerGapMs = value.coerceIn(0, 3000)) }
    fun commitGap() = viewModelScope.launch { settings.updateReaderParagraphGapMs(_uiState.value.readerGapMs) }

    fun onSleepChange(value: Int) = _uiState.update { it.copy(readerSleepMinutes = value.coerceIn(0, 180)) }
    fun commitSleep() = viewModelScope.launch { settings.updateReaderSleepMinutes(_uiState.value.readerSleepMinutes) }

    fun onStopAfterChaptersChange(value: Int) = _uiState.update { it.copy(readerStopAfterChapters = value.coerceIn(0, 20)) }
    fun commitStopAfterChapters() = viewModelScope.launch { settings.updateReaderStopAfterChapters(_uiState.value.readerStopAfterChapters) }

    fun onConservativeRequestIntervalChange(value: Int) =
        _uiState.update { it.copy(conservativeRequestIntervalMs = value.coerceIn(500, 30_000)) }
    fun commitConservativeRequestInterval() =
        viewModelScope.launch { settings.updateReaderConservativeRequestIntervalMs(_uiState.value.conservativeRequestIntervalMs) }

    fun onRetryCountChange(value: Int) = _uiState.update { it.copy(retryCount = value.coerceIn(0, 8)) }
    fun commitRetryCount() = viewModelScope.launch { settings.updateReaderRetryCount(_uiState.value.retryCount) }

    fun onRetryBaseDelayChange(value: Int) = _uiState.update { it.copy(retryBaseDelayMs = value.coerceIn(500, 15_000)) }
    fun commitRetryBaseDelay() = viewModelScope.launch { settings.updateReaderRetryBaseDelayMs(_uiState.value.retryBaseDelayMs) }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor: Cursor? = getApplication<Application>().contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && it.moveToFirst()) it.getString(nameIndex) else null
        }
    }
}

data class ReaderUiState(
    val currentBook: ReaderBookEntity? = null,
    val chapters: List<TxtChapter> = emptyList(),
    val pages: List<TxtPage> = emptyList(),
    val chapterIndex: Int = 0,
    val pageIndex: Int = 0,
    val pageTargetLength: Int = 160,
    val isLoadingBook: Boolean = false,
    val statusText: String = "",
    val selectedVoiceId: String = "冰糖",
    val selectedVoiceName: String = "冰糖",
    val selectedStyle: String = "",
    val voices: List<VoiceInfo> = emptyList(),
    val isListening: Boolean = false,
    val isPaused: Boolean = false,
    val selectedParagraphIndex: Int? = null,
    val isEngineConfigured: Boolean = false,
    val readerGapMs: Int = 700,
    val readerSleepMinutes: Int = 0,
    val readerStopAfterChapters: Int = 0,
    val conservativeRequestIntervalMs: Int = 5000,
    val retryCount: Int = 3,
    val retryBaseDelayMs: Int = 2000,
    // 分角色朗读档：旁白/对话/具名角色各自的音色与可选风格。空档等价于关闭。
    val roleEnabled: Boolean = false,
    val roleProfile: com.voxengine.reader.RoleProfile = com.voxengine.reader.RoleProfile(),
    val pageAnimationKey: Long = 0L,
    val pageAnimationForward: Boolean = true
)
