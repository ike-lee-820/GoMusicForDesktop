package gomusic

import com.jogamp.openal.AL
import com.jogamp.openal.ALC
import com.jogamp.openal.ALCcontext
import com.jogamp.openal.ALCdevice
import com.jogamp.openal.ALFactory
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
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
// OpenAL 常量（直接用数值，避免依赖具体常量类的命名）
// =====================================================================

private const val AL_NONE = 0
private const val AL_FORMAT_MONO16 = 0x1101
private const val AL_FORMAT_STEREO16 = 0x1103
private const val AL_SOURCE_STATE = 0x1010
private const val AL_PLAYING = 0x1012
private const val AL_PAUSED = 0x1013
private const val AL_STOPPED = 0x1014
private const val AL_BUFFERS_PROCESSED = 0x1016
private const val AL_PITCH = 0x1003
private const val AL_GAIN = 0x100A

private const val NUM_BUFFERS = 4
/** 每个缓冲区的采样帧数（每声道） */
private const val FRAMES_PER_BUFFER = 4096

// =====================================================================
// FFmpeg 解码（JavaCV）+ OpenAL 输出（JOAL）
// =====================================================================

class JavaCvEngine : PlayerEngine {
    override val name = "FFmpeg + OpenAL"

    var onEnded: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var thread: Thread? = null
    private val stopFlag = AtomicBoolean(false)
    @Volatile private var pauseFlag = false

    @Volatile private var posMs = 0L
    @Volatile private var durMs = 0L
    @Volatile private var curRef: String? = null
    @Volatile private var curIsFile = false

    /** 初始化探测：能拿到 AL 与 FFmpeg 才认为可用 */
    fun init() {
        val al = runCatching { ALFactory.getAL() }.getOrNull()
            ?: throw RuntimeException("OpenAL 不可用")
        val alc = runCatching { ALFactory.getALC() }.getOrNull()
            ?: throw RuntimeException("OpenAL 不可用")
        val dev: ALCdevice? = runCatching { alc.alcOpenDevice(null as String?) }.getOrNull()
        if (dev == null) throw RuntimeException("没有可用的音频输出设备")
        runCatching { alc.alcCloseDevice(dev) }
        // 触发一次类加载，确认 FFmpeg 原生库能加载
        runCatching { Class.forName("org.bytedeco.javacv.FFmpegFrameGrabber") }
            .getOrElse { throw RuntimeException("FFmpeg 不可用") }
    }

    override fun play(ref: String, isFile: Boolean, startMs: Long) {
        stop()
        curRef = ref
        curIsFile = isFile
        stopFlag.set(false)
        pauseFlag = false
        posMs = startMs
        durMs = 0
        thread = Thread({ run(ref, startMs) }, "gomusic-al").apply { isDaemon = true; start() }
    }

    override fun pause() { pauseFlag = true }

    override fun resume() { pauseFlag = false }

    override fun seek(ms: Long) {
        val ref = curRef ?: return
        play(ref, curIsFile, ms.coerceAtLeast(0))
    }

    override fun position(): Long = posMs
    override fun duration(): Long = durMs

    /** 当前音源，用于实时改倍速；0 表示没有在播 */
    @Volatile private var curSource = 0
    @Volatile private var pitch = 1f

    override fun setRate(r: Float): Boolean {
        pitch = r
        val s = curSource
        if (s != 0) {
            runCatching { ALFactory.getAL().alSourcef(s, AL_PITCH, r) }
        }
        // OpenAL 的 PITCH 会同时改变音调，这是它原生支持的变速手段
        return true
    }

    override fun stop() {
        stopFlag.set(true)
        pauseFlag = false
        thread?.join(1500)
        thread = null
    }

    private class Sink(val al: AL, val source: Int) {
        /** bufferId -> 该缓冲区的采样帧数 */
        val sizes = HashMap<Int, Int>()
        var written = 0L
        var played = 0L
    }

