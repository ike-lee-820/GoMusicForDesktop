package gomusic

import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.AudioPlayerComponent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 播放引擎接口。
 */
interface PlayerEngine {
    val name: String
    fun play(ref: String, isFile: Boolean, startMs: Long)
    fun pause()
    fun resume()
    fun stop()
    fun position(): Long
    fun duration(): Long
    fun seek(ms: Long)
    fun setRate(r: Float): Boolean
}

// =====================================================================
// VLC（vlcj）
// =====================================================================

class VlcEngine : PlayerEngine {
    override val name = "VLC"

    private var comp: AudioPlayerComponent? = null
    private var ended = AtomicBoolean(false)

    var onEnded: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /** 初始化失败（多半是没装 VLC）会抛异常 */
    fun init() {
        if (comp != null) return
        if (!NativeDiscovery().discover()) throw RuntimeException("未找到 VLC（libvlc）")
        val c = AudioPlayerComponent()
        c.mediaPlayer().events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun finished(mediaPlayer: MediaPlayer) {
                if (ended.compareAndSet(false, true)) onEnded?.invoke()
            }

            override fun error(mediaPlayer: MediaPlayer) {
                onError?.invoke("VLC 播放出错")
            }
        })
        comp = c
    }

    private fun mrl(ref: String, isFile: Boolean): String =
        if (isFile) File(ref).toURI().toString() else ref

    override fun play(ref: String, isFile: Boolean, startMs: Long) {
        val c = comp ?: throw RuntimeException("VLC 未初始化")
        ended.set(false)
        val url = mrl(ref, isFile)
        val opts = buildList {
            if (startMs > 0) add(":start-time=${startMs / 1000}")
            add(":no-video")
        }.toTypedArray()
        c.mediaPlayer().media().play(url, *opts)
    }

    override fun pause() { comp?.mediaPlayer()?.controls()?.pause() }

    override fun resume() { comp?.mediaPlayer()?.controls()?.play() }

    override fun stop() { comp?.mediaPlayer()?.controls()?.stop() }

    override fun position(): Long = comp?.mediaPlayer()?.status()?.time() ?: 0L

    override fun duration(): Long = comp?.mediaPlayer()?.status()?.length() ?: 0L

    override fun seek(ms: Long) {
        comp?.mediaPlayer()?.controls()?.setTime(ms)
    }

    override fun setRate(r: Float): Boolean {
        return runCatching { comp?.mediaPlayer()?.controls()?.setRate(r) ?: false }.getOrDefault(false)
    }

    fun release() {
        runCatching { comp?.mediaPlayer()?.release() }
        comp = null
    }
}

// =====================================================================
// 回退：JLayer（纯 Java MP3）
// =====================================================================

class JLayerEngine : PlayerEngine {
    override val name = "内置 MP3 解码"

    private var impl: Mp3Player? = null
    var onEnded: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private fun get(): Mp3Player {
        val p = impl ?: Mp3Player().also {
            it.onEnded = { onEnded?.invoke() }
            it.onError = { m -> onError?.invoke(m) }
        }
        impl = p
        return p
    }

    override fun play(ref: String, isFile: Boolean, startMs: Long) = get().play(ref, isFile, startMs)
    override fun pause() = get().pause()
    override fun resume() = get().resume()
    override fun stop() = get().stop()
    override fun position(): Long = impl?.positionMs ?: 0L
    override fun duration(): Long = impl?.durationMs ?: 0L
    override fun seek(ms: Long) = get().seek(ms)
    override fun setRate(r: Float): Boolean = false
}

// =====================================================================
// 对外：优先 VLC，失败回退 JLayer
// =====================================================================

class MusicPlayer {
    /** 当前引擎名，界面上提示用 */
    var engineName: String = "未初始化"
        private set

    var onEnded: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var vlc: VlcEngine? = null
    private var fallback: JLayerEngine? = null

    private fun engine(): PlayerEngine? {
        vlc?.let { return it }
        fallback?.let { return it }
        // 先试 VLC
        val v = VlcEngine()
        v.onEnded = { onEnded?.invoke() }
        v.onError = { onError?.invoke(it) }
        engineName = try {
            v.init()
            vlc = v
            "VLC"
        } catch (e: Exception) {
            fallback = JLayerEngine().also {
                it.onEnded = { onEnded?.invoke() }
                it.onError = { onError?.invoke(it) }
            }
            onError?.invoke("未检测到 VLC，已回退内置解码器（仅支持 MP3）")
            "内置 MP3 解码"
        }
        return vlc ?: fallback
    }

    fun play(ref: String, isFile: Boolean, startMs: Long = 0) = engine()?.play(ref, isFile, startMs)
    fun pause() = engine()?.pause()
    fun resume() = engine()?.resume()
    fun stop() = engine()?.stop()
    fun position(): Long = engine()?.position() ?: 0L
    fun duration(): Long = engine()?.duration() ?: 0L
    fun seek(ms: Long) = engine()?.seek(ms)
    fun setRate(r: Float): Boolean = engine()?.setRate(r) ?: false
}
