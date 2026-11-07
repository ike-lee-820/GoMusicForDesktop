package coil.compose

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import de.myocd.gomusic.DesktopImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Coil AsyncImage 的桌面平替：签名与调用点完全一致 */
@Composable
fun AsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    filterQuality: FilterQuality = FilterQuality.High,
    alpha: Float = 1f,
) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, model) {
        value = withContext(Dispatchers.IO) { DesktopImages.imageBitmap(model) }
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            filterQuality = filterQuality,
            alpha = alpha,
        )
    }
}