    private fun fill(al: AL, g: FFmpegFrameGrabber, channels: Int, buf: Int, sink: Sink): Boolean {
        val target = FRAMES_PER_BUFFER * channels
        val acc = ShortArray(target)
        var n = 0
        while (n < target) {
            val f: Frame = try {
                g.grabSamples() ?: break
            } catch (e: Exception) {
                break
            }
            val s = f.samples?.firstOrNull() ?: break
            when (s) {
                is ShortBuffer -> {
                    val rem = s.remaining()
                    val take = minOf(rem, target - n)
                    for (i in 0 until take) acc[n + i] = s.get()
                    n += take
                }
                is FloatBuffer -> {
                    val rem = s.remaining()
                    val take = minOf(rem, target - n)
                    for (i in 0 until take) {
                        val v = (s.get() * 32767f).toInt().coerceIn(-32768, 32767)
                        acc[n + i] = v.toShort()
                    }
                    n += take
                }
                else -> break
            }
        }
        if (n == 0) return false
        val bb = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) bb.putShort(acc[i])
        bb.flip()
        val fmt = if (channels >= 2) AL_FORMAT_STEREO16 else AL_FORMAT_MONO16
        al.alBufferData(buf, fmt, bb as java.nio.Buffer, n * 2, g.sampleRate)
        sink.sizes[buf] = n / channels
        sink.written += (n / channels).toLong()
        return true
    }

    private fun run(ref: String, startMs: Long) {
        var al: AL? = null
        var alc: ALC? = null
        var device: ALCdevice? = null
        var ctx: ALCcontext? = null
        var g: FFmpegFrameGrabber? = null
        try {
            al = ALFactory.getAL()
            alc = ALFactory.getALC()
            val dev = alc.alcOpenDevice(null as String?)
            if (dev == null) { onError?.invoke("没有可用的音频输出设备"); return }
            device = dev
            val c = alc.alcCreateContext(dev, null as IntArray?, 0)
            if (c == null) { onError?.invoke("无法创建音频上下文"); return }
            ctx = c
            alc.alcMakeContextCurrent(c)

            val grab = FFmpegFrameGrabber(ref)
            grab.sampleMode = FrameGrabber.SampleMode.SHORT
            grab.start()
            g = grab

            val sampleRate = grab.sampleRate
            val channels = grab.audioChannels.coerceAtLeast(1)
            if (sampleRate <= 0) { onError?.invoke("无法解析音频格式"); return }
            val len = runCatching { grab.lengthInTime }.getOrDefault(0L)
            durMs = if (len > 0) len / 1000 else 0
            if (startMs > 0) runCatching { grab.setTimestamp(startMs * 1000) }

            val bufs = IntArray(NUM_BUFFERS)
            al.alGenBuffers(NUM_BUFFERS, bufs, 0)
            val srcArr = IntArray(1)
            al.alGenSources(1, srcArr, 0)
            val source = srcArr[0]
            curSource = source
            if (pitch != 1f) runCatching { al.alSourcef(source, AL_PITCH, pitch) }
            val sink = Sink(al, source)

            var queued = 0
            for (b in bufs) {
                if (!fill(al, grab, channels, b, sink)) break
                al.alSourceQueueBuffers(source, 1, intArrayOf(b), 0)
                queued++
            }
            if (queued == 0) {
                onError?.invoke("没有可播放的音频数据")
                return
            }
            al.alSourcePlay(source)
            posMs = startMs

            val stateArr = IntArray(1)
            val procArr = IntArray(1)
            var eof = false
            var offsetBase = System.currentTimeMillis()

            while (!stopFlag.get()) {
                if (pauseFlag) {
                    al.alSourcePause(source)
                    while (pauseFlag && !stopFlag.get()) Thread.sleep(40)
                    if (stopFlag.get()) break
                    al.alSourcePlay(source)
                    offsetBase = System.currentTimeMillis()
                }

                al.alGetSourcei(source, AL_BUFFERS_PROCESSED, procArr, 0)
                if (procArr[0] > 0) {
                    val out = IntArray(procArr[0])
                    al.alSourceUnqueueBuffers(source, procArr[0], out, 0)
                    for (b in out) {
                        sink.played += (sink.sizes.remove(b) ?: 0).toLong()
                        if (!eof && fill(al, grab, channels, b, sink)) {
                            al.alSourceQueueBuffers(source, 1, intArrayOf(b), 0)
                        } else {
                            eof = true
                        }
                    }
                }

                al.alGetSourcei(source, AL_SOURCE_STATE, stateArr, 0)
                if (eof && stateArr[0] != AL_PLAYING) break
                posMs = startMs + sink.played * 1000L / sampleRate
                Thread.sleep(40)
            }

            curSource = 0
            al.alSourceStop(source)
            al.alDeleteSources(1, intArrayOf(source), 0)
            al.alDeleteBuffers(NUM_BUFFERS, bufs, 0)
            if (!stopFlag.get() && sink.played > 0) onEnded?.invoke()
        } catch (e: Exception) {
            if (!stopFlag.get()) onError?.invoke(e.message ?: "播放失败")
        } finally {
            runCatching { g?.close() }
            ctx?.let {
                runCatching { alc?.alcMakeContextCurrent(null as ALCcontext?) }
                runCatching { alc?.alcDestroyContext(it) }
            }
            device?.let { runCatching { alc?.alcCloseDevice(it) } }
        }
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
// 对外：优先 FFmpeg + OpenAL，失败回退 JLayer
// =====================================================================

class MusicPlayer {
    /** 当前引擎名，界面上提示用 */
    var engineName: String = "未初始化"
        private set

    var onEnded: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var main: JavaCvEngine? = null
    private var fallback: JLayerEngine? = null

    private fun engine(): PlayerEngine? {
        main?.let { return it }
        fallback?.let { return it }
        val j = JavaCvEngine()
        j.onEnded = { onEnded?.invoke() }
        j.onError = { onError?.invoke(it) }
        engineName = try {
            j.init()
            main = j
            "FFmpeg + OpenAL"
        } catch (e: Exception) {
            fallback = JLayerEngine().also {
                it.onEnded = { onEnded?.invoke() }
                it.onError = { onError?.invoke(it) }
            }
            onError?.invoke("FFmpeg/OpenAL 不可用，已回退内置解码器（仅 MP3）")
            "内置 MP3 解码"
        }
        return main ?: fallback
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
