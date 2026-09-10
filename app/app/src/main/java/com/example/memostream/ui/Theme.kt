package com.example.memostream.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class MemoColors(
    val bg: Color,
    val bgSoft: Color,
    val bgHover: Color,
    val fg: Color,
    val fgSoft: Color,
    val border: Color,
    val accent: Color,
    val accentSoft: Color,
    val danger: Color,
    val markBg: Color,
    val markFg: Color,
)

val LightMemo = MemoColors(
    bg = Color(0xFFFFFFFF),
    bgSoft = Color(0xFFF5F6F8),
    bgHover = Color(0xFFECEEF1),
    fg = Color(0xFF1A1C20),
    fgSoft = Color(0xFF6B7280),
    border = Color(0xFFE3E6EA),
    accent = Color(0xFF2563EB),
    accentSoft = Color(0xFFDBEAFE),
    danger = Color(0xFFDC2626),
    markBg = Color(0xFFFDE68A),
    markFg = Color(0xFF1A1C20),
)

val DarkMemo = MemoColors(
    bg = Color(0xFF17181C),
    bgSoft = Color(0xFF1F2127),
    bgHover = Color(0xFF2A2D35),
    fg = Color(0xFFE8EAED),
    fgSoft = Color(0xFF9AA0AA),
    border = Color(0xFF2E3138),
    accent = Color(0xFF60A5FA),
    accentSoft = Color(0xFF1E3A5F),
    danger = Color(0xFFF87171),
    markBg = Color(0xFF854D0E),
    markFg = Color(0xFFFEF3C7),
)

val MemoRadius = 10.dp

val LocalMemo = staticCompositionLocalOf {
    LightMemo
}

val memo: MemoColors
    @Composable get() = LocalMemo.current

@Composable
fun MemoTheme(preference: String, content: @Composable () -> Unit) {
    val dark = when (preference) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val tokens = if (dark) DarkMemo else LightMemo
    val scheme = if (dark) {
        darkColorScheme(
            primary = tokens.accent,
            onPrimary = Color(0xFF0C2350),
            primaryContainer = tokens.accentSoft,
            onPrimaryContainer = tokens.fg,
            background = tokens.bg,
            onBackground = tokens.fg,
            surface = tokens.bg,
            onSurface = tokens.fg,
            surfaceVariant = tokens.bgSoft,
            onSurfaceVariant = tokens.fgSoft,
            outline = tokens.border,
            outlineVariant = tokens.border,
            error = tokens.danger,
        )
    } else {
        lightColorScheme(
            primary = tokens.accent,
            onPrimary = Color.White,
            primaryContainer = tokens.accentSoft,
            onPrimaryContainer = tokens.fg,
            background = tokens.bg,
            onBackground = tokens.fg,
            surface = tokens.bg,
            onSurface = tokens.fg,
            surfaceVariant = tokens.bgSoft,
            onSurfaceVariant = tokens.fgSoft,
            outline = tokens.border,
            outlineVariant = tokens.border,
            error = tokens.danger,
        )
    }
    CompositionLocalProvider(LocalMemo provides tokens) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
