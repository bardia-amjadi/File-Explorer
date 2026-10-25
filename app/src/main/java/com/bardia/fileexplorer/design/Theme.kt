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

/** GNOME/Adwaita-inspired "Linux desktop" palette: flat surfaces, blue accent, blue folders, monospace file names. */
object Fluent {
    val Accent = Color(0xFF3584E4)
    val AccentDark = Color(0xFF78AEED)
    val FolderYellow = Color(0xFF62A0EA)      // folders are blue like the app icon (name kept for existing references)
    val FolderYellowDark = Color(0xFF99C1F1)
    val Success = Color(0xFF26A269)
    val Danger = Color(0xFFC01C28)
}

private val LightColors = lightColorScheme(
    primary = Fluent.Accent, onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E6FA), onPrimaryContainer = Color(0xFF0B2E5C),
    secondary = Color(0xFF5E5C64), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E6EA), onSecondaryContainer = Color(0xFF241F31),
    tertiary = Color(0xFF26A269), onTertiary = Color.White,
    background = Color(0xFFFAFAFA), onBackground = Color(0xFF2E3436),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF2E3436),
    surfaceVariant = Color(0xFFEBEBEB), onSurfaceVariant = Color(0xFF5E5C64),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F5F4),
    surfaceContainer = Color(0xFFF0EFEE), surfaceContainerHigh = Color(0xFFEBEBEB), surfaceContainerHighest = Color(0xFFDEDDDA),
    outline = Color(0xFF9A9996), outlineVariant = Color(0xFFDEDDDA),
    error = Fluent.Danger, onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Fluent.AccentDark, onPrimary = Color(0xFF062A55),
    primaryContainer = Color(0xFF1C4A86), onPrimaryContainer = Color(0xFFD7E6FA),
    secondary = Color(0xFFC0BFBC), onSecondary = Color(0xFF241F31),
    secondaryContainer = Color(0xFF3D3846), onSecondaryContainer = Color(0xFFDEDDDA),
    tertiary = Color(0xFF8FF0A4), onTertiary = Color(0xFF0B3A1F),
    background = Color(0xFF1E1E1E), onBackground = Color(0xFFEEEEEC),
    surface = Color(0xFF242424), onSurface = Color(0xFFEEEEEC),
    surfaceVariant = Color(0xFF303030), onSurfaceVariant = Color(0xFFC0BFBC),
    surfaceContainerLowest = Color(0xFF171717), surfaceContainerLow = Color(0xFF1E1E1E),
    surfaceContainer = Color(0xFF242424), surfaceContainerHigh = Color(0xFF303030), surfaceContainerHighest = Color(0xFF3A3A3A),
    outline = Color(0xFF77767B), outlineVariant = Color(0xFF3D3D3D),
    error = Color(0xFFFF7B63), onError = Color(0xFF4A0A00)
)

private val FluentShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp)
)

private val Mono = FontFamily.Monospace
private val Sans = FontFamily.SansSerif

private val FluentTypography = Typography(
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 21.sp),
    titleSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontFamily = Mono, fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = Mono, fontSize = 11.sp, lineHeight = 15.sp),
    labelLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp)
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
