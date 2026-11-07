package androidx.palette.graphics

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Palette 平替：从封面像素统计主色 / 高饱和色，接口与 AndroidX Palette 一致 */
class Palette private constructor(private val swatches: List<Swatch>) {
    class Swatch(val rgb: Int, val population: Int)
    val vibrantSwatch: Swatch? = swatches.filter { it.population > 0 }
        .maxByOrNull { saturation(it.rgb) * 2f + it.population / 1000f }
    val dominantSwatch: Swatch? = swatches.maxByOrNull { it.population }
    companion object {
        fun from(bmp: Bitmap): Builder = Builder(bmp)
        private fun saturation(c: Int): Float {
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            return if (mx == 0f) 0f else (mx - mn) / mx
        }
    }
    class Builder(private val bmp: Bitmap) {
        fun generate(): Palette {
            val img = bmp.img
            val step = max(1, min(img.width, img.height) / 64)
            val counts = HashMap<Int, Int>()
            var y = 0
            while (y < img.height) {
                var x = 0
                while (x < img.width) {
                    val c = img.getRGB(x, y) and 0x00FFFFFF
                    // 量化到 32 级，忽略接近纯黑/纯白的像素
                    val r = ((c shr 16) and 0xFF) / 32 * 32
                    val g = ((c shr 8) and 0xFF) / 32 * 32
                    val b = (c and 0xFF) / 32 * 32
                    val q = (r shl 16) or (g shl 8) or b
                    val lum = (r + g + b) / 3
                    if (lum in 25..230) counts[q] = (counts[q] ?: 0) + 1
                    x += step
                }
                y += step
            }
            val list = counts.map { (rgb, n) -> Swatch(0xFF000000.toInt() or rgb, n) }
            return Palette(list)
        }
    }
}
