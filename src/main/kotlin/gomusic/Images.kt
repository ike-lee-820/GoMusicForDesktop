package gomusic

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

data class CoverImg(val bmp: ImageBitmap, val color: Color?)

/** 封面加载：Skia 解码（自带 webp/jpeg/png），并抽取主色 */
object Images {
    private val cache = ConcurrentHashMap<String, CoverImg?>()

    private fun readBytes(ref: String): ByteArray? = when {
        ref.startsWith("data:") -> decodeDataUri(ref)
        ref.startsWith("http") -> Api.bytes(ref)
        else -> runCatching { File(ref).takeIf { it.exists() }?.readBytes() }.getOrNull()
    }

    /** 阻塞加载，请在 IO 线程调用 */
    fun get(ref: String?, size: Int = 128): CoverImg? {
        if (ref.isNullOrBlank()) return null
        val key = "$ref@$size"
        cache[key]?.let { return it }
        val bytes = readBytes(ref) ?: return null
        val img = decode(bytes, size) ?: skia(bytes)
        if (cache.size > 120) cache.clear()
        cache[key] = img
        return img
    }

    /** ImageIO（含 TwelveMonkeys 的 webp 插件）：能顺带缩放到目标尺寸并取色 */
    private fun decode(bytes: ByteArray, size: Int): CoverImg? {
        val src = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null
        val scale = (size.toDouble() / maxOf(w, h)).coerceAtMost(1.0)
        val tw = (w * scale).toInt().coerceAtLeast(1)
        val th = (h * scale).toInt().coerceAtLeast(1)
        val small = BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = small.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, tw, th, null)
        g.dispose()
        return CoverImg(small.toComposeImageBitmap(), dominant(small))
    }

    /** 兜底：Skia 直接解码（webp / jpeg / png 都支持），但不取色 */
    private fun skia(bytes: ByteArray): CoverImg? = runCatching {
        CoverImg(Image.makeFromEncoded(bytes).toComposeImageBitmap(), null)
    }.getOrNull()

    /** 取封面主色：偏好饱和、亮度适中的像素，并保证在深色底上可读 */
    fun dominant(bi: BufferedImage): Color? {
        val w = bi.width
        val h = bi.height
        if (w == 0 || h == 0) return null
        var sr = 0.0
        var sg = 0.0
        var sb = 0.0
        var n = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
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
        // 提升亮度：太暗在深色背景上看不清
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
        return Color((r / 255f).coerceIn(0f, 1f), (g / 255f).coerceIn(0f, 1f), (b / 255f).coerceIn(0f, 1f))
    }
}
