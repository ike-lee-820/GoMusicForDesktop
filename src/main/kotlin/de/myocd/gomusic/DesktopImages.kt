package de.myocd.gomusic

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/** 桌面图片加载：OkHttp + ImageIO（含 WebP）+ Compose ImageBitmap。
 *  与手机端 Coil 的差别只是实现，URL / 缓存 / 调用点全部不变。 */
object DesktopImages {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun bytesOf(model: Any?): ByteArray? {
        return when (model) {
            null -> null
            is ByteArray -> model
            is File -> runCatching { model.takeIf { it.exists() }?.readBytes() }.getOrNull()
            is Uri -> bytesOf(model.toString())
            is String -> {
                val s = model
                when {
                    s.startsWith("content://gomusic/") ->
                        runCatching { File(android.content.AppDirs.downloadRoot, s.removePrefix("content://gomusic/")).takeIf { it.exists() }?.readBytes() }.getOrNull()
                    s.startsWith("file://") -> runCatching { File(java.net.URI(s)).takeIf { it.exists() }?.readBytes() }.getOrNull()
                    s.startsWith("/") -> runCatching { File(s).takeIf { it.exists() }?.readBytes() }.getOrNull()
                    s.startsWith("http") -> {
                        // 先读手机端同款磁盘封面缓存
                        val hit = ImageCache.cached(s)
                        if (hit != null) runCatching { File(hit).readBytes() }.getOrNull()
                        else {
                            val b = fetch(s)
                            if (b != null) runCatching { ImageCache.store(s) }
                            b
                        }
                    }
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun fetch(url: String): ByteArray? = runCatching {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) GoMusicDesktop/26.11.8")
            .build()
        client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    }.getOrNull()

    fun buffered(model: Any?): BufferedImage? = runCatching {
        val b = bytesOf(model) ?: return null
        ImageIO.read(ByteArrayInputStream(b))
    }.getOrNull()

    fun bitmap(model: Any?): Bitmap? = buffered(model)?.let { Bitmap(it) }

    fun imageBitmap(model: Any?): ImageBitmap? = buffered(model)?.toComposeImageBitmap()
}
