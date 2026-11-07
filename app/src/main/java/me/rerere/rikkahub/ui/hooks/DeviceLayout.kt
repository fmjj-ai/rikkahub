package me.rerere.rikkahub.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

// 使用最小宽度，避免手机横屏时误启用平板布局；600dp 及以上保持原有平板行为。
internal fun isPhoneLayout(smallestWidthDp: Int): Boolean = smallestWidthDp in 1 until 600

@Composable
internal fun isPhoneLayout(): Boolean = isPhoneLayout(LocalConfiguration.current.smallestScreenWidthDp)
