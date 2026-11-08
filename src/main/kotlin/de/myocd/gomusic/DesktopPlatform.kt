package de.myocd.gomusic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.io.File

/** Android R 资源 id 平替：桌面不需要资源 id，给常量让原代码零改动编译 */
object R {
    object drawable { const val ic_fg = 1 }
    object font { const val song = 2 }
    object string { const val app_name = 3 }
    object mipmap { const val ic_launcher = 4 }
}

object BuildConfig {
    const val VERSION_NAME = "26.11.10"
    val OR_KEY: String get() = System.getenv("OPENROUTER_API_KEY") ?: ""
    const val DEBUG = false
}

/** 播放服务：桌面没有 Service，用同名 object 承接「应用播放焦点」指令 */
object PlaybackService {
    const val ACTION_APPLY_FOCUS = "de.myocd.gomusic.APPLY_FOCUS"
    fun applyFocus() { /* 桌面没有电话打断，策略保留但无需动作 */ }
}

/** APK 安装结果广播接收器平替：桌面更新直接交系统安装器 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {}
    companion object {
        const val ACTION = "de.myocd.gomusic.INSTALL_RESULT"
        const val EXTRA_SESSION = "session"
        const val EXTRA_FILE = "file"
    }
}

/** 下载通知平替：系统托盘气泡（桌面最高级平替） */
class Noti(private val ctx: Context) {
    private val id = 2001
    private var icon: TrayIcon? = null
    private fun ensureIcon(): TrayIcon? {
        if (icon != null) return icon
        if (!SystemTray.isSupported()) return null
        val tray = SystemTray.getSystemTray()
        val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val t = TrayIcon(img, "GoMusic")
        runCatching { tray.add(t) }
        icon = t
        return t
    }
    fun ensureChannel() { ensureIcon() }
    fun progress(title: String, index: Int, total: Int, percent: Int, speed: String, size: String) {
        ensureIcon()?.displayMessage("GoMusic", "正在下载 $index/$total：$title ${if (percent >= 0) "$percent%" else ""}", TrayIcon.MessageType.INFO)
    }
    fun done(text: String) { ensureIcon()?.displayMessage("GoMusic", text, TrayIcon.MessageType.INFO) }
    fun cancel() {}
    companion object {
        private const val CH = "gomusic_download"
        fun size(bytes: Long): String {
            if (bytes <= 0) return ""
            val kb = bytes / 1024.0
            return when { kb < 1024 -> "%.0f KB".format(kb); kb < 1024 * 1024 -> "%.1f MB".format(kb / 1024); else -> "%.2f GB".format(kb / (1024 * 1024)) }
        }
        fun speed(bytesPerSec: Long): String { if (bytesPerSec <= 0) return ""; return "${size(bytesPerSec)}/s" }
    }
}

/** 边播边缓存平替：FFmpeg 直接流式播放；切歌时清掉上一首的临时缓存 */
object PlayCache {
    @Volatile private var cur: String? = null
    fun noteUrl(url: String) {
        if (url == cur) return
        val old = cur
        cur = url
        if (old != null) NmLog.note("已切换音源，清除上一首缓存引用")
    }
    fun clear(ctx: Context) { cur = null }
    fun release() { cur = null }
}

/** 字体：桌面用系统衬线字体平替思源宋体（无需内置 22MB 字体即可全平台一致） */
const val FONT_SYSTEM = 0
const val FONT_SONG = 1
const val WGHT_MIN = 250
const val WGHT_MAX = 900
const val WGHT_DEFAULT = 450
val FONT_NAMES = listOf("系统字体", "思源宋体")

@Composable
fun rememberAppFont(kind: Int, wght: Int): FontFamily? =
    if (kind == FONT_SONG) FontFamily.Serif else null

/** 状态栏：桌面没有系统状态栏，同名空实现让界面代码零改动 */
@Composable
fun StatusBarMode() {}
