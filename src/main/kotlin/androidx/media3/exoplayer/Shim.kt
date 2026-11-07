package androidx.media3.exoplayer

import android.content.Context
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import de.myocd.gomusic.DesktopPlayer
import de.myocd.gomusic.DesktopVideo

/** ExoPlayer 平替：MV 视频页用（音频走 FFmpeg，画面渲染到 SwingPanel） */
class ExoPlayer private constructor(private val ctx: Context) {
    private val listeners = ArrayList<Player.Listener>()
    var playWhenReady: Boolean = true
        set(v) { field = v; if (v) DesktopVideo.resume() else DesktopVideo.pause() }
    val currentPosition: Long get() = DesktopVideo.position()
    val duration: Long get() = DesktopVideo.duration()
    fun setMediaItem(item: MediaItem) { DesktopVideo.url = item.uri?.toString(); notifyVideoSize() }
    fun prepare() {}
    fun play() { playWhenReady = true; DesktopVideo.start(urlValue()) }
    fun pause() { playWhenReady = false; DesktopVideo.pause() }
    fun stop() { DesktopVideo.stop() }
    fun release() { DesktopVideo.stop() }
    fun setPlaybackSpeed(v: Float) { DesktopVideo.setRate(v) }
    fun seekTo(ms: Long) { DesktopVideo.seek(ms) }
    fun setVideoSurfaceView(sv: SurfaceView?) { DesktopVideo.surface = sv }
    fun addListener(l: Player.Listener) { listeners.add(l); notifyVideoSize() }
    fun removeListener(l: Player.Listener) { listeners.remove(l) }
    private fun urlValue(): String = DesktopVideo.url ?: ""
    private fun notifyVideoSize() {
        val (w, h) = DesktopVideo.videoSize()
        listeners.toList().forEach { it.onVideoSizeChanged(androidx.media3.common.VideoSize(w, h)) }
    }
    class Builder(private val ctx: Context) {
        fun setMediaSourceFactory(f: Any?): Builder = this
        fun setLoadControl(c: Any?): Builder = this
        fun setAudioAttributes(a: Any?, b: Boolean): Builder = this
        fun setHandleAudioBecomingNoisy(b: Boolean): Builder = this
        fun setWakeMode(m: Int): Builder = this
        fun build(): ExoPlayer = ExoPlayer(ctx)
    }
}
