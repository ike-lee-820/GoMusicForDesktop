package android.graphics

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** Android Bitmap 平替：包一层 BufferedImage，配合 [BitmapFactory] 与取色 */
class Bitmap(val img: BufferedImage) {
    val width: Int get() = img.width
    val height: Int get() = img.height
    fun getPixel(x: Int, y: Int): Int = img.getRGB(x, y)
}
object BitmapFactory {
    fun decodeByteArray(data: ByteArray, offset: Int, length: Int): Bitmap? = runCatching {
        val b = data.copyOfRange(offset, offset + length)
        ImageIO.read(ByteArrayInputStream(b))?.let { Bitmap(it) }
    }.getOrNull()
    fun decodeStream(input: java.io.InputStream): Bitmap? = runCatching {
        ImageIO.read(input)?.let { Bitmap(it) }
    }.getOrNull()
}
object Color {
    const val TRANSPARENT = 0
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    fun parseColor(s: String): Int = runCatching {
        java.awt.Color.decode(s).rgb or 0xFF000000.toInt()
    }.getOrDefault(0)
    fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    fun red(c: Int): Int = (c shr 16) and 0xFF
    fun green(c: Int): Int = (c shr 8) and 0xFF
    fun blue(c: Int): Int = c and 0xFF
}
