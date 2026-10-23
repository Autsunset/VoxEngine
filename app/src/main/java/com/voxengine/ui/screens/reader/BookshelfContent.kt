package com.voxengine.ui.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxengine.data.AppThemeStyle
import com.voxengine.data.ReaderBookEntity
import com.voxengine.ui.theme.LocalAppThemeStyle
import com.voxengine.ui.components.VoxCard as Card

@Composable
internal fun Bookshelf(
    books: List<ReaderBookEntity>,
    onImport: () -> Unit,
    onOpen: (ReaderBookEntity) -> Unit,
    onDelete: (ReaderBookEntity) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val isGlass = LocalAppThemeStyle.current == AppThemeStyle.LIQUID_GLASS
    val layoutDirection = LocalLayoutDirection.current
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val spineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    val bookShape = if (isGlass) {
        RoundedCornerShape(topStart = 8.dp, topEnd = 24.dp, bottomEnd = 24.dp, bottomStart = 8.dp)
    } else {
        CardDefaults.shape
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (isGlass) 156.dp * fontScale else 148.dp),
        modifier = modifier.fillMaxSize().then(if (isGlass) Modifier else Modifier.padding(16.dp)),
        contentPadding = if (isGlass) {
            PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDirection) + 16.dp,
                top = contentPadding.calculateTopPadding() + 16.dp,
                end = contentPadding.calculateEndPadding(layoutDirection) + 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp
            )
        } else PaddingValues(0.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(
            key = "import",
            span = { GridItemSpan(if (isGlass && books.isEmpty()) maxLineSpan else 1) }
        ) {
            val importHeight = if (books.isEmpty()) 244.dp else 208.dp
            Card(
                modifier = Modifier
                    .then(if (isGlass) Modifier.heightIn(min = importHeight) else Modifier.height(176.dp))
                    .fillMaxWidth()
                    .clickable(
                        onClickLabel = "导入小说",
                        role = Role.Button,
                        onClick = onImport
                    )
            ) {
                Column(
                    modifier = Modifier
                        .then(
                            if (isGlass) Modifier.fillMaxWidth().heightIn(min = importHeight)
                            else Modifier.fillMaxSize()
                        )
                        .padding(if (isGlass) 20.dp else 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = if (isGlass) {
                            Modifier
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape)
                                .padding(16.dp)
                        } else Modifier
                    ) {
                        Icon(
                            Icons.Default.UploadFile,
                            contentDescription = null,
                            tint = if (isGlass) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            modifier = Modifier.size(if (isGlass) 28.dp else 34.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (isGlass && books.isEmpty()) "导入第一本书" else "导入小说",
                        style = if (isGlass) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall
                    )
                    if (isGlass) Spacer(Modifier.height(6.dp))
                    Text("支持 TXT / EPUB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("可一次选择多本", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(books, key = { it.uri }) { book ->
            Card(
                modifier = Modifier
                    .then(if (isGlass) Modifier.heightIn(min = 208.dp) else Modifier.height(176.dp))
                    .fillMaxWidth()
                    .clickable(onClickLabel = "打开书籍", role = Role.Button) { onOpen(book) },
                shape = bookShape
            ) {
                Column(
                    modifier = Modifier
                        .then(
                            if (isGlass) {
                                Modifier.fillMaxWidth().heightIn(min = 208.dp).drawBehind {
                                    drawLine(
                                        color = spineColor,
                                        start = Offset(5.dp.toPx(), 14.dp.toPx()),
                                        end = Offset(5.dp.toPx(), size.height - 14.dp.toPx()),
                                        strokeWidth = 2.dp.toPx()
                                    )
                                }
                            } else Modifier.fillMaxSize()
                        )
                        .padding(if (isGlass) 16.dp else 12.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    if (isGlass) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 14.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (book.title.endsWith(".epub", ignoreCase = true)) "EPUB" else "TEXT",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            book.title.replace(BOOK_EXTENSION, ""),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = if (isGlass) FontFamily.Serif else null,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onDelete(book) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                    if (isGlass) Spacer(Modifier.height(18.dp))
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

private val BOOK_EXTENSION = Regex("\\.(txt|epub)$", RegexOption.IGNORE_CASE)
