package com.voxengine.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxengine.data.AppThemeStyle

val LocalAppThemeStyle = staticCompositionLocalOf { AppThemeStyle.LIQUID_GLASS }
internal val LocalGlassDarkTheme = staticCompositionLocalOf { false }

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFF625B71),
    tertiary = Color(0xFF7D5260),
    surface = Color(0xFFFEF7FF),
    background = Color(0xFFFEF7FF)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFCFBCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    secondary = Color(0xFFCCC2DC),
    tertiary = Color(0xFFEFB8C8),
    surface = Color(0xFF141218),
    background = Color(0xFF141218)
)

private val GlassLightColorScheme = lightColorScheme(
    primary = Color(0xFF176B5B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3EEE4),
    onPrimaryContainer = Color(0xFF103D33),
    secondary = Color(0xFF52665F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0EBE5),
    onSecondaryContainer = Color(0xFF243A31),
    tertiary = Color(0xFF825C35),
    tertiaryContainer = Color(0xFFF5E3CB),
    onTertiaryContainer = Color(0xFF483216),
    background = Color(0xFFF2F6F3),
    onBackground = Color(0xFF192D28),
    surface = Color(0xFFF6F9F6),
    onSurface = Color(0xFF192D28),
    surfaceVariant = Color(0xFFE0EAE3),
    onSurfaceVariant = Color(0xFF4E625A),
    surfaceContainerLowest = Color(0xFFFAFCFA),
    surfaceContainerLow = Color(0xFFF0F5EF),
    surfaceContainer = Color(0xFFEAF1EA),
    surfaceContainerHigh = Color(0xFFE4ECE4),
    surfaceContainerHighest = Color(0xFFDEE8DF),
    outline = Color(0xFF74877D),
    outlineVariant = Color(0xFFC3D1C7)
)

private val GlassDarkColorScheme = darkColorScheme(
    primary = Color(0xFFA0DACB),
    onPrimary = Color(0xFF103B32),
    primaryContainer = Color(0xFF284D43),
    onPrimaryContainer = Color(0xFFCAEFE1),
    secondary = Color(0xFFB5C9BF),
    secondaryContainer = Color(0xFF344A41),
    onSecondaryContainer = Color(0xFFD6E8DD),
    tertiary = Color(0xFFE7C39A),
    tertiaryContainer = Color(0xFF56432F),
    onTertiaryContainer = Color(0xFFF8E1C4),
    background = Color(0xFF101C1C),
    onBackground = Color(0xFFE1EEE6),
    surface = Color(0xFF142321),
    onSurface = Color(0xFFE1EEE6),
    surfaceVariant = Color(0xFF30473F),
    onSurfaceVariant = Color(0xFFB7C9C0),
    surfaceContainerLowest = Color(0xFF0D1817),
    surfaceContainerLow = Color(0xFF182A25),
    surfaceContainer = Color(0xFF20342D),
    surfaceContainerHigh = Color(0xFF293E35),
    surfaceContainerHighest = Color(0xFF32483E),
    outline = Color(0xFF8DA399),
    outlineVariant = Color(0xFF435C50)
)

private val GlassShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

private val GlassTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.8).sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.6).sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp)
)

@Composable
fun VoxEngineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    themeStyle: AppThemeStyle = AppThemeStyle.LIQUID_GLASS,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        themeStyle == AppThemeStyle.LIQUID_GLASS ->
            if (darkTheme) GlassDarkColorScheme else GlassLightColorScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    CompositionLocalProvider(
        LocalAppThemeStyle provides themeStyle,
        LocalGlassDarkTheme provides darkTheme
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = if (themeStyle == AppThemeStyle.LIQUID_GLASS) GlassShapes else Shapes(),
            typography = if (themeStyle == AppThemeStyle.LIQUID_GLASS) GlassTypography else Typography(),
            content = content
        )
    }
}
