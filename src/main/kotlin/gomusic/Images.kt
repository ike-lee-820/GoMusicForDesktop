package gomusic

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.compose.resources.decodeToImageBitmap
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

data class CoverImg(val bmp: ImageBitmap, val color: Color?)

/** 封面加载：Skia 解码（webp / jpeg / png 通吃），ImageIO（含 webp 插件）负责取色 */
object Images {
    private val cache = ConcurrentHashMap<String, CoverImg?>()
    private val dir: File = File(Prefs.dir, "imgcache").apply { mkdirs() }
    private const val MAX_BYTES = 300L * 1024 * 1024

    private fun fileOf(url: String): File {
        val md = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        return File(dir, md.joinToString("") { "%02x".format(it) })
    }

    private fun readBytes(ref: String): ByteArray? = when {
        ref.startsWith("data:") -> decodeDataUri(ref)
        ref.startsWith("http") -> {
            val f = fileOf(ref)
            // 先读缓存文件，没有再走网络并存下来
            runCatching { if (f.exists() && f.length() > 0) f.readBytes() else null }?.getOrNull()
                ?: Api.bytes(ref)?.also { b ->
                    runCatching {
                        val tmp = File(dir, f.name + ".tmp")
                        tmp.writeBytes(b)
                        if (tmp.length() > 0) tmp.renameTo(f) else tmp.delete()
                        trim()
                    }
                }
        }
        else -> runCatching { File(ref).takeIf { it.exists() }?.readBytes() }.getOrNull()
    }

    private fun trim() {
        runCatching {
            val files = dir.listFiles() ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_BYTES) return
            files.sortedBy { it.lastModified() }.forEach {
                if (total <= MAX_BYTES) return
                val len = it.length()
                if (it.delete()) total -= len
            }
        }
    }

    /** 阻塞加载，请在 IO 线程调用 */
    fun get(ref: String?, size: Int = 128): CoverImg? {
        if (ref.isNullOrBlank()) return null
        val key = "$ref@$size"
        cache[key]?.let { return it }
        val bytes = readBytes(ref) ?: return null
        val img = CoverImg(
            bmp = runCatching { bytes.decodeToImageBitmap() }.getOrNull() ?: return null,
            color = runCatching { dominant(bytes) }.getOrNull(),
        )
        if (cache.size > 200) cache.clear()
        cache[key] = img
        return img
    }

    /** 取封面主色：偏好饱和、亮度适中的像素，并保证在深色底上可读 */
    private fun dominant(bytes: ByteArray): Color? {
        val bi = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        val w = bi.width
        val h = bi.height
        if (w == 0 || h == 0) return null
        var sr = 0.0
        var sg = 0.0
        var sb = 0.0
        var n = 0
        val step = 2 // 隔点采样，够用且快
        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                val argb = bi.getRGB(x, y)
                val a = (argb ushr 24) and 0xFF
                if (a < 125) continue
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                if (max < 40) continue
                val sat = (max - min).toDouble() / max
                val lum = (max + min) / 2.0 / 255.0
                if (sat < 0.12) continue
                if (lum < 0.18 || lum > 0.85) continue
                sr += r
                sg += g
                sb += b
                n++
            }
        }
        if (n == 0) return null
        var r = sr / n
        var g = sg / n
        var b = sb / n
        var lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        if (lum < 0.55) {
            val k = 0.55 / lum.coerceAtLeast(0.05)
            r = (r * k).coerceAtMost(255.0)
            g = (g * k).coerceAtMost(255.0)
            b = (b * k).coerceAtMost(255.0)
            lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            if (lum > 0.78) {
                val k2 = 0.78 / lum
                r *= k2
                g *= k2
                b *= k2
            }
        }
        return Color(
            red = (r / 255.0).toFloat(),
            green = (g / 255.0).toFloat(),
            blue = (b / 255.0).toFloat(),
        )
    }
}
