package gomusic

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
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

    // 悬浮歌词：独立透明窗口，始终置顶，可拖动
    if (state.floatOn) {
        val fs = rememberWindowState(
            position = WindowPosition(Prefs.floatX.dp, Prefs.floatY.dp),
            size = DpSize(820.dp, 130.dp),
        )
        DisposableEffect(fs) {
            onDispose {
                Prefs.floatX = fs.position.x.value
                Prefs.floatY = fs.position.y.value
            }
        }
        Window(
            title = "GoMusic 悬浮歌词",
            state = fs,
            undecorated = true,
            transparent = true,
            alwaysOnTop = true,
            resizable = false,
            onCloseRequest = { state.setFloatOn(false) },
        ) {
            FloatLyricContent(state)
        }
    }
}
