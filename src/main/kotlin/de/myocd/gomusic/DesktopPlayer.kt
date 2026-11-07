package de.myocd.gomusic

import android.content.AppContext
import android.content.AppDirs
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.io.File

/**
 * 播放地址解析：与手机端 PlaybackService 的 ResolvingDataSource.Resolver 逐条对齐。
 * MediaItem 的 uri 里带 ?q=&nl=，换音质时 uri 变化 → 这里重新解析 → 音源真的更换。
 */
object DesktopResolver {
    fun resolve(item: MediaItem): Pair<String, Boolean>? {
        val u = item.uri ?: return null
        if (u.scheme != "gomusic") {
            val s = u.toString()
            return when {
                s.startsWith("content://gomusic/") -> localPath(s)?.let { it to true }
                s.startsWith("file:") -> runCatching { File(java.net.URI(s)).absolutePath }.getOrNull()?.let { it to true }
                else -> s to false
            }
        }
        val ssid = u.lastPathSegment ?: return null
        val p = Prefs(AppContext)
        val q = u.getQueryParameter("q")?.takeIf { it.isNotBlank() } ?: p.quality
        val nl = u.getQueryParameter("nl")?.takeIf { it.isNotBlank() } ?: p.nmLevel
        val want = if (neteaseIdOf(ssid) != null && nl.isNotBlank()) nl else q
        val local = LocalLib.audioUri(AppContext, ssid, want)
        if (local != null) {
            localPath(local.toString())?.let { return it to true }
        }
        val nid = neteaseIdOf(ssid)
        val url = runCatching {
            if (nid != null) {
                Netease.cookie = p.nmCookie
                Netease.audioUrl(nid, q, nl.takeIf { it.isNotBlank() })
                    ?: throw IllegalStateException("网易云没有可用音源")
            } else Api.audioUrl(ssid, p.quality)
        }.getOrElse { NmLog.note("解析播放地址失败：${it.message}"); return null }
        // 网易云 CDN 给 http://，统一升 https
        val safe = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url
        NmLog.note("解析到播放地址：$url -> $safe（${if (nid != null) "网易云" else "本站"}）")
        return safe to false
    }

    private fun localPath(s: String): String? = when {
        s.startsWith("content://gomusic/") ->
            File(AppDirs.downloadRoot, s.removePrefix("content://gomusic/")).takeIf { it.exists() }?.absolutePath
        s.startsWith("file:") -> runCatching { File(java.net.URI(s)).takeIf { it.exists() }?.absolutePath }.getOrNull()
        s.startsWith("/") -> File(s).takeIf { it.exists() }?.absolutePath
        else -> null
    }
}

/** 播放引擎：JavaCV(FFmpeg) 解码 + OpenAL 输出，全格式、全平台，无需外部 VLC */
object DesktopPlayer {
    private val engine = gomusic.MusicPlayer()
    var onEnded: (() -> Unit)? = null
    var onPlayingChanged: ((Boolean) -> Unit)? = null
    var repeatMode: Int = Player.REPEAT_MODE_ALL
    var shuffle: Boolean = false
    @Volatile var index: Int = 0
        private set
    @Volatile private var items: List<MediaItem> = emptyList()

    init { engine.onEnded = { onEnded?.invoke() } }

    fun setQueue(list: List<MediaItem>, idx: Int) {
        items = ArrayList(list)
        index = if (items.isEmpty()) 0 else idx.coerceIn(0, items.lastIndex)
    }
    fun playCurrent(posMs: Long = 0) {
        val it = items.getOrNull(index) ?: return
        val (ref, isFile) = DesktopResolver.resolve(it) ?: return
        PlayCache.noteUrl(ref)
        engine.play(ref, isFile, posMs.coerceAtLeast(0))
        onPlayingChanged?.invoke(true)
    }
    fun jumpTo(i: Int, posMs: Long) {
        if (items.isEmpty()) return
        index = when {
            i < 0 && repeatMode == Player.REPEAT_MODE_ALL -> items.lastIndex
            i >= items.size && repeatMode == Player.REPEAT_MODE_ALL -> 0
            else -> i.coerceIn(0, items.lastIndex)
        }
        playCurrent(posMs)
    }
    fun pause() { engine.pause(); onPlayingChanged?.invoke(false) }
    fun resume() { engine.resume(); onPlayingChanged?.invoke(true) }
    fun stop() { engine.stop(); onPlayingChanged?.invoke(false) }
    fun position(): Long = engine.position()
    fun duration(): Long = engine.duration()
    fun seek(ms: Long) { engine.seek(ms) }
    fun setRate(v: Float) { engine.setRate(v) }
}

/** MV 视频：FFmpeg 抓帧渲染到 SwingPanel，音频同步走 FFmpeg+OpenAL */
object DesktopVideo {
    var url: String? = null
    var surface: android.view.SurfaceView? = null
    private val audio = gomusic.MusicPlayer()
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var pos = 0L
    @Volatile private var dur = 0L

    fun start(u: String) {
        stop()
        url = u
        running = true
        runCatching { audio.play(u, false, 0) }
        thread = Thread({
            var grabber: org.bytedeco.javacv.FFmpegFrameGrabber? = null
            try {
                grabber = org.bytedeco.javacv.FFmpegFrameGrabber(u).apply { start() }
                dur = runCatching { grabber.lengthInTime / 1000 }.getOrDefault(0L)
                val conv = org.bytedeco.javacv.Java2DFrameConverter()
                val panel = surface?.component as? java.awt.Component ?: return@Thread
                while (running) {
                    val f = grabber.grabFrame() ?: break
                    pos = runCatching { grabber.timestamp / 1000 }.getOrDefault(pos)
                    if (f.image != null) {
                        val img = conv.convert(f)
                        javax.swing.SwingUtilities.invokeLater {
                            val g = panel.graphics ?: return@invokeLater
                            g.drawImage(img, 0, 0, panel.width, panel.height, null)
                        }
                    }
                }
            } catch (ignored: Exception) {
            } finally { runCatching { grabber?.stop() } }
        }, "gomusic-video").apply { isDaemon = true; start() }
    }
    fun pause() { audio.pause(); running = false }
    fun resume() { audio.resume() }
    fun stop() { running = false; audio.stop(); thread?.interrupt(); thread = null }
    fun position(): Long = pos
    fun duration(): Long = dur
    fun setRate(v: Float) { audio.setRate(v) }
    fun videoSize(): Pair<Int, Int> = 1280 to 720
}
