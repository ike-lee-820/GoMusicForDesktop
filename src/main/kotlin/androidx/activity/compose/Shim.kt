package androidx.activity.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** Android 返回键：桌面用 Esc / Backspace 平替，注册顺序与 Android 一致（后注册先响应） */
object BackDispatcher {
    private val handlers = ArrayList<Pair<() -> Boolean, () -> Unit>>()
    fun register(enabled: () -> Boolean, onBack: () -> Unit): () -> Unit {
        val e = enabled to onBack
        synchronized(handlers) { handlers.add(e) }
        return { synchronized(handlers) { handlers.remove(e) } }
    }
    /** 返回 true 表示已消费 */
    fun trigger(): Boolean {
        val list = synchronized(handlers) { handlers.toList() }
        for ((enabled, onBack) in list.asReversed()) {
            if (enabled()) { onBack(); return true }
        }
        return false
    }
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val cur = rememberUpdatedState(onBack)
    val en = rememberUpdatedState(enabled)
    DisposableEffect(Unit) {
        val remove = BackDispatcher.register({ en.value }) { cur.value() }
        onDispose { remove() }
    }
}

/** 手机端只 import 未调用，保留同名桩让 import 能解析 */
@Composable
fun rememberLauncherForActivityResult(contract: Any, onResult: (Any?) -> Unit): Any = remember { Any() }

@Composable
fun setContent(content: @Composable () -> Unit) { content() }
