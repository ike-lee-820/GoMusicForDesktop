package androidx.compose.foundation.layout

import androidx.compose.ui.Modifier

/** 桌面没有系统状态栏 / 导航栏：同名平替，返回原 Modifier，界面代码零改动 */
fun Modifier.statusBarsPadding(): Modifier = this
fun Modifier.navigationBarsPadding(): Modifier = this
