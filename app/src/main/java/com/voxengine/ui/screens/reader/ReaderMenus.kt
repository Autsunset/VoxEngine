package com.voxengine.ui.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxengine.engine.VoiceInfo
import com.voxengine.reader.RoleProfile
import com.voxengine.reader.TxtChapter
import kotlin.math.roundToInt

internal const val READER_PANEL_NONE = 0
internal const val READER_PANEL_CATALOG = 1
internal const val READER_PANEL_SETTINGS = 2

@Composable
internal fun ReaderTopMenu(
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

@Composable
internal fun ReaderToolButton(
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
internal fun ReaderBottomMenu(
    chapters: List<TxtChapter>,
    currentChapterIndex: Int,
    currentPageIndex: Int,
    pageCount: Int,
    panelMode: Int,
    selectedVoiceName: String,
    selectedStyle: String,
    voices: List<VoiceInfo>,
    voiceExpanded: Boolean,
    onVoiceExpandedChange: (Boolean) -> Unit,
    onVoiceSelected: (VoiceInfo) -> Unit,
    onStyleChange: (String) -> Unit,
    roleEnabled: Boolean,
    onRoleEnabledChange: (Boolean) -> Unit,
    roleProfile: RoleProfile,
    onNarrationVoiceChange: (VoiceInfo?) -> Unit,
    onNarrationStyleChange: (String) -> Unit,
    onDialogueVoiceChange: (VoiceInfo?) -> Unit,
    onDialogueStyleChange: (String) -> Unit,
    onCharacterSave: (String, String, String) -> Unit,
    onCharacterRemove: (String) -> Unit,
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
                    roleEnabled = roleEnabled,
                    onRoleEnabledChange = onRoleEnabledChange,
                    roleProfile = roleProfile,
                    onNarrationVoiceChange = onNarrationVoiceChange,
                    onNarrationStyleChange = onNarrationStyleChange,
                    onDialogueVoiceChange = onDialogueVoiceChange,
                    onDialogueStyleChange = onDialogueStyleChange,
                    onCharacterSave = onCharacterSave,
                    onCharacterRemove = onCharacterRemove,
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
