package androidx.media3.session

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import de.myocd.gomusic.DesktopPlayer

class SessionToken(ctx: Context, cn: ComponentName)

/**
 * MediaController 平替：接口与 media3 完全一致，内部接桌面播放引擎
 * （FFmpeg + OpenAL）。队列、切歌、音质、倍速、随机/循环逻辑不变。
 */
class MediaController private constructor(private val ctx: Context) {
    private var items = ArrayList<MediaItem>()
    private val listeners = ArrayList<Player.Listener>()
    var repeatMode: Int = Player.REPEAT_MODE_ALL
        set(v) { field = v; DesktopPlayer.repeatMode = v }
    var shuffleModeEnabled: Boolean = false
        set(v) { field = v; DesktopPlayer.shuffle = v }
    @Volatile var isPlaying: Boolean = false
        private set

    val currentMediaItemIndex: Int get() = DesktopPlayer.index
    val currentMediaItem: MediaItem? get() = items.getOrNull(DesktopPlayer.index)
    val mediaItemCount: Int get() = items.size
    fun getMediaItemAt(i: Int): MediaItem = items[i]
    val currentPosition: Long get() = DesktopPlayer.position()
    val duration: Long get() = DesktopPlayer.duration().takeIf { it > 0 } ?: C.TIME_UNSET

    init {
        DesktopPlayer.onEnded = { onTrackEnded() }
        DesktopPlayer.onPlayingChanged = { p ->
            isPlaying = p
            listeners.toList().forEach { it.onIsPlayingChanged(p) }
        }
    }

    fun setMediaItems(list: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        items = ArrayList(list)
        DesktopPlayer.setQueue(items, startIndex)
        listeners.toList().forEach { it.onTimelineChanged(Timeline(), 0) }
        DesktopPlayer.playCurrent(startPositionMs)
    }
    fun prepare() {}
    fun play() { DesktopPlayer.resume(); isPlaying = true; listeners.toList().forEach { it.onIsPlayingChanged(true) } }
    fun pause() { DesktopPlayer.pause(); isPlaying = false; listeners.toList().forEach { it.onIsPlayingChanged(false) } }
    fun stop() { DesktopPlayer.stop(); isPlaying = false }
    fun seekTo(ms: Long) { DesktopPlayer.seek(ms) }
    fun seekTo(index: Int, ms: Long) { DesktopPlayer.jumpTo(index, ms) }
    fun seekToNext() { DesktopPlayer.jumpTo(DesktopPlayer.index + 1, 0) }
    fun seekToPrevious() { DesktopPlayer.jumpTo(DesktopPlayer.index - 1, 0) }
    fun addMediaItem(item: MediaItem) { items.add(item); DesktopPlayer.setQueue(items, DesktopPlayer.index) }
    fun moveMediaItem(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        val v = items.removeAt(from); items.add(to, v)
        DesktopPlayer.setQueue(items, DesktopPlayer.index)
    }
    fun removeMediaItem(i: Int) {
        if (i !in items.indices) return
        items.removeAt(i); DesktopPlayer.setQueue(items, DesktopPlayer.index.coerceAtMost(items.lastIndex.coerceAtLeast(0)))
    }
    fun replaceMediaItem(i: Int, item: MediaItem) {
        if (i in items.indices) items[i] = item
        DesktopPlayer.setQueue(items, i)
        DesktopPlayer.playCurrent(DesktopPlayer.position())
    }
    fun setPlaybackSpeed(v: Float) { DesktopPlayer.setRate(v) }
    fun addListener(l: Player.Listener) { listeners.add(l) }
    fun removeListener(l: Player.Listener) { listeners.remove(l) }
    fun release() { DesktopPlayer.stop() }

    private fun onTrackEnded() {
        val next = when {
            repeatMode == Player.REPEAT_MODE_ONE -> DesktopPlayer.index
            shuffleModeEnabled && items.size > 1 -> {
                var n = DesktopPlayer.index
                while (n == DesktopPlayer.index) n = (0 until items.size).random()
                n
            }
            DesktopPlayer.index + 1 < items.size -> DesktopPlayer.index + 1
            repeatMode == Player.REPEAT_MODE_ALL -> 0
            else -> return
        }
        DesktopPlayer.jumpTo(next, 0)
        notifyTransition()
    }
    private fun notifyTransition() {
        val it = currentMediaItem
        listeners.toList().forEach { l ->
            l.onMediaItemTransition(it, 0)
            l.onTimelineChanged(Timeline(), 0)
        }
    }

    class Builder(private val ctx: Context, private val token: SessionToken) {
        fun buildAsync(): ListenableFuture<MediaController> =
            Futures.immediateFuture(instance)
    }
    companion object {
        val instance: MediaController by lazy { MediaController(android.content.AppContext) }
    }
}

class MediaSession {
    class Callback {
        open fun onAddMediaItems(session: MediaSession, controller: ControllerInfo, items: List<MediaItem>): List<MediaItem> = items
    }
    class ControllerInfo
    class Builder(ctx: Context, player: Any) {
        fun setCallback(cb: Callback): Builder = this
        fun setSessionActivity(pi: Any?): Builder = this
        fun build(): MediaSession = MediaSession()
    }
}
open class MediaSessionService : android.app.Application()
