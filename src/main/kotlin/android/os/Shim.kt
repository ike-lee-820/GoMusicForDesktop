package android.os

import java.io.File
import javax.swing.Timer

object Build {
    object VERSION { const val SDK_INT = 35 }
}
object Environment {
    const val DIRECTORY_DOWNLOADS = "Download"
    fun getExternalStoragePublicDirectory(type: String): File =
        android.content.AppDirs.downloadRoot
    fun getExternalStorageDirectory(): File = android.content.AppDirs.downloadRoot
}
object Process {
    fun myPid(): Int = 0
    fun killProcess(pid: Int) {}
}
class Bundle {
    val map = HashMap<String, Any?>()
    fun getString(k: String): String? = map[k] as? String
}
class Looper {
    companion object { fun getMainLooper(): Looper = Looper() }
}
class Handler(private val looper: Looper = Looper.getMainLooper()) {
    private val timers = ArrayList<Timer>()
    fun post(r: Runnable): Boolean { javax.swing.SwingUtilities.invokeLater(r); return true }
    fun postDelayed(r: Runnable, ms: Long): Boolean {
        val t = Timer(ms.coerceAtLeast(1).toInt()) { r.run() }
        t.isRepeats = false; t.start(); timers += t; return true
    }
    fun removeCallbacks(r: Runnable) { timers.forEach { it.stop() }; timers.clear() }
    fun removeCallbacksAndMessages(token: Any?) { timers.forEach { it.stop() }; timers.clear() }
}
class PowerManager {
    fun isIgnoringBatteryOptimizations(pkg: String): Boolean = true
    inner class WakeLock { fun acquire() {}; fun acquire(ms: Long) {}; fun release() {} }
    fun newWakeLock(level: Int, tag: String): WakeLock = WakeLock()
}
