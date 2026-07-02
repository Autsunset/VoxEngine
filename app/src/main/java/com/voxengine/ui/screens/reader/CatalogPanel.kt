package com.voxengine.ui.screens.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxengine.reader.TxtChapter

@Composable
internal fun CatalogPanel(
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
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
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
                        fontWeight = if (index == currentChapterIndex) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                        modifier = Modifier.padding(start = 10.dp).weight(1f)
                    )
                }
            }
        }
    }
}
