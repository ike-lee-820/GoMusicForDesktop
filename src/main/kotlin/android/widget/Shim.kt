package android.widget

import android.content.Context

object Toast {
    const val LENGTH_SHORT = 0
    const val LENGTH_LONG = 1
    fun makeText(ctx: Context?, text: CharSequence, duration: Int): Toast = this
    fun show() {}
}
