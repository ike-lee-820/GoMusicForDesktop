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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.exitApplication
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class DesktopApp : Application()

/**
 * 桌面入口。
 *
 * 窗口默认按手机竖屏尺寸（420x860）打开，界面与手机端逐像素一致；
 * 把窗口拉宽后 LocalConfiguration.orientation 变成 LANDSCAPE，
 * 自动切换成手机横屏那套「左侧导航栏 + 右侧竖排播放栏」布局。
 */
fun main() = application {
    val app = remember { DesktopApp() }
    val vm = remember { MainViewModel(app) }
    val state = rememberWindowState(width = 420.dp, height = 860.dp)
    var cfg by remember {
        mutableStateOf(DesktopConfiguration(420, 860, android.content.res.Configuration.ORIENTATION_PORTRAIT))
    }
    LaunchedEffect(state.size) {
        val w = state.size.width.value.toInt()
        val h = state.size.height.value.toInt()
        cfg = DesktopConfiguration(
            w, h,
            if (w > h) android.content.res.Configuration.ORIENTATION_LANDSCAPE
            else android.content.res.Configuration.ORIENTATION_PORTRAIT,
        )
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
        onCloseRequest = ::exitApplication,
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
