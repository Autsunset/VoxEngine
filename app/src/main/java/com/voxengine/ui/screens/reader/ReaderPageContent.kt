package com.voxengine.ui.screens.reader

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxengine.data.ReaderBookEntity
import com.voxengine.reader.TxtChapter
import com.voxengine.reader.TxtPage
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
@Composable
internal fun ReaderPage(
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

internal fun measurePagesForViewport(
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
