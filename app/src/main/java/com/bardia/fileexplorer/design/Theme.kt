@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.bardia.fileexplorer.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bardia.fileexplorer.core.ThemeMode

/** Fluent-inspired palette: Windows 11 accent blue, Mica-like neutral layers. */
object Fluent {
    val Accent = Color(0xFF0067C0)
    val AccentDark = Color(0xFF60CDFF)
    val FolderYellow = Color(0xFFF2B93B)
    val FolderYellowDark = Color(0xFFE8A92A)
    val Success = Color(0xFF0F7B0F)
    val Danger = Color(0xFFC42B1C)
}

private val LightColors = lightColorScheme(
    primary = Fluent.Accent, onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E8F8), onPrimaryContainer = Color(0xFF00305A),
    secondary = Color(0xFF4F6378), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EAF2), onSecondaryContainer = Color(0xFF1B2733),
    tertiary = Color(0xFF00758F), onTertiary = Color.White,
    background = Color(0xFFF3F3F3), onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFEDEDED), onSurfaceVariant = Color(0xFF505050),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF9F9F9),
    surfaceContainer = Color(0xFFF3F3F3), surfaceContainerHigh = Color(0xFFEDEDED), surfaceContainerHighest = Color(0xFFE6E6E6),
    outline = Color(0xFF8A8A8A), outlineVariant = Color(0xFFD9D9D9),
    error = Fluent.Danger, onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Fluent.AccentDark, onPrimary = Color(0xFF00334F),
    primaryContainer = Color(0xFF00497A), onPrimaryContainer = Color(0xFFCCE5FF),
    secondary = Color(0xFFB7C8DA), onSecondary = Color(0xFF22323F),
    secondaryContainer = Color(0xFF384857), onSecondaryContainer = Color(0xFFD3E4F6),
    tertiary = Color(0xFF6ED0E8), onTertiary = Color(0xFF00363F),
    background = Color(0xFF202020), onBackground = Color(0xFFF0F0F0),
    surface = Color(0xFF2B2B2B), onSurface = Color(0xFFF0F0F0),
    surfaceVariant = Color(0xFF383838), onSurfaceVariant = Color(0xFFC7C7C7),
    surfaceContainerLowest = Color(0xFF191919), surfaceContainerLow = Color(0xFF242424),
    surfaceContainer = Color(0xFF2B2B2B), surfaceContainerHigh = Color(0xFF333333), surfaceContainerHighest = Color(0xFF3B3B3B),
    outline = Color(0xFF8F8F8F), outlineVariant = Color(0xFF454545),
    error = Color(0xFFFF99A4), onError = Color(0xFF5F0010)
)

private val FluentShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp)
)

private val FluentTypography = Typography(
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp)
)

@Composable
fun FileExplorerTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = FluentShapes,
        typography = FluentTypography,
        content = content
    )
}
