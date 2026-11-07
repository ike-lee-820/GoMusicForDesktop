package androidx.compose.ui.platform

import android.content.Context
import android.content.AppContext
import androidx.compose.runtime.compositionLocalOf

val LocalContext = compositionLocalOf<Context> { AppContext }

/** 窗口配置：桌面按窗口宽高实时更新，横屏/竖屏布局逻辑与手机端完全一致 */
data class DesktopConfiguration(
    var screenWidthDp: Int = 800,
    var screenHeightDp: Int = 600,
    var orientation: Int = android.content.res.Configuration.ORIENTATION_LANDSCAPE,
)
val LocalConfiguration = compositionLocalOf { DesktopConfiguration() }
