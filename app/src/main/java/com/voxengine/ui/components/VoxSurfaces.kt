package com.voxengine.ui.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxengine.data.AppThemeStyle
import com.voxengine.ui.theme.LocalAppThemeStyle
import com.voxengine.ui.theme.LocalGlassDarkTheme

val LocalVoxNavigationInset = staticCompositionLocalOf { 0.dp }

@Composable
fun VoxCard(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    colors: CardColors? = null,
    elevation: CardElevation? = null,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = LocalAppThemeStyle.current == AppThemeStyle.LIQUID_GLASS
    val dark = LocalGlassDarkTheme.current
    Card(
        modifier = modifier,
        shape = shape ?: if (glass) RoundedCornerShape(26.dp) else CardDefaults.shape,
        colors = colors ?: if (glass) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(
                    alpha = if (Build.VERSION.SDK_INT < 31) 0.94f else if (dark) 0.80f else 0.70f
                ),
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        } else CardDefaults.cardColors(),
        elevation = elevation ?: CardDefaults.cardElevation(),
        border = border ?: if (glass) {
            BorderStroke(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = if (dark) 0.23f else 0.94f),
                        Color.White.copy(alpha = if (dark) 0.04f else 0.32f),
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
                    )
                )
            )
        } else null,
        content = content
    )
}

@Composable
fun VoxScreenScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = if (LocalAppThemeStyle.current == AppThemeStyle.LIQUID_GLASS) {
        Color.Transparent
    } else MaterialTheme.colorScheme.background,
    contentColor: Color = MaterialTheme.colorScheme.onBackground,
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit
) {
    val navigationInset = LocalVoxNavigationInset.current
    val layoutDirection = LocalLayoutDirection.current
    Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        floatingActionButtonPosition = floatingActionButtonPosition,
        containerColor = containerColor,
        contentColor = contentColor,
        contentWindowInsets = contentWindowInsets
    ) { padding ->
        content(
            PaddingValues(
                start = padding.calculateStartPadding(layoutDirection),
                top = padding.calculateTopPadding(),
                end = padding.calculateEndPadding(layoutDirection),
                bottom = maxOf(padding.calculateBottomPadding(), navigationInset)
            )
        )
    }
}

@Composable
fun VoxPageHeader(
    title: String,
    subtitle: String,
    eyebrow: String,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
            .statusBarsPadding()
            .padding(start = 22.dp, end = 18.dp, top = 18.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = eyebrow,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.5.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )
            actions()
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
