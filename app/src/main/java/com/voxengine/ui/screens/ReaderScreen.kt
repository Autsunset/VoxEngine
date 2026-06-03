package com.voxengine.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.voxengine.data.AppDatabase
import com.voxengine.data.ReaderBookEntity
import com.voxengine.data.ReaderChapterEntity
import com.voxengine.data.SettingsRepository
import com.voxengine.engine.EngineRegistry
import com.voxengine.reader.ReaderChapterCache
import com.voxengine.reader.ReaderMeasuredPageCache
import com.voxengine.reader.ReaderPlaybackService
import com.voxengine.reader.TxtChapter
import com.voxengine.reader.TxtNovelParser
import com.voxengine.reader.TxtPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import kotlin.math.roundToInt

private const val READER_PANEL_NONE = 0
private const val READER_PANEL_CATALOG = 1
private const val READER_PANEL_SETTINGS = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    onReadingModeChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val settings = remember { SettingsRepository(context) }
    val books by db.readerBookDao().getAll().collectAsState(initial = emptyList())

    val currentEngineId by settings.currentEngine.collectAsState(initial = "mimo")
    val defaultVoice by settings.defaultVoice.collectAsState(initial = "冰糖")
    val defaultStyle by settings.defaultStyle.collectAsState(initial = "无")
    val activeEngine = remember(currentEngineId) { EngineRegistry.get(currentEngineId) }
    val voices by produceState(initialValue = emptyList<com.voxengine.engine.VoiceInfo>(), activeEngine) {
        value = activeEngine?.getVoices() ?: emptyList()
    }
    val readerGapMs by settings.readerParagraphGapMs.collectAsState(initial = 700)
    val readerSleepMinutes by settings.readerSleepMinutes.collectAsState(initial = 0)
    val readerStopAfterChapters by settings.readerStopAfterChapters.collectAsState(initial = 0)
    val readerConservativeRequestIntervalMs by settings.readerConservativeRequestIntervalMs.collectAsState(initial = 5000)
    val readerRetryCount by settings.readerRetryCount.collectAsState(initial = 3)
    val readerRetryBaseDelayMs by settings.readerRetryBaseDelayMs.collectAsState(initial = 2000)

    var readerGapMsDraft by remember { mutableIntStateOf(readerGapMs) }
    var readerSleepMinutesDraft by remember { mutableIntStateOf(readerSleepMinutes) }
    var readerStopAfterChaptersDraft by remember { mutableIntStateOf(readerStopAfterChapters) }
    var readerConservativeRequestIntervalMsDraft by remember { mutableIntStateOf(readerConservativeRequestIntervalMs) }
    var readerRetryCountDraft by remember { mutableIntStateOf(readerRetryCount) }
    var readerRetryBaseDelayMsDraft by remember { mutableIntStateOf(readerRetryBaseDelayMs) }

    var currentBook by remember { mutableStateOf<ReaderBookEntity?>(null) }
    var chapters by remember { mutableStateOf<List<TxtChapter>>(emptyList()) }
    var pages by remember { mutableStateOf<List<TxtPage>>(emptyList()) }
    var chapterIndex by remember { mutableIntStateOf(0) }
    var pageIndex by remember { mutableIntStateOf(0) }
    var pageTargetLength by remember { mutableIntStateOf(160) }
    var isLoadingBook by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var menuVisible by remember { mutableStateOf(false) }
    var panelMode by remember { mutableIntStateOf(READER_PANEL_NONE) }
    var selectedVoiceId by remember { mutableStateOf("冰糖") }
    var selectedVoiceName by remember { mutableStateOf("冰糖") }
    var selectedStyle by remember { mutableStateOf("") }
    var voiceExpanded by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var selectedParagraphIndex by remember { mutableStateOf<Int?>(null) }
    var pageAnimationKey by remember { mutableLongStateOf(0L) }
    var pageAnimationForward by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        onDispose { onReadingModeChanged(false) }
    }

    LaunchedEffect(currentBook != null) {
        onReadingModeChanged(currentBook != null)
    }

    LaunchedEffect(readerGapMs) { readerGapMsDraft = readerGapMs }
    LaunchedEffect(readerSleepMinutes) { readerSleepMinutesDraft = readerSleepMinutes }
    LaunchedEffect(readerStopAfterChapters) { readerStopAfterChaptersDraft = readerStopAfterChapters }
    LaunchedEffect(readerConservativeRequestIntervalMs) { readerConservativeRequestIntervalMsDraft = readerConservativeRequestIntervalMs }
    LaunchedEffect(readerRetryCount) { readerRetryCountDraft = readerRetryCount }
    LaunchedEffect(readerRetryBaseDelayMs) { readerRetryBaseDelayMsDraft = readerRetryBaseDelayMs }

    fun pagesFor(chapter: Int): List<TxtPage> {
        val bookUri = currentBook?.uri
        if (bookUri != null) {
            ReaderMeasuredPageCache.getChapterPages(bookUri, chapter)?.let { return it }
        }
        return chapters.getOrNull(chapter)?.content?.let { TxtNovelParser.paginate(it, pageTargetLength) }.orEmpty()
    }

    fun saveProgress() {
        val book = currentBook ?: return
        scope.launch(Dispatchers.IO) {
            db.readerBookDao().updateProgress(book.uri, chapterIndex, pageIndex, 0)
        }
    }

    fun setPosition(chapter: Int, page: Int, forward: Boolean = true) {
        if (chapter !in chapters.indices) return
        val nextPages = pagesFor(chapter)
        val nextPageIndex = page.coerceIn(0, (nextPages.size - 1).coerceAtLeast(0))
        if (chapter != chapterIndex || nextPageIndex != pageIndex) {
            pageAnimationForward = forward
            pageAnimationKey += 1L
        }
        chapterIndex = chapter
        pages = nextPages
        pageIndex = nextPageIndex
        selectedParagraphIndex = null
        saveProgress()
    }

    fun refreshCurrentPages(resetPage: Boolean) {
        val nextPages = pagesFor(chapterIndex)
        pages = nextPages
        pageIndex = if (resetPage) 0 else pageIndex.coerceIn(0, (nextPages.size - 1).coerceAtLeast(0))
        selectedParagraphIndex = null
    }

    fun openBook(book: ReaderBookEntity) {
        currentBook = book
        menuVisible = false
        panelMode = READER_PANEL_NONE
        isListening = false
        isPaused = false
        selectedParagraphIndex = null
    }

    fun sendPlayback(action: String) {
        val intent = Intent(context, ReaderPlaybackService::class.java).setAction(action)
        if (action == ReaderPlaybackService.ACTION_START) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }

    fun startListening(paragraphIndex: Int = 0) {
        val book = currentBook ?: return
        if (pages.isNotEmpty()) {
            ReaderMeasuredPageCache.putChapterPages(book.uri, chapterIndex, pages)
        }
        val intent = Intent(context, ReaderPlaybackService::class.java).apply {
            action = ReaderPlaybackService.ACTION_START
            putExtra(ReaderPlaybackService.EXTRA_URI, book.uri)
            putExtra(ReaderPlaybackService.EXTRA_TITLE, book.title)
            putExtra(ReaderPlaybackService.EXTRA_VOICE, selectedVoiceId)
            putExtra(ReaderPlaybackService.EXTRA_STYLE, selectedStyle)
            putExtra(ReaderPlaybackService.EXTRA_ENGINE_ID, currentEngineId)
            putExtra(ReaderPlaybackService.EXTRA_CHAPTER_INDEX, chapterIndex)
            putExtra(ReaderPlaybackService.EXTRA_PAGE_INDEX, pageIndex)
            putExtra(ReaderPlaybackService.EXTRA_PARAGRAPH_INDEX, paragraphIndex.coerceAtLeast(0))
            putExtra(ReaderPlaybackService.EXTRA_PAGE_TARGET_LENGTH, pageTargetLength)
            putExtra(ReaderPlaybackService.EXTRA_GAP_MS, readerGapMsDraft.toLong())
            putExtra(ReaderPlaybackService.EXTRA_SLEEP_MINUTES, readerSleepMinutesDraft)
            putExtra(ReaderPlaybackService.EXTRA_STOP_AFTER_CHAPTERS, readerStopAfterChaptersDraft)
            putExtra(ReaderPlaybackService.EXTRA_CONSERVATIVE_REQUEST_INTERVAL_MS, readerConservativeRequestIntervalMsDraft)
            putExtra(ReaderPlaybackService.EXTRA_RETRY_COUNT, readerRetryCountDraft)
            putExtra(ReaderPlaybackService.EXTRA_RETRY_BASE_DELAY_MS, readerRetryBaseDelayMsDraft)
        }
        ContextCompat.startForegroundService(context, intent)
        isListening = true
        isPaused = false
        selectedParagraphIndex = null
        statusText = if (paragraphIndex > 0) "已从选中段落开始听书" else "听书已开始"
    }

    fun pauseListening() {
        if (!isListening || isPaused) return
        sendPlayback(ReaderPlaybackService.ACTION_PAUSE)
        isPaused = true
        statusText = "听书已暂停"
    }

    fun resumeListening() {
        if (!isListening || !isPaused) return
        sendPlayback(ReaderPlaybackService.ACTION_RESUME)
        isPaused = false
        statusText = "听书播放中"
    }

    fun stopListening() {
        if (!isListening) return
        sendPlayback(ReaderPlaybackService.ACTION_STOP)
        isListening = false
        isPaused = false
        statusText = "听书已停止"
    }

    fun previousChapter() {
        if (chapterIndex <= 0) return
        if (isListening) sendPlayback(ReaderPlaybackService.ACTION_PREVIOUS_CHAPTER)
        setPosition(chapterIndex - 1, 0, forward = false)
    }

    fun nextChapter() {
        if (chapterIndex >= chapters.lastIndex) return
        if (isListening) sendPlayback(ReaderPlaybackService.ACTION_NEXT_CHAPTER)
        setPosition(chapterIndex + 1, 0, forward = true)
    }

    fun previousPage() {
        when {
            pageIndex > 0 -> {
                pageAnimationForward = false
                pageAnimationKey += 1L
                pageIndex -= 1
                selectedParagraphIndex = null
                saveProgress()
            }
            chapterIndex > 0 -> {
                val previousPages = pagesFor(chapterIndex - 1)
                pageAnimationForward = false
                pageAnimationKey += 1L
                chapterIndex -= 1
                pages = previousPages
                pageIndex = (previousPages.size - 1).coerceAtLeast(0)
                selectedParagraphIndex = null
                saveProgress()
            }
        }
    }

    fun nextPage() {
        when {
            pageIndex < pages.lastIndex -> {
                pageAnimationForward = true
                pageAnimationKey += 1L
                pageIndex += 1
                selectedParagraphIndex = null
                saveProgress()
            }
            chapterIndex < chapters.lastIndex -> setPosition(chapterIndex + 1, 0, forward = true)
        }
    }

    fun toggleReaderMenu() {
        menuVisible = !menuVisible
        if (!menuVisible) panelMode = READER_PANEL_NONE
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var firstBook: ReaderBookEntity? = null
            uris.forEach { uri ->
                val title = context.queryDisplayName(uri) ?: "本地小说.txt"
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
            firstBook?.let { if (currentBook == null) openBook(it) }
        }
    }

    LaunchedEffect(defaultVoice, defaultStyle) {
        if (defaultVoice.isNotBlank()) selectedVoiceId = defaultVoice
        selectedStyle = defaultStyle.takeIf { it != "无" }.orEmpty()
    }

    LaunchedEffect(voices, selectedVoiceId) {
        val selected = voices.firstOrNull { it.id == selectedVoiceId || it.name == selectedVoiceId }
        if (selected != null) {
            selectedVoiceId = selected.id
            selectedVoiceName = selected.name
        } else if (voices.isNotEmpty()) {
            selectedVoiceId = voices.first().id
            selectedVoiceName = voices.first().name
        }
    }

    LaunchedEffect(currentBook?.uri) {
        val book = currentBook ?: return@LaunchedEffect
        isLoadingBook = true
        statusText = "正在解析 ${book.title}"
        val parsed = withContext(Dispatchers.IO) {
            runCatching {
                ReaderChapterCache.getChapters(book.uri)
                    ?: db.readerChapterDao().getChapters(book.uri)
                        .map { it.toTxtChapter() }
                        .takeIf { it.isNotEmpty() }
                        ?.also { ReaderChapterCache.putChapters(book.uri, it) }
                    ?: run {
                        val bytes = context.contentResolver.openInputStream(Uri.parse(book.uri))?.use { it.readBytes() }
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
            chapters = list
            chapterIndex = book.lastChapterIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
            pages = list.getOrNull(chapterIndex)?.content?.let { TxtNovelParser.paginate(it, pageTargetLength) }.orEmpty()
            pageIndex = book.lastPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            selectedParagraphIndex = null
            statusText = "已载入 ${list.size} 章"
        }.onFailure {
            chapters = emptyList()
            pages = emptyList()
            chapterIndex = 0
            pageIndex = 0
            statusText = "打开失败: ${it.message}"
        }
        isLoadingBook = false
    }


    val book = currentBook
    if (book == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("书架") },
                    actions = {
                        IconButton(onClick = { openDocumentLauncher.launch(arrayOf("text/plain", "text/*")) }) {
                            Icon(Icons.Default.UploadFile, contentDescription = "导入TXT")
                        }
                    }
                )
            }
        ) { padding ->
            Bookshelf(
                books = books,
                onImport = { openDocumentLauncher.launch(arrayOf("text/plain", "text/*")) },
                onOpen = ::openBook,
                onDelete = { item ->
                    scope.launch(Dispatchers.IO) {
                        db.withTransaction {
                            db.readerChapterDao().deleteByBookUri(item.uri)
                            db.readerBookDao().deleteByUri(item.uri)
                        }
                        ReaderChapterCache.clearBook(item.uri)
                        ReaderMeasuredPageCache.clearBook(item.uri)
                    }
                },
                modifier = Modifier.padding(padding)
            )
        }
        return
    }

    DisposableEffect(book.uri, chapters, chapterIndex, pageIndex) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra(ReaderPlaybackService.EXTRA_URI) != book.uri) return
                val nextChapter = intent.getIntExtra(ReaderPlaybackService.EXTRA_CHAPTER_INDEX, chapterIndex)
                val nextPage = intent.getIntExtra(ReaderPlaybackService.EXTRA_PAGE_INDEX, pageIndex)
                if (nextChapter in chapters.indices && (nextChapter != chapterIndex || nextPage != pageIndex)) {
                    setPosition(nextChapter, nextPage, forward = nextChapter > chapterIndex || nextPage > pageIndex)
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ReaderPlaybackService.ACTION_PROGRESS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ReaderPage(
            book = book,
            chapters = chapters,
            pages = pages,
            chapterIndex = chapterIndex,
            pageIndex = pageIndex,
            isLoadingBook = isLoadingBook,
            selectedParagraphIndex = selectedParagraphIndex,
            pageAnimationKey = pageAnimationKey,
            pageAnimationForward = pageAnimationForward,
            onPageTargetChanged = { pageTargetLength = it },
            onPagesMeasured = { measuredChapterIndex, measuredPages ->
                ReaderMeasuredPageCache.putChapterPages(book.uri, measuredChapterIndex, measuredPages)
                if (measuredChapterIndex == chapterIndex && measuredPages != pages) {
                    pages = measuredPages
                    pageIndex = pageIndex.coerceIn(0, (measuredPages.size - 1).coerceAtLeast(0))
                }
            },
            onParagraphTap = {
                if (selectedParagraphIndex != null) {
                    selectedParagraphIndex = null
                } else {
                    toggleReaderMenu()
                }
            },
            onParagraphLongPress = { index, _ ->
                selectedParagraphIndex = index
                statusText = "已选中该段"
            },
            onCopyParagraph = { paragraph ->
                clipboard.setText(AnnotatedString(paragraph))
                statusText = "已复制该段"
            },
            onReadFromParagraph = { index -> startListening(index) },
            onPreviousPage = ::previousPage,
            onNextPage = ::nextPage,
            onCenterTap = ::toggleReaderMenu
        )

        if (menuVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        menuVisible = false
                        panelMode = READER_PANEL_NONE
                    }
            )
            ReaderTopMenu(
                title = book.title,
                chapterTitle = chapters.getOrNull(chapterIndex)?.title.orEmpty(),
                onBack = {
                    currentBook = null
                    menuVisible = false
                    panelMode = READER_PANEL_NONE
                },
                onImport = { openDocumentLauncher.launch(arrayOf("text/plain", "text/*")) },
                modifier = Modifier.align(Alignment.TopCenter)
            )
            ReaderBottomMenu(
                chapters = chapters,
                currentChapterIndex = chapterIndex,
                currentPageIndex = pageIndex,
                pageCount = pages.size,
                panelMode = panelMode,
                selectedVoiceName = selectedVoiceName,
                selectedStyle = selectedStyle,
                voices = voices,
                voiceExpanded = voiceExpanded,
                onVoiceExpandedChange = { voiceExpanded = it },
                onVoiceSelected = { voice ->
                    selectedVoiceId = voice.id
                    selectedVoiceName = voice.name
                    voiceExpanded = false
                    scope.launch { settings.updateDefaultVoice(voice.id) }
                },
                onStyleChange = {
                    selectedStyle = it
                    scope.launch { settings.updateDefaultStyle(it.ifBlank { "无" }) }
                },
                readerGapMs = readerGapMsDraft,
                readerSleepMinutes = readerSleepMinutesDraft,
                readerStopAfterChapters = readerStopAfterChaptersDraft,
                conservativeRequestIntervalMs = readerConservativeRequestIntervalMsDraft,
                retryCount = readerRetryCountDraft,
                retryBaseDelayMs = readerRetryBaseDelayMsDraft,
                onGapChange = { value -> readerGapMsDraft = value.coerceIn(0, 3000) },
                onSleepChange = { value -> readerSleepMinutesDraft = value.coerceIn(0, 180) },
                onStopAfterChaptersChange = { value -> readerStopAfterChaptersDraft = value.coerceIn(0, 20) },
                onConservativeRequestIntervalChange = { value -> readerConservativeRequestIntervalMsDraft = value.coerceIn(500, 30_000) },
                onRetryCountChange = { value -> readerRetryCountDraft = value.coerceIn(0, 8) },
                onRetryBaseDelayChange = { value -> readerRetryBaseDelayMsDraft = value.coerceIn(500, 15_000) },
                onGapChangeFinished = { scope.launch { settings.updateReaderParagraphGapMs(readerGapMsDraft) } },
                onSleepChangeFinished = { scope.launch { settings.updateReaderSleepMinutes(readerSleepMinutesDraft) } },
                onStopAfterChaptersChangeFinished = { scope.launch { settings.updateReaderStopAfterChapters(readerStopAfterChaptersDraft) } },
                onConservativeRequestIntervalChangeFinished = { scope.launch { settings.updateReaderConservativeRequestIntervalMs(readerConservativeRequestIntervalMsDraft) } },
                onRetryCountChangeFinished = { scope.launch { settings.updateReaderRetryCount(readerRetryCountDraft) } },
                onRetryBaseDelayChangeFinished = { scope.launch { settings.updateReaderRetryBaseDelayMs(readerRetryBaseDelayMsDraft) } },
                isListening = isListening,
                isPaused = isPaused,
                canListen = activeEngine?.isConfigured() == true && pages.isNotEmpty(),
                statusText = statusText,
                onPanelChange = { panelMode = if (panelMode == it) READER_PANEL_NONE else it },
                onChapterSelected = { index ->
                    setPosition(index, 0)
                    panelMode = READER_PANEL_NONE
                },
                onPreviousChapter = ::previousChapter,
                onNextChapter = ::nextChapter,
                onPreviousPage = ::previousPage,
                onNextPage = ::nextPage,
                onPageSelected = { page -> setPosition(chapterIndex, page) },
                onStartListening = { startListening() },
                onPauseListening = ::pauseListening,
                onResumeListening = ::resumeListening,
                onStopListening = ::stopListening,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun Bookshelf(
    books: List<ReaderBookEntity>,
    onImport: () -> Unit,
    onOpen: (ReaderBookEntity) -> Unit,
    onDelete: (ReaderBookEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(148.dp),
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.height(176.dp).fillMaxWidth().clickable { onImport() }) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("导入TXT", style = MaterialTheme.typography.titleSmall)
                    Text("可一次选择多本", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(books, key = { it.uri }) { book ->
            Card(modifier = Modifier.height(176.dp).fillMaxWidth().clickable { onOpen(book) }) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            book.title.removeSuffix(".txt"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onDelete(book) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                    Text(
                        "第${book.lastChapterIndex + 1}章 · 第${book.lastPageIndex + 1}页",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
@Composable
private fun ReaderPage(
    book: ReaderBookEntity,
    chapters: List<TxtChapter>,
    pages: List<TxtPage>,
    chapterIndex: Int,
    pageIndex: Int,
    isLoadingBook: Boolean,
    selectedParagraphIndex: Int?,
    pageAnimationKey: Long,
    pageAnimationForward: Boolean,
    onPageTargetChanged: (Int) -> Unit,
    onPagesMeasured: (Int, List<TxtPage>) -> Unit,
    onParagraphTap: () -> Unit,
    onParagraphLongPress: (Int, String) -> Unit,
    onCopyParagraph: (String) -> Unit,
    onReadFromParagraph: (Int) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onCenterTap: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val titleStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        val pageInfoStyle = MaterialTheme.typography.bodySmall
        val bodyStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 30.sp)
        val chapterTitle = chapters.getOrNull(chapterIndex)?.title ?: book.title
        fun measureChapterPagesForIndex(targetChapterIndex: Int): List<TxtPage> = with(density) {
            val targetTitle = chapters.getOrNull(targetChapterIndex)?.title ?: book.title
            val pageWidthPx = (maxWidth - 48.dp).roundToPx().coerceAtLeast(1)
            val screenHeightPx = maxHeight.roundToPx().coerceAtLeast(1)
            val verticalPaddingPx = 44.dp.roundToPx()
            val titleGapPx = 8.dp.roundToPx()
            val pageInfoGapPx = 14.dp.roundToPx()
            val paragraphGapPx = 14.dp.roundToPx()
            val pageInfoHeightPx = textMeasurer.measure(
                text = AnnotatedString("第999/999章 · 第999/999页"),
                style = pageInfoStyle,
                constraints = Constraints(maxWidth = pageWidthPx)
            ).size.height
            val titleHeightPx = textMeasurer.measure(
                text = AnnotatedString(targetTitle),
                style = titleStyle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = pageWidthPx)
            ).size.height
            val normalTextHeightPx = (screenHeightPx - verticalPaddingPx - pageInfoHeightPx - pageInfoGapPx).coerceAtLeast(1)
            val firstTextHeightPx = (normalTextHeightPx - titleHeightPx - titleGapPx).coerceAtLeast(1)
            measurePagesForViewport(
                content = chapters.getOrNull(targetChapterIndex)?.content.orEmpty(),
                textMeasurer = textMeasurer,
                style = bodyStyle,
                pageWidthPx = pageWidthPx,
                firstPageHeightPx = firstTextHeightPx,
                normalPageHeightPx = normalTextHeightPx,
                paragraphGapPx = paragraphGapPx
            )
        }
        val measuredPages = remember(chapters, chapterIndex, maxWidth, maxHeight, textMeasurer, titleStyle, pageInfoStyle, bodyStyle) {
            measureChapterPagesForIndex(chapterIndex)
        }
        val adjacentMeasuredPages = remember(chapters, chapterIndex, maxWidth, maxHeight, textMeasurer, titleStyle, pageInfoStyle, bodyStyle) {
            listOf(chapterIndex - 1, chapterIndex + 1)
                .filter { it in chapters.indices }
                .associateWith { measureChapterPagesForIndex(it) }
        }
        val displayPages = measuredPages.ifEmpty { pages }
        LaunchedEffect(chapterIndex, measuredPages, adjacentMeasuredPages) {
            onPagesMeasured(chapterIndex, measuredPages)
            adjacentMeasuredPages.forEach { (measuredChapterIndex, measuredChapterPages) ->
                onPagesMeasured(measuredChapterIndex, measuredChapterPages)
            }
            val averagePageLength = measuredPages.map { it.text.length }.takeIf { it.isNotEmpty() }?.average()?.roundToInt() ?: 220
            onPageTargetChanged(averagePageLength.coerceIn(90, 520))
        }

        var dragDistance = 0f
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(chapterIndex, pageIndex) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragDistance = 0f },
                        onHorizontalDrag = { _, dragAmount -> dragDistance += dragAmount },
                        onDragEnd = {
                            when {
                                dragDistance > 80f -> onPreviousPage()
                                dragDistance < -80f -> onNextPage()
                            }
                        }
                    )
                }
                .pointerInput(chapterIndex, pageIndex) {
                    detectTapGestures { offset ->
                        val width = size.width.toFloat()
                        when {
                            offset.x < width * 0.32f -> onPreviousPage()
                            offset.x > width * 0.68f -> onNextPage()
                            else -> onCenterTap()
                        }
                    }
                }
        ) {
            if (isLoadingBook) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }

            AnimatedContent(
                targetState = pageAnimationKey,
                transitionSpec = {
                    val direction = if (pageAnimationForward) 1 else -1
                    slideInHorizontally(animationSpec = tween(220)) { fullWidth -> fullWidth * direction } togetherWith
                        slideOutHorizontally(animationSpec = tween(220)) { fullWidth -> -fullWidth * direction }
                },
                label = "reader-page-slide",
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 22.dp)
                ) {
                    if (pageIndex == 0) {
                        Text(
                            chapters.getOrNull(chapterIndex)?.title ?: book.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        "第${chapterIndex + 1}/${chapters.size.coerceAtLeast(1)}章 · 第${pageIndex + 1}/${displayPages.size.coerceAtLeast(1)}页",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        displayPages.getOrNull(pageIndex)?.paragraphs.orEmpty().forEachIndexed { index, paragraph ->
                            Text(
                                paragraph,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = onParagraphTap,
                                        onLongClick = { onParagraphLongPress(index, paragraph) }
                                    ),
                                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 30.sp),
                                color = if (selectedParagraphIndex == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                overflow = TextOverflow.Clip
                            )
                            if (selectedParagraphIndex == index) {
                                Row(
                                    modifier = Modifier.align(Alignment.End),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(onClick = { onCopyParagraph(paragraph) }) {
                                        Text("复制")
                                    }
                                    Button(onClick = { onReadFromParagraph(index) }) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("从此段开始读")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


private fun measurePagesForViewport(
    content: String,
    textMeasurer: TextMeasurer,
    style: TextStyle,
    pageWidthPx: Int,
    firstPageHeightPx: Int,
    normalPageHeightPx: Int,
    paragraphGapPx: Int
): List<TxtPage> {
    val paragraphs = content.split(Regex("""\n{1,}"""))
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (paragraphs.isEmpty()) return listOf(TxtPage(emptyList()))

    val pages = mutableListOf<TxtPage>()
    val current = mutableListOf<String>()
    var currentHeight = 0

    fun pageHeight(): Int = if (pages.isEmpty()) firstPageHeightPx else normalPageHeightPx

    fun measure(text: String): Int = textMeasurer.measure(
        text = AnnotatedString(text),
        style = style,
        constraints = Constraints(maxWidth = pageWidthPx)
    ).size.height

    fun flushPage() {
        if (current.isNotEmpty()) {
            pages += TxtPage(current.toList())
            current.clear()
            currentHeight = 0
        }
    }

    paragraphs.forEach { paragraph ->
        var remaining = paragraph
        while (remaining.isNotBlank()) {
            val gap = if (current.isEmpty()) 0 else paragraphGapPx
            val available = pageHeight() - currentHeight - gap
            val remainingHeight = measure(remaining)
            if (available > 0 && remainingHeight <= available) {
                current += remaining
                currentHeight += gap + remainingHeight
                remaining = ""
            } else if (current.isNotEmpty()) {
                flushPage()
            } else {
                val fitIndex = findMeasuredSplitIndex(remaining, pageHeight(), ::measure)
                val splitIndex = chooseReadableSplitIndex(remaining, fitIndex)
                val part = remaining.take(splitIndex).trimEnd()
                if (part.isBlank()) {
                    current += remaining.take(1)
                    remaining = remaining.drop(1).trimStart()
                } else {
                    current += part
                    remaining = remaining.drop(splitIndex).trimStart()
                }
                currentHeight = measure(current.last())
                flushPage()
            }
        }
    }
    flushPage()
    return pages.ifEmpty { listOf(TxtPage(emptyList())) }
}

private fun findMeasuredSplitIndex(
    text: String,
    maxHeightPx: Int,
    measure: (String) -> Int
): Int {
    var low = 1
    var high = text.length
    var best = 1
    while (low <= high) {
        val mid = (low + high) / 2
        if (measure(text.take(mid)) <= maxHeightPx) {
            best = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best.coerceIn(1, text.length)
}

private fun chooseReadableSplitIndex(text: String, fitIndex: Int): Int {
    if (fitIndex >= text.length) return text.length
    val minIndex = (fitIndex * 0.72f).roundToInt().coerceAtLeast(1)
    for (index in fitIndex downTo minIndex) {
        val char = text.getOrNull(index - 1) ?: continue
        if (char in "，。！？；、,.!?;:：") return index
    }
    return fitIndex.coerceAtLeast(1)
}

@Composable
private fun ReaderTopMenu(
    title: String,
    chapterTitle: String,
    onBack: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回书架")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                if (chapterTitle.isNotBlank()) {
                    Text(chapterTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onImport) {
                Icon(Icons.Default.UploadFile, contentDescription = "导入TXT")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderBottomMenu(
    chapters: List<TxtChapter>,
    currentChapterIndex: Int,
    currentPageIndex: Int,
    pageCount: Int,
    panelMode: Int,
    selectedVoiceName: String,
    selectedStyle: String,
    voices: List<com.voxengine.engine.VoiceInfo>,
    voiceExpanded: Boolean,
    onVoiceExpandedChange: (Boolean) -> Unit,
    onVoiceSelected: (com.voxengine.engine.VoiceInfo) -> Unit,
    onStyleChange: (String) -> Unit,
    readerGapMs: Int,
    readerSleepMinutes: Int,
    readerStopAfterChapters: Int,
    conservativeRequestIntervalMs: Int,
    retryCount: Int,
    retryBaseDelayMs: Int,
    onGapChange: (Int) -> Unit,
    onSleepChange: (Int) -> Unit,
    onStopAfterChaptersChange: (Int) -> Unit,
    onConservativeRequestIntervalChange: (Int) -> Unit,
    onRetryCountChange: (Int) -> Unit,
    onRetryBaseDelayChange: (Int) -> Unit,
    onGapChangeFinished: () -> Unit,
    onSleepChangeFinished: () -> Unit,
    onStopAfterChaptersChangeFinished: () -> Unit,
    onConservativeRequestIntervalChangeFinished: () -> Unit,
    onRetryCountChangeFinished: () -> Unit,
    onRetryBaseDelayChangeFinished: () -> Unit,
    isListening: Boolean,
    isPaused: Boolean,
    canListen: Boolean,
    statusText: String,
    onPanelChange: (Int) -> Unit,
    onChapterSelected: (Int) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onStartListening: () -> Unit,
    onPauseListening: () -> Unit,
    onResumeListening: () -> Unit,
    onStopListening: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            when (panelMode) {
                READER_PANEL_CATALOG -> CatalogPanel(
                    chapters = chapters,
                    currentChapterIndex = currentChapterIndex,
                    onChapterSelected = onChapterSelected
                )
                READER_PANEL_SETTINGS -> ReaderSettingsPanel(
                    selectedVoiceName = selectedVoiceName,
                    selectedStyle = selectedStyle,
                    voices = voices,
                    voiceExpanded = voiceExpanded,
                    onVoiceExpandedChange = onVoiceExpandedChange,
                    onVoiceSelected = onVoiceSelected,
                    onStyleChange = onStyleChange,
                    readerGapMs = readerGapMs,
                    readerSleepMinutes = readerSleepMinutes,
                    readerStopAfterChapters = readerStopAfterChapters,
                    conservativeRequestIntervalMs = conservativeRequestIntervalMs,
                    retryCount = retryCount,
                    retryBaseDelayMs = retryBaseDelayMs,
                    onGapChange = onGapChange,
                    onSleepChange = onSleepChange,
                    onStopAfterChaptersChange = onStopAfterChaptersChange,
                    onConservativeRequestIntervalChange = onConservativeRequestIntervalChange,
                    onRetryCountChange = onRetryCountChange,
                    onRetryBaseDelayChange = onRetryBaseDelayChange,
                    onGapChangeFinished = onGapChangeFinished,
                    onSleepChangeFinished = onSleepChangeFinished,
                    onStopAfterChaptersChangeFinished = onStopAfterChaptersChangeFinished,
                    onConservativeRequestIntervalChangeFinished = onConservativeRequestIntervalChangeFinished,
                    onRetryCountChangeFinished = onRetryCountChangeFinished,
                    onRetryBaseDelayChangeFinished = onRetryBaseDelayChangeFinished
                )
            }

            if (panelMode != READER_PANEL_NONE) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = currentChapterIndex > 0, onClick = onPreviousChapter) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = null)
                    Text("上一章")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "第${currentPageIndex + 1}/${pageCount.coerceAtLeast(1)}页",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                TextButton(enabled = currentChapterIndex < chapters.lastIndex, onClick = onNextChapter) {
                    Text("下一章")
                    Icon(Icons.Default.SkipNext, contentDescription = null)
                }
            }

            if (pageCount > 1) {
                Slider(
                    value = (currentPageIndex + 1).toFloat().coerceIn(1f, pageCount.toFloat()),
                    onValueChange = { onPageSelected(it.roundToInt() - 1) },
                    valueRange = 1f..pageCount.toFloat(),
                    steps = 0
                )
            }

            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                ReaderToolButton(
                    icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                    label = "目录",
                    selected = panelMode == READER_PANEL_CATALOG,
                    onClick = { onPanelChange(READER_PANEL_CATALOG) }
                )
                ReaderToolButton(
                    icon = { Icon(Icons.Default.SkipPrevious, contentDescription = null) },
                    label = "上一页",
                    enabled = currentChapterIndex > 0 || currentPageIndex > 0,
                    onClick = onPreviousPage
                )
                ReaderToolButton(
                    icon = { Icon(if (isListening && !isPaused) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null) },
                    label = if (!isListening) "听书" else if (isPaused) "继续" else "暂停",
                    enabled = canListen || isListening,
                    onClick = {
                        when {
                            !isListening -> onStartListening()
                            isPaused -> onResumeListening()
                            else -> onPauseListening()
                        }
                    }
                )
                ReaderToolButton(
                    icon = { Icon(Icons.Default.Stop, contentDescription = null) },
                    label = "停止",
                    enabled = isListening,
                    onClick = onStopListening
                )
                ReaderToolButton(
                    icon = { Icon(Icons.Default.SkipNext, contentDescription = null) },
                    label = "下一页",
                    enabled = currentChapterIndex < chapters.lastIndex || currentPageIndex < pageCount - 1,
                    onClick = onNextPage
                )
                ReaderToolButton(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = "设置",
                    selected = panelMode == READER_PANEL_SETTINGS,
                    onClick = { onPanelChange(READER_PANEL_SETTINGS) }
                )
            }

            val tip = statusText.ifBlank {
                if (canListen) "顺序合成，播放时缓存后续段落" else "请先配置当前语音引擎"
            }
            Text(
                tip,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ReaderToolButton(
    icon: @Composable () -> Unit,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(54.dp)) {
        IconButton(enabled = enabled, onClick = onClick, modifier = Modifier.size(40.dp)) {
            icon()
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                selected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CatalogPanel(
    chapters: List<TxtChapter>,
    currentChapterIndex: Int,
    onChapterSelected: (Int) -> Unit
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentChapterIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0)))

    LaunchedEffect(currentChapterIndex, chapters.size) {
        if (chapters.isNotEmpty()) {
            listState.scrollToItem(currentChapterIndex.coerceIn(0, chapters.lastIndex))
        }
    }

    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.42f)) {
        Text(
            "目录 · 共${chapters.size}章",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
            items(chapters.size) { index ->
                val chapter = chapters[index]
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onChapterSelected(index) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(42.dp)
                    )
                    Text(
                        text = chapter.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (index == currentChapterIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (index == currentChapterIndex) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(start = 10.dp).weight(1f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsPanel(
    selectedVoiceName: String,
    selectedStyle: String,
    voices: List<com.voxengine.engine.VoiceInfo>,
    voiceExpanded: Boolean,
    onVoiceExpandedChange: (Boolean) -> Unit,
    onVoiceSelected: (com.voxengine.engine.VoiceInfo) -> Unit,
    onStyleChange: (String) -> Unit,
    readerGapMs: Int,
    readerSleepMinutes: Int,
    readerStopAfterChapters: Int,
    conservativeRequestIntervalMs: Int,
    retryCount: Int,
    retryBaseDelayMs: Int,
    onGapChange: (Int) -> Unit,
    onSleepChange: (Int) -> Unit,
    onStopAfterChaptersChange: (Int) -> Unit,
    onConservativeRequestIntervalChange: (Int) -> Unit,
    onRetryCountChange: (Int) -> Unit,
    onRetryBaseDelayChange: (Int) -> Unit,
    onGapChangeFinished: () -> Unit,
    onSleepChangeFinished: () -> Unit,
    onStopAfterChaptersChangeFinished: () -> Unit,
    onConservativeRequestIntervalChangeFinished: () -> Unit,
    onRetryCountChangeFinished: () -> Unit,
    onRetryBaseDelayChangeFinished: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.48f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("听书设置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        ExposedDropdownMenuBox(expanded = voiceExpanded, onExpandedChange = onVoiceExpandedChange) {
            OutlinedTextField(
                value = selectedVoiceName,
                onValueChange = {},
                readOnly = true,
                label = { Text("音色") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(voiceExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(expanded = voiceExpanded, onDismissRequest = { onVoiceExpandedChange(false) }) {
                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = { Text("${voice.name} - ${voice.description}") },
                        onClick = { onVoiceSelected(voice) }
                    )
                }
            }
        }
        OutlinedTextField(
            value = selectedStyle,
            onValueChange = onStyleChange,
            label = { Text("风格") },
            placeholder = { Text("如：温柔、粤语、四川话") },
            modifier = Modifier.fillMaxWidth()
        )
        Text("段间间隔: ${readerGapMs}ms", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = readerGapMs.toFloat(),
            onValueChange = { onGapChange(it.roundToInt()) },
            onValueChangeFinished = onGapChangeFinished,
            valueRange = 0f..3000f
        )
        Text("克隆/设计请求间隔: ${conservativeRequestIntervalMs}ms", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = conservativeRequestIntervalMs.toFloat(),
            onValueChange = { onConservativeRequestIntervalChange(it.roundToInt()) },
            onValueChangeFinished = onConservativeRequestIntervalChangeFinished,
            valueRange = 500f..30000f
        )
        Text("失败重试次数: ${retryCount}次", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = retryCount.toFloat(),
            onValueChange = { onRetryCountChange(it.roundToInt()) },
            onValueChangeFinished = onRetryCountChangeFinished,
            valueRange = 0f..8f,
            steps = 7
        )
        Text("重试基础等待: ${retryBaseDelayMs}ms", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = retryBaseDelayMs.toFloat(),
            onValueChange = { onRetryBaseDelayChange(it.roundToInt()) },
            onValueChangeFinished = onRetryBaseDelayChangeFinished,
            valueRange = 500f..15000f
        )
        Text("定时停止: " + if (readerSleepMinutes == 0) "关闭" else "${readerSleepMinutes}分钟", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = readerSleepMinutes.toFloat(),
            onValueChange = { onSleepChange(it.roundToInt()) },
            onValueChangeFinished = onSleepChangeFinished,
            valueRange = 0f..180f,
            steps = 17
        )
        Text("播放章数后停止: " + if (readerStopAfterChapters == 0) "关闭" else "${readerStopAfterChapters}章", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = readerStopAfterChapters.toFloat(),
            onValueChange = { onStopAfterChaptersChange(it.roundToInt()) },
            onValueChangeFinished = onStopAfterChaptersChangeFinished,
            valueRange = 0f..20f,
            steps = 19
        )
        Spacer(Modifier.height(12.dp))
    }
}

private fun android.content.Context.queryDisplayName(uri: Uri): String? {
    val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
    return cursor?.use {
        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && it.moveToFirst()) it.getString(nameIndex) else null
    }
}
