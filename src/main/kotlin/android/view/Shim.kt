package android.view

import android.content.Context
import java.awt.Component
import javax.swing.JPanel

/** Android View 平替：桌面用 AWT Component 承载（MV 视频画面） */
open class View(ctx: Context? = null) {
    open val component: Component get() = JPanel().apply { background = java.awt.Color.BLACK; isOpaque = true }
}
class SurfaceView(ctx: Context? = null) : View(ctx) {
    var holder: Any? = null
}
