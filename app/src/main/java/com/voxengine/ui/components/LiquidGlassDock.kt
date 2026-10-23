package com.voxengine.ui.components

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.voxengine.ui.navigation.BottomNavItem
import com.voxengine.ui.navigation.Screen
import com.voxengine.ui.theme.GlassBackdrop
import com.voxengine.ui.theme.LocalGlassDarkTheme
import com.voxengine.ui.theme.glassBackdrop

@Composable
fun LiquidGlassDock(
    items: List<BottomNavItem>,
    selectedIndex: Int,
    backdrop: GlassBackdrop,
    onSelect: (BottomNavItem) -> Unit
) {
    val dark = LocalGlassDarkTheme.current
    val shape = RoundedCornerShape(28.dp)
    val position by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 1f, stiffness = 500f),
        label = "glass_tab_position"
    )
    Box(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .shadow(
                    elevation = 12.dp,
                    shape = shape,
                    ambientColor = Color(0xFF163F36).copy(alpha = 0.12f),
                    spotColor = Color(0xFF163F36).copy(alpha = 0.16f)
                )
                .then(
                    if (Build.VERSION.SDK_INT >= 31) Modifier.glassBackdrop(backdrop, shape)
                    else Modifier.clip(shape)
                )
                .background(
                    MaterialTheme.colorScheme.surface.copy(
                        alpha = if (Build.VERSION.SDK_INT < 31) 0.97f else if (dark) 0.87f else 0.76f
                    )
                )
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = if (dark) 0.34f else 0.98f),
                            Color.White.copy(alpha = 0.08f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.23f)
                        )
                    ),
                    shape
                )
                .padding(6.dp)
        ) {
            val cellWidth = maxWidth / items.size
            Box(Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset((cellWidth * position).roundToPx(), 0) }
                        .width(cellWidth)
                        .fillMaxSize()
                        .padding(horizontal = 2.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f),
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
                                )
                            )
                        )
                        .border(
                            0.5.dp,
                            Color.White.copy(alpha = if (dark) 0.20f else 0.85f),
                            RoundedCornerShape(22.dp)
                        )
                )
            }
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                items.forEachIndexed { index, item ->
                    val interactionSource = remember { MutableInteractionSource() }
                    val pressed by interactionSource.collectIsPressedAsState()
                    val scale by animateFloatAsState(
                        targetValue = if (pressed) 0.94f else 1f,
                        animationSpec = spring(dampingRatio = 1f, stiffness = 900f),
                        label = "glass_tab_press"
                    )
                    val selected = index == selectedIndex
                    val tint by animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        label = "glass_tab_tint"
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 62.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = { onSelect(item) }
                            )
                            .graphicsLayer { scaleX = scale; scaleY = scale }
                            .padding(vertical = 8.dp, horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
                    ) {
                        Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        Text(
                            text = when (item.screen) {
                                Screen.VoiceManage -> "音色"
                                Screen.Test -> "试听"
                                else -> item.screen.title
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = tint,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
