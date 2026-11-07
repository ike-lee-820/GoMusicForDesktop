package android.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.content.pm.ActivityInfo

open class Application : Activity() {
    init { android.content.AppContext.realApp = this }
}

open class Activity : ContextWrapper(null) {
    /** 屏幕方向：桌面无传感器，赋值不做实际旋转（窗口大小由用户自己拖） */
    var requestedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    var intent: Intent? = null
    fun finish() {}
    fun recreate() {}
}

class NotificationChannel(val id: String, val name: CharSequence, val importance: Int) {
    companion object { const val IMPORTANCE_LOW = 2 }
}
class NotificationManager {
    fun getNotificationChannel(id: String): NotificationChannel? = null
    fun createNotificationChannel(c: NotificationChannel) {}
    fun notify(id: Int, n: Any?) {}
    fun cancel(id: Int) {}
}

class PendingIntent(val intentSender: Any?) {
    companion object {
        const val FLAG_IMMUTABLE = 0x02000000
        const val FLAG_UPDATE_CURRENT = 0x08000000
        fun getBroadcast(ctx: Context, requestCode: Int, intent: Intent, flags: Int): PendingIntent =
            PendingIntent(intent)
    }
}
