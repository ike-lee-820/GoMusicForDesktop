package gomusic

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 网易云请求日志。
 *
 * 输出到 ~/.gomusic/log/log_<时间戳>.txt，
 * 记录每一条网易云请求的完整 URL 与完整响应。
 */
object NmLog {
    private const val MAX_FILE = 4L * 1024 * 1024
    private var file: File? = null
    private var written = 0L
    private var enabled = false

    fun init() {
        if (file != null) return
        val name = "log_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        val dir = File(Prefs.dir, "log").apply { mkdirs() }
        val f = File(dir, name)
        runCatching {
            f.writeText(
                "=== GoMusic 网易云日志 ===\n" +
                    "时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n" +
                    "路径：${f.absolutePath}\n\n",
            )
            file = f
            enabled = true
        }
    }

    fun path(): String = file?.absolutePath ?: "(未初始化)"

    private fun w(line: String) {
        val f = file ?: return
        if (!enabled) return
        val data = line.toByteArray(Charsets.UTF_8)
        if (written + data.size > MAX_FILE) {
            enabled = false
            runCatching { f.appendText("[日志已达上限，停止写入]\n") }
            return
        }
        try {
            f.appendText(line)
            written += data.size
        } catch (e: Exception) {
            enabled = false
        }
    }

    fun req(url: String) = w("[请求] ${now()}\n  URL: ${mask(url)}\n")
    fun res(url: String, code: Int?, body: String?) =
        w("[响应] ${now()}  HTTP ${code ?: "-"}  ${mask(url)}\n  ${body ?: "(空)"}\n\n")
    fun err(url: String, e: Throwable) =
        w("[异常] ${now()}  ${mask(url)}\n  ${e.javaClass.simpleName}: ${e.message}\n\n")
    fun note(s: String) = w("[说明] ${now()}  $s\n")

    /** 崩溃：写完整堆栈 */
    fun crash(t: Throwable, where: String = "") {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        w(buildString {
            append("\n[崩溃] ${now()}")
            if (where.isNotBlank()) append("  位置：$where")
            append("\n  ${t.javaClass.name}: ${t.message}\n")
            append(sw.toString()).append("\n")
        })
    }

    private fun now(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    /** cookie / 验证码 / 密码 打码 */
    private val SECRET = Regex("(cookie|captcha|password|md5_password)=([^&]*)")
    private fun mask(u: String): String =
        u.replace(SECRET) { m -> "${m.groupValues[1]}=***(${m.groupValues[2].length}字符)" }
}
