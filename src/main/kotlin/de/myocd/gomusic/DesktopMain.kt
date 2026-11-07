package de.myocd.gomusic

import android.app.Application
import androidx.activity.compose.BackDispatcher
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.DesktopConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class DesktopApp : Application()

/** 桌面只保留横屏：窗口最小宽度/高度，低于下限自动拉回，永远不会进竖屏分支 */
private const val MIN_WIDTH_DP = 960
private const val MIN_HEIGHT_DP = 600
private val DEFAULT_SIZE = DpSize(1280.dp, 800.dp)

/**
 * 桌面入口。
 *
 * 竖屏界面已移除：LocalConfiguration.orientation 恒为 LANDSCAPE，
 * 手机端源码里的竖屏分支不会被渲染。窗口默认 1280×800（横屏），
 * 拉宽/拉高都仍是横屏那套「左侧导航栏 + 右侧竖排播放栏」布局。
 */
fun main() = application {
    val app = remember { DesktopApp() }
    val vm = remember { MainViewModel(app) }
    val state = rememberWindowState(width = DEFAULT_SIZE.width, height = DEFAULT_SIZE.height)
    var cfg by remember {
        mutableStateOf(
            DesktopConfiguration(1280, 800, android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        )
    }
    LaunchedEffect(state.size) {
        val w = state.size.width.value.toInt()
        val h = state.size.height.value.toInt()
        // 不允许缩成竖屏 / 过小，保证横屏布局始终成立
        if (w < MIN_WIDTH_DP || h < MIN_HEIGHT_DP) {
            state.size = DpSize(
                maxOf(w, MIN_WIDTH_DP).dp,
                maxOf(h, MIN_HEIGHT_DP).dp,
            )
            return@LaunchedEffect
        }
        cfg = DesktopConfiguration(w, h, android.content.res.Configuration.ORIENTATION_LANDSCAPE)
    }
    LaunchedEffect(Unit) {
        ImageCache.init(app)
        NmLog.init(app)
        CrashHandler.install(app)
        withContext(Dispatchers.IO) { ZhConvert.warmUp() }
        vm.refreshBatteryExempt()
        vm.autoCheckUpdate()
    }
    LaunchedEffect(vm.updateInfo, vm.updateChecking) {
        if (!vm.updateChecking) vm.maybeShowUpdateDialog()
    }
    Window(
        onCloseRequest = { kotlin.system.exitProcess(0) },
        state = state,
        title = "GoMusic",
        onKeyEvent = { ev ->
            if (ev.type == KeyEventType.KeyUp && (ev.key == Key.Escape || ev.key == Key.Backspace)) {
                BackDispatcher.trigger()
            } else false
        },
    ) {
        CompositionLocalProvider(
            LocalContext provides app,
            LocalConfiguration provides cfg,
        ) {
            GoTheme(vm.primary, rememberAppFont(vm.fontKind, vm.fontWght)) {
                CompositionLocalProvider(LocalZhMode provides vm.zhConvert) {
                    MainScreen(vm)
                    if (vm.showUpdate) UpdateScreen(vm)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        GoToast(
                            vm.toastMsg,
                            Modifier.navigationBarsPadding().padding(bottom = 56.dp + 64.dp + 16.dp),
                        )
                    }
                }
            }
        }
    }
}
