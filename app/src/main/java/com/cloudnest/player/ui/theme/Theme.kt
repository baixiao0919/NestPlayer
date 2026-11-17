package com.cloudnest.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- 品牌色：电紫 → 薄荷青，偏年轻 / 影音感 ----
val BrandPurple = Color(0xFF7C5CFF)
val BrandViolet = Color(0xFFA96BFF)
val BrandCyan = Color(0xFF2EE6D6)
val BrandPink = Color(0xFFFF5C8A)
val Gold = Color(0xFFFFC46B)

val Accent = BrandPurple
val AccentSoft = BrandViolet

// ---- 深色底 ----
val DarkBg = Color(0xFF07070C)
val DarkSurface = Color(0xFF111118)
val DarkSurface2 = Color(0xFF1B1B24)
val DarkLine = Color(0xFF2B2B38)
val TextPrimary = Color(0xFFF4F4F8)
val TextSecondary = Color(0xFF9E9EB2)

// ---- 浅色底 ----
val LightBg = Color(0xFFF6F5FB)
val LightSurface = Color(0xFFFFFFFF)
val LightSurface2 = Color(0xFFEFEDF8)

val BrandGradient = Brush.linearGradient(listOf(BrandPurple, BrandCyan))
val BrandGradientV = Brush.verticalGradient(listOf(BrandPurple, BrandCyan))
val PinkGradient = Brush.linearGradient(listOf(BrandPink, BrandViolet))

private val DarkColors = darkColorScheme(
    primary = BrandPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2B2358),
    onPrimaryContainer = Color(0xFFE4DEFF),
    secondary = BrandCyan,
    onSecondary = Color(0xFF04241F),
    secondaryContainer = Color(0xFF0E3A36),
    tertiary = BrandPink,
    onTertiary = Color.White,
    background = DarkBg,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurface2,
    onSurfaceVariant = TextSecondary,
    outline = DarkLine,
    outlineVariant = Color(0xFF20202B),
    error = Color(0xFFFF6B6B),
    scrim = Color(0xCC000000)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6440F0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7DEFF),
    onPrimaryContainer = Color(0xFF20124E),
    secondary = Color(0xFF00998C),
    onSecondary = Color.White,
    tertiary = Color(0xFFE83D6E),
    background = LightBg,
    onBackground = Color(0xFF14131A),
    surface = LightSurface,
    onSurface = Color(0xFF14131A),
    surfaceVariant = LightSurface2,
    onSurfaceVariant = Color(0xFF625F70),
    outline = Color(0xFFD8D4E6)
)

private val NestShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * 文案排版：把「标题 / 分区标题」加粗、字距压紧一点，观感更利落。
 * 只覆盖常用样式，未覆盖的沿用 Material3 默认。
 */
private fun nestTypography(base: androidx.compose.material3.Typography) = base.copy(
        headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        bodyMedium = TextStyle(fontSize = 14.sp),
        labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 0.2.sp)
)

@Composable
fun NestTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = NestShapes,
        typography = nestTypography(MaterialTheme.typography),
        content = content
    )
}
