package android.content.pm

import android.app.PendingIntent
import android.content.Context
import java.io.File
import java.io.OutputStream

class ActivityInfo {
    companion object {
        const val SCREEN_ORIENTATION_UNSPECIFIED = -1
        const val SCREEN_ORIENTATION_SENSOR_LANDSCAPE = 6
        const val SCREEN_ORIENTATION_SENSOR_PORTRAIT = 7
    }
}

class PackageManager {
    companion object { const val PERMISSION_GRANTED = 0; const val PERMISSION_DENIED = -1 }
    val packageInstaller: PackageInstaller get() = PackageInstaller()
    fun canRequestPackageInstalls(): Boolean = true
    fun getPackageInfo(p: String, f: Int): Any? = null
}

class PackageInstaller {
    class SessionParams(mode: Int) {
        companion object { const val MODE_FULL_INSTALL = 1 }
        fun setAppPackageName(p: String) {}
    }
    inner class Session : java.io.Closeable {
        private var target: File? = null
        fun openWrite(name: String, offset: Long, size: Long): OutputStream {
            val f = File(android.content.AppDirs.downloadRoot, "GoMusic-26.11.8-update${extOf(name)}")
            target = f
            return f.outputStream()
        }
        fun fsync(os: OutputStream) { runCatching { os.flush() } }
        fun commit(intentSender: Any?) {
            // 桌面平替：下载完的桌面安装包直接交系统打开（NSIS / dmg / deb）
            val f = target ?: return
            runCatching { java.awt.Desktop.getDesktop().open(f) }
        }
        override fun close() {}
        private fun extOf(n: String): String = when {
            n.contains("msi") || n.contains("nsis") -> ".exe"
            n.contains("dmg") -> ".dmg"
            else -> ""
        }
    }
    fun createSession(p: SessionParams): Int = 1
    fun openSession(id: Int): Session = Session()
}
