package coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.request.ImageRequest
import de.myocd.gomusic.DesktopImages

class ImageResult(val drawable: android.graphics.drawable.Drawable?)
object ImageLoader {
    fun execute(req: ImageRequest): ImageResult {
        val bmp = DesktopImages.bitmap(req.data)
        return ImageResult(bmp?.let { BitmapDrawable(it) })
    }
}
val Context.imageLoader: ImageLoader get() = ImageLoader
