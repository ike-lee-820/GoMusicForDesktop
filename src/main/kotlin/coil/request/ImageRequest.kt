package coil.request

import android.content.Context

class ImageRequest private constructor(val data: Any?) {
    class Builder(private val ctx: Context) {
        private var data: Any? = null
        fun data(d: Any?): Builder { data = d; return this }
        fun allowHardware(v: Boolean): Builder = this
        fun size(v: Int): Builder = this
        fun build(): ImageRequest = ImageRequest(data)
    }
}
