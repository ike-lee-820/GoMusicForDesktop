package androidx.core.graphics

import kotlin.math.abs

/** androidx.palette 取色用的 HSL 工具，行为与 Android ColorUtils 一致 */
object ColorUtils {
    fun colorToHSL(color: Int, out: FloatArray) {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        val l = (max + min) / 2f
        val d = max - min
        val s = if (d == 0f) 0f else d / (1f - abs(2f * l - 1f))
        var h = 0f
        if (d != 0f) {
            h = when (max) {
                r -> ((g - b) / d) % 6f
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            }
            h *= 60f
            if (h < 0f) h += 360f
        }
        out[0] = h; out[1] = s; out[2] = l
    }
    fun HSLToColor(hsl: FloatArray): Int {
        val h = hsl[0]; val s = hsl[1]; val l = hsl[2]
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return (0xFF shl 24) or (((r + m) * 255).toInt().coerceIn(0,255) shl 16) or
            (((g + m) * 255).toInt().coerceIn(0,255) shl 8) or ((b + m) * 255).toInt().coerceIn(0,255)
    }
}
