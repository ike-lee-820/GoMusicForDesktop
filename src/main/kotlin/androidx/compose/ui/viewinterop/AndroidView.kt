package androidx.compose.ui.viewinterop

import android.content.AppContext
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import javax.swing.JComponent

/** AndroidView 平替：用 SwingPanel 把 AWT 组件嵌进 Compose（MV 视频画面） */
@Composable
fun AndroidView(
    factory: (android.content.Context) -> View,
    modifier: Modifier = Modifier,
    update: (View) -> Unit = {},
) {
    val ref = remember { arrayOfNulls<View>(1) }
    SwingPanel(
        modifier = modifier,
        factory = {
            val v = factory(AppContext)
            ref[0] = v
            v.component as? JComponent ?: javax.swing.JPanel()
        },
        update = { ref[0]?.let(update) },
    )
}
