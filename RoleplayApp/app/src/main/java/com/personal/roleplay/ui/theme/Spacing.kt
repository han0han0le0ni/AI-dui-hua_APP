package com.personal.roleplay.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 8dp 网格（M3 规范）。
 *
 * 间距一律从这里取，避免不同页面出现 10dp / 14dp 这类不一致的数字。
 */
@Immutable
object Spacing {
    /** 图标内边距 */
    val xs: Dp = 4.dp

    /** 紧凑间距 */
    val sm: Dp = 8.dp

    /** 默认内边距 */
    val md: Dp = 16.dp

    /** 区块间距 */
    val lg: Dp = 24.dp

    /** 大间距 */
    val xl: Dp = 32.dp

    /** 屏幕外边距 */
    val xxl: Dp = 48.dp

    /** 最小可点击尺寸，任何可交互元素都不能小于它 */
    val minTouchTarget: Dp = 48.dp

    /** 主要操作建议尺寸 */
    val primaryTouchTarget: Dp = 56.dp

    /** 相邻可点击元素之间的最小间隔 */
    val touchGap: Dp = 8.dp
}
