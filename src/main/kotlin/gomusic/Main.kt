package gomusic

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() {
    NmLog.init()
    // 未捕获异常写进日志：界面崩了也能从 ~/.gomusic/log 里看到完整堆栈，
    // 不至于只看到一句干巴巴的越界提示
    Thread.setDefaultUncaughtExceptionHandler { _, t ->
        NmLog.crash(t, "未捕获异常")
    }
    application {
        val state = remember { AppState() }
        Window(
            title = "GoMusic",
            state = rememberWindowState(size = DpSize(1180.dp, 760.dp)),
            onCloseRequest = ::exitApplication,
            onKeyEvent = { e ->
                if (e.type == KeyEventType.KeyDown) {
                    when (e.key) {
                        Key.Spacebar -> {
                            state.toggle()
                            true
                        }

                        Key.DirectionRight -> {
                            val max = if (state.durationMs > 0) state.durationMs else state.positionMs + 5000
                            state.seek((state.positionMs + 5000).coerceAtMost(max))
                            true
                        }

                        Key.DirectionLeft -> {
                            state.seek((state.positionMs - 5000).coerceAtLeast(0))
                            true
                        }

                        else -> false
                    }
                } else {
                    false
                }
            },
        ) {
            App(state)
        }
    }
}
