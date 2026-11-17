package com.personal.roleplay.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 应用主题。
 *
 * 三级配色优先级：
 * 1. 对话页传进来的角色主题色（accent）——进入某个角色的对话时全局切换；
 * 2. Android 12+ 的动态取色（M3 的「Personal」）；
 * 3. 内置默认配色。
 */
@Composable
fun RoleplayAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** 角色主题色，ARGB 打包的 Long；为空时走动态取色 */
    accentArgb: Long? = null,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    /**
     * 必须缓存。
     *
     * deriveAccentScheme 会新建一整套 ColorScheme（30+ 个色值 + HSL 换算），
     * 而本函数包着整个对话页——流式输出时每个 token 都会重组一次。
     * 不缓存就等于每个 token 全量换一次肤，整棵子树跟着重绘，卡顿主要来自这里。
     */
    val colorScheme = remember(accentArgb, darkTheme, dynamicColor, context) {
        when {
            accentArgb != null -> deriveAccentScheme(accentArgb, darkTheme)

            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

            darkTheme -> darkColorScheme(primary = DEFAULT_SEED)
            else -> lightColorScheme(primary = DEFAULT_SEED)
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

/**
 * 由一个种子色推导出整套 ColorScheme。
 *
 * HSL 转换是这里自己实现的：android.graphics.Color 只提供 HSV 接口，没有 HSL，
 * 而按亮度（lightness）分派明暗两侧比按 HSV 的 value 更好控制对比度
 * ——正文对背景至少 4.5:1、UI 组件至少 3:1（M3 硬性要求）。
 */
fun deriveAccentScheme(seedArgb: Long, darkTheme: Boolean): ColorScheme {
    val (hue, seedSaturation) = rgbToHsl(seedArgb)
    val saturation = seedSaturation.coerceIn(0.35f, 0.85f)

    return if (darkTheme) {
        darkColorScheme().copy(
            primary = hslColor(hue, saturation, 0.72f),
            onPrimary = hslColor(hue, saturation, 0.12f),
            primaryContainer = hslColor(hue, saturation, 0.26f),
            onPrimaryContainer = hslColor(hue, saturation * 0.6f, 0.92f),
            secondary = hslColor((hue + 25f) % 360f, saturation * 0.6f, 0.70f),
            onSecondary = hslColor((hue + 25f) % 360f, saturation * 0.6f, 0.14f),
            secondaryContainer = hslColor((hue + 25f) % 360f, saturation * 0.5f, 0.28f),
            onSecondaryContainer = hslColor((hue + 25f) % 360f, saturation * 0.5f, 0.92f),
            tertiary = hslColor((hue + 320f) % 360f, saturation * 0.7f, 0.72f),
            onTertiary = hslColor((hue + 320f) % 360f, saturation * 0.7f, 0.14f),
            surfaceTint = hslColor(hue, saturation, 0.72f)
        )
    } else {
        lightColorScheme().copy(
            primary = hslColor(hue, saturation, 0.40f),
            onPrimary = Color.White,
            primaryContainer = hslColor(hue, saturation * 0.7f, 0.92f),
            onPrimaryContainer = hslColor(hue, saturation, 0.18f),
            secondary = hslColor((hue + 25f) % 360f, saturation * 0.6f, 0.40f),
            onSecondary = Color.White,
            secondaryContainer = hslColor((hue + 25f) % 360f, saturation * 0.5f, 0.93f),
            onSecondaryContainer = hslColor((hue + 25f) % 360f, saturation * 0.5f, 0.20f),
            tertiary = hslColor((hue + 320f) % 360f, saturation * 0.7f, 0.42f),
            onTertiary = Color.White,
            surfaceTint = hslColor(hue, saturation, 0.40f)
        )
    }
}

/**
 * RGB → HSL。返回 (色相 0..360, 饱和度 0..1)。
 *
 * 亮度在这里用不上（只取种子色的色相与饱和度），所以不返回。
 */
private fun rgbToHsl(argb: Long): Pair<Float, Float> {
    val r = ((argb shr 16) and 0xFF).toFloat() / 255f
    val g = ((argb shr 8) and 0xFF).toFloat() / 255f
    val b = (argb and 0xFF).toFloat() / 255f

    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    if (delta == 0f) return 0f to 0f

    val lightness = (max + min) / 2f
    val saturation = delta / (1f - kotlin.math.abs(2f * lightness - 1f))
    val hue = when (max) {
        r -> 60f * (((g - b) / delta) % 6f)
        g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    return (if (hue < 0f) hue + 360f else hue) to saturation
}

/**
 * HSL → Compose 的 Color。色相环绕、输入越界都在这里收口。
 */
private fun hslColor(hue: Float, saturation: Float, lightness: Float): Color {
    val s = saturation.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)

    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val hp = (((hue % 360f) + 360f) % 360f) / 60f
    val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))

    val (r1, g1, b1) = when {
        hp < 1f -> Triple(c, x, 0f)
        hp < 2f -> Triple(x, c, 0f)
        hp < 3f -> Triple(0f, c, x)
        hp < 4f -> Triple(0f, x, c)
        hp < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f)
    )
}

val DEFAULT_SEED = Color(0xFF6B5B95)
