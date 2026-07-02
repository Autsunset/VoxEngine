package com.voxengine.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import com.voxengine.reader.ReaderPlaybackService
import com.voxengine.reader.PlaybackSnapshot
import com.voxengine.ui.screens.reader.Bookshelf
import com.voxengine.ui.screens.reader.ReaderPage
import com.voxengine.ui.screens.reader.ReaderTopMenu
import com.voxengine.ui.screens.reader.ReaderBottomMenu
import com.voxengine.ui.screens.reader.READER_PANEL_CATALOG
import com.voxengine.ui.screens.reader.READER_PANEL_NONE
import com.voxengine.ui.screens.reader.READER_PANEL_SETTINGS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    onReadingModeChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = LocalClipboardManager.current
    val viewModel: ReaderViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val books by viewModel.books.collectAsState()

    // 纯 UI 状态：菜单显隐 / 面板 / 音色下拉，无需在配置变更后存活，留在组合内。
    var menuVisible by remember { mutableStateOf(false) }
    var panelMode by remember { mutableIntStateOf(READER_PANEL_NONE) }
    var voiceExpanded by remember { mutableStateOf(false) }

    fun toggleReaderMenu() {
        menuVisible = !menuVisible
        if (!menuVisible) panelMode = READER_PANEL_NONE
    }

    DisposableEffect(Unit) {
        onDispose { onReadingModeChanged(false) }
    }

    LaunchedEffect(uiState.currentBook != null) {
        onReadingModeChanged(uiState.currentBook != null)
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importBooks(uris)
    }

    // 回前台时重新同步播放状态（服务可能在后台推进了进度）。
    DisposableEffect(lifecycleOwner, uiState.currentBook?.uri) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.syncPlaybackSnapshot(ReaderPlaybackService.getPlaybackSnapshot(uiState.currentBook?.uri))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val book = uiState.currentBook
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
                onOpen = { item ->
                    menuVisible = false
                    panelMode = READER_PANEL_NONE
                    viewModel.openBook(item)
                },
                onDelete = { item -> viewModel.deleteBook(item) },
                modifier = Modifier.padding(padding)
            )
        }
        return
    }

    // 监听听书服务广播（进度 / 播放状态）。receiver 闭包读取实时 uiState，故仅在书本切换时重建。
    DisposableEffect(book.uri) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val uri = intent.getStringExtra(ReaderPlaybackService.EXTRA_URI) ?: return
                if (uri != book.uri) return
                viewModel.syncPlaybackSnapshot(
                    PlaybackSnapshot(
                        uri = uri,
                        chapterIndex = intent.getIntExtra(ReaderPlaybackService.EXTRA_CHAPTER_INDEX, uiState.chapterIndex),
                        pageIndex = intent.getIntExtra(ReaderPlaybackService.EXTRA_PAGE_INDEX, uiState.pageIndex),
                        paragraphIndex = intent.getIntExtra(ReaderPlaybackService.EXTRA_PARAGRAPH_INDEX, 0),
                        isListening = intent.getBooleanExtra(
                            ReaderPlaybackService.EXTRA_IS_LISTENING,
                            intent.action == ReaderPlaybackService.ACTION_PROGRESS
                        ),
                        isPaused = intent.getBooleanExtra(ReaderPlaybackService.EXTRA_IS_PAUSED, false)
                    )
                )
            }
        }
        val filter = IntentFilter().apply {
            addAction(ReaderPlaybackService.ACTION_PROGRESS)
            addAction(ReaderPlaybackService.ACTION_PLAYBACK_STATE)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    val canListen = uiState.isEngineConfigured && uiState.pages.isNotEmpty()

    Box(modifier = Modifier.fillMaxSize()) {
        ReaderPage(
            book = book,
            chapters = uiState.chapters,
            pages = uiState.pages,
            chapterIndex = uiState.chapterIndex,
            pageIndex = uiState.pageIndex,
            isLoadingBook = uiState.isLoadingBook,
            selectedParagraphIndex = uiState.selectedParagraphIndex,
            pageAnimationKey = uiState.pageAnimationKey,
            pageAnimationForward = uiState.pageAnimationForward,
            onPageTargetChanged = { viewModel.onPageTargetChanged(it) },
            onPagesMeasured = { measuredChapterIndex, measuredPages ->
                viewModel.onPagesMeasured(measuredChapterIndex, measuredPages)
            },
            onParagraphTap = {
                if (uiState.selectedParagraphIndex != null) viewModel.clearSelectedParagraph() else toggleReaderMenu()
            },
            onParagraphLongPress = { index, _ -> viewModel.selectParagraph(index) },
            onCopyParagraph = { paragraph ->
                clipboard.setText(AnnotatedString(paragraph))
                viewModel.setStatus("已复制该段")
            },
            onReadFromParagraph = { index -> viewModel.startListening(index) },
            onPreviousPage = { viewModel.previousPage() },
            onNextPage = { viewModel.nextPage() },
            onCenterTap = { toggleReaderMenu() }
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
                chapterTitle = uiState.chapters.getOrNull(uiState.chapterIndex)?.title.orEmpty(),
                onBack = {
                    menuVisible = false
                    panelMode = READER_PANEL_NONE
                    viewModel.closeBook()
                },
                onImport = { openDocumentLauncher.launch(arrayOf("text/plain", "text/*")) },
                modifier = Modifier.align(Alignment.TopCenter)
            )
            ReaderBottomMenu(
                chapters = uiState.chapters,
                currentChapterIndex = uiState.chapterIndex,
                currentPageIndex = uiState.pageIndex,
                pageCount = uiState.pages.size,
                panelMode = panelMode,
                selectedVoiceName = uiState.selectedVoiceName,
                selectedStyle = uiState.selectedStyle,
                voices = uiState.voices,
                voiceExpanded = voiceExpanded,
                onVoiceExpandedChange = { voiceExpanded = it },
                onVoiceSelected = { voice ->
                    viewModel.selectVoice(voice)
                    voiceExpanded = false
                },
                onStyleChange = { viewModel.setStyle(it) },
                roleEnabled = uiState.roleEnabled,
                onRoleEnabledChange = { viewModel.onRoleEnabledChange(it) },
                roleProfile = uiState.roleProfile,
                onNarrationVoiceChange = { viewModel.setNarrationVoice(it) },
                onNarrationStyleChange = { viewModel.setNarrationStyle(it) },
                onDialogueVoiceChange = { viewModel.setDialogueVoice(it) },
                onDialogueStyleChange = { viewModel.setDialogueStyle(it) },
                onCharacterSave = { name, voice, style -> viewModel.saveCharacterVoice(name, voice, style) },
                onCharacterRemove = { name -> viewModel.removeCharacterVoice(name) },
                readerGapMs = uiState.readerGapMs,
                readerSleepMinutes = uiState.readerSleepMinutes,
                readerStopAfterChapters = uiState.readerStopAfterChapters,
                conservativeRequestIntervalMs = uiState.conservativeRequestIntervalMs,
                retryCount = uiState.retryCount,
                retryBaseDelayMs = uiState.retryBaseDelayMs,
                onGapChange = { viewModel.onGapChange(it) },
                onSleepChange = { viewModel.onSleepChange(it) },
                onStopAfterChaptersChange = { viewModel.onStopAfterChaptersChange(it) },
                onConservativeRequestIntervalChange = { viewModel.onConservativeRequestIntervalChange(it) },
                onRetryCountChange = { viewModel.onRetryCountChange(it) },
                onRetryBaseDelayChange = { viewModel.onRetryBaseDelayChange(it) },
                onGapChangeFinished = { viewModel.commitGap() },
                onSleepChangeFinished = { viewModel.commitSleep() },
                onStopAfterChaptersChangeFinished = { viewModel.commitStopAfterChapters() },
                onConservativeRequestIntervalChangeFinished = { viewModel.commitConservativeRequestInterval() },
                onRetryCountChangeFinished = { viewModel.commitRetryCount() },
                onRetryBaseDelayChangeFinished = { viewModel.commitRetryBaseDelay() },
                isListening = uiState.isListening,
                isPaused = uiState.isPaused,
                canListen = canListen,
                statusText = uiState.statusText,
                onPanelChange = { panelMode = if (panelMode == it) READER_PANEL_NONE else it },
                onChapterSelected = { index ->
                    viewModel.setPosition(index, 0)
                    panelMode = READER_PANEL_NONE
                },
                onPreviousChapter = { viewModel.previousChapter() },
                onNextChapter = { viewModel.nextChapter() },
                onPreviousPage = { viewModel.previousPage() },
                onNextPage = { viewModel.nextPage() },
                onPageSelected = { page -> viewModel.setPosition(uiState.chapterIndex, page) },
                onStartListening = { viewModel.startListening() },
                onPauseListening = { viewModel.pauseListening() },
                onResumeListening = { viewModel.resumeListening() },
                onStopListening = { viewModel.stopListening() },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}
