package com.voxengine.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.voxengine.data.AppThemeStyle

@Composable
fun ThemeStylePicker(
    selectedStyle: AppThemeStyle,
    onStyleSelected: (AppThemeStyle) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    VoxCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("界面风格", style = MaterialTheme.typography.titleMedium)
                Text(
                    "即时切换，保留所有设置与内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ThemeStyleOption(
                    style = AppThemeStyle.LIQUID_GLASS,
                    title = "液态玻璃",
                    description = "新版 · 通透光感",
                    selected = selectedStyle == AppThemeStyle.LIQUID_GLASS,
                    enabled = enabled,
                    onClick = { onStyleSelected(AppThemeStyle.LIQUID_GLASS) }
                )
                ThemeStyleOption(
                    style = AppThemeStyle.CLASSIC,
                    title = "经典",
                    description = "原版 · 熟悉如初",
                    selected = selectedStyle == AppThemeStyle.CLASSIC,
                    enabled = enabled,
                    onClick = { onStyleSelected(AppThemeStyle.CLASSIC) }
                )
            }
            Text(
                if (enabled) "两种风格均支持深色模式" else "正在保存主题…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ThemeStyleOption(
    style: AppThemeStyle,
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .clip(shape)
            .background(
                if (selected) colors.primary.copy(alpha = 0.08f)
                else colors.surface.copy(alpha = 0.65f)
            )
            .border(
                width = 1.dp,
                color = if (selected) colors.primary.copy(alpha = 0.65f)
                else colors.outlineVariant.copy(alpha = 0.7f),
                shape = shape
            )
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = { if (!selected) onClick() }
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThemeStylePreview(style, modifier = Modifier.size(56.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled
        )
    }
}

@Composable
private fun ThemeStylePreview(style: AppThemeStyle, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.clip(RoundedCornerShape(13.dp))) {
        val glass = style == AppThemeStyle.LIQUID_GLASS
        val width = size.width
        val height = size.height
        val accent = if (glass) Color(0xFF176B5B) else Color(0xFF6750A4)
        drawRect(
            brush = Brush.linearGradient(
                if (glass) listOf(Color(0xFFF2F6F3), Color(0xFFD5EEE7))
                else listOf(Color(0xFFFEF7FF), Color(0xFFF4EDF7))
            )
        )
        if (glass) {
            drawCircle(
                color = Color(0xFFB4DCD1),
                radius = width * 0.38f,
                center = Offset(width * 0.13f, height * 0.46f)
            )
            drawCircle(
                color = Color(0xFFEAD3B2),
                radius = width * 0.3f,
                center = Offset(width * 0.86f, height * 0.82f)
            )
        }
        drawRoundRect(
            color = accent,
            topLeft = Offset(width * 0.15f, height * 0.15f),
            size = Size(width * 0.42f, height * 0.055f),
            cornerRadius = CornerRadius(width * 0.03f)
        )
        drawRoundRect(
            color = if (glass) Color.White.copy(alpha = 0.78f) else Color(0xFFE9DDFF),
            topLeft = Offset(width * 0.12f, height * 0.3f),
            size = Size(width * 0.76f, height * 0.36f),
            cornerRadius = CornerRadius(width * if (glass) 0.13f else 0.06f)
        )
        drawRoundRect(
            color = accent.copy(alpha = 0.5f),
            topLeft = Offset(width * 0.24f, height * 0.4f),
            size = Size(width * 0.4f, height * 0.045f),
            cornerRadius = CornerRadius(width * 0.03f)
        )
        drawRoundRect(
            color = accent.copy(alpha = 0.22f),
            topLeft = Offset(width * 0.24f, height * 0.51f),
            size = Size(width * 0.28f, height * 0.04f),
            cornerRadius = CornerRadius(width * 0.03f)
        )
        drawRoundRect(
            color = if (glass) Color.White.copy(alpha = 0.9f) else Color(0xFFE5DDEA),
            topLeft = Offset(width * 0.16f, height * 0.77f),
            size = Size(width * 0.68f, height * 0.14f),
            cornerRadius = CornerRadius(width * if (glass) 0.1f else 0.025f)
        )
        drawCircle(
            color = accent,
            radius = width * 0.035f,
            center = Offset(width * 0.32f, height * 0.84f)
        )
    }
}
