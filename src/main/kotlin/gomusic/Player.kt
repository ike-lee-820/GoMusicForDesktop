package gomusic

import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.Header
import javazoom.jl.decoder.SampleBuffer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * 桌面版播放内核。
 *
 * 用 JLayer（纯 Java MP3 解码）+ javax.sound.sampled 输出：
 * 不依赖系统解码器、VLC 或 JavaFX，Windows / Linux / macOS 的 x64 与 ARM64 都能跑。
 * 代价是只支持 MP3；无损（flac）在选音质时会优先挑 mp3 地址，见 [Api.audioUrlMp3]。
 */
class Mp3Player {
    var onPosition: ((Long) -> Unit)? = null
    var onDuration: ((Long) -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var thread: Thread? = null
    @Volatile private var stopFlag = false
    @Volatile private var pauseFlag = false
    @Volatile private var currentRef: String? = null
    @Volatile private var currentIsFile = false

    @Volatile var positionMs: Long = 0
        private set
    @Volatile var durationMs: Long = 0
        private set

    val isPlaying: Boolean get() = thread != null && !pauseFlag

    /** [ref] 可以是 http 地址，也可以是本地文件路径 */
    fun play(ref: String, isFile: Boolean, startMs: Long = 0) {
        stop()
        currentRef = ref
        currentIsFile = isFile
        stopFlag = false
        pauseFlag = false
        positionMs = startMs
        durationMs = 0
        thread = Thread({ run(ref, isFile, startMs) }, "gomusic-player").apply {
            isDaemon = true
            start()
        }
    }

    fun pause() { pauseFlag = true }

    fun resume() { pauseFlag = false }

    fun seek(ms: Long) {
        val ref = currentRef ?: return
        play(ref, currentIsFile, ms.coerceAtLeast(0))
    }

    fun stop() {
        stopFlag = true
        pauseFlag = false
        thread?.join(1000)
        thread = null
    }

    private class Counting(private val src: InputStream) : InputStream() {
        @Volatile var count = 0L
        override fun read(): Int {
            val b = src.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = src.read(b, off, len)
            if (n > 0) count += n
            return n
        }

        override fun skip(n: Long): Long {
            val s = src.skip(n)
            count += s
            return s
        }

        override fun available(): Int = src.available()
        override fun close() = src.close()
    }

    private fun open(ref: String, isFile: Boolean, skipBytes: Long): Pair<Counting, Long>? {
        return try {
            if (isFile) {
                val f = File(ref)
                if (!f.exists()) return null
                val s = Counting(BufferedInputStream(FileInputStream(f), 64 * 1024))
                if (skipBytes > 0) s.skip(skipBytes)
                s to f.length()
            } else {
                val conn = URL(ref).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 40000
                conn.setRequestProperty("Connection", "close")
                conn.connect()
                if (conn.responseCode !in 200..299) return null
                val size = conn.contentLengthLong
                val s = Counting(BufferedInputStream(conn.inputStream, 64 * 1024))
                if (skipBytes > 0) s.skip(skipBytes)
                s to size
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 按已测得的码率估算要跳过的字节数 */
    private fun seekBytes(ms: Long, bps: Double): Long =
        if (bps <= 0) 0 else (ms / 1000.0 * bps / 8.0).toLong()

    private fun run(ref: String, isFile: Boolean, startMs: Long) {
        var line: SourceDataLine? = null
        var stream: Counting? = null
        try {
            var bps = 0.0
            // 需要跳转时先测码率，才能换成要跳过的字节数
            if (startMs > 0) {
                bps = if (isFile) probeBpsOfFile(ref) ?: 0.0 else probeBps(ref) ?: 0.0
            }
            val opened = open(ref, isFile, seekBytes(startMs, bps))
            if (opened == null) {
                onError?.invoke("无法打开音频")
                return
            }
            stream = opened.first
            val fileSize = opened.second

            val bs = Bitstream(stream)
            val dec = Decoder()
            var freq = 44100
            var ch = 2
            var msPerFrame = 26.0
            var frames = 0
            var posMs = startMs
            var lastEmit = 0L
            var startedAt = System.currentTimeMillis()
            var byteBase = 0L

            while (!stopFlag) {
                if (pauseFlag) {
                    runCatching { line?.stop() }
                    while (pauseFlag && !stopFlag) Thread.sleep(50)
                    runCatching { line?.flush(); line?.start() }
                    continue
                }
                val h: Header = try {
                    bs.readFrame() ?: break
                } catch (e: Exception) {
                    break
                }
                val buf: SampleBuffer = try {
                    dec.decodeFrame(h, bs)
                } catch (e: Exception) {
                    bs.closeFrame()
                    continue
                }
                bs.closeFrame()

                if (line == null) {
                    freq = h.frequency()
                    ch = if (h.mode() == 3) 1 else 2 // 3 = Header.SINGLE_CHANNEL
                    val spf = (buf.bufferLength / ch).coerceAtLeast(1)
                    msPerFrame = spf * 1000.0 / freq
                    val fmt = AudioFormat(freq.toFloat(), 16, ch, true, false)
                    line = AudioSystem.getSourceDataLine(fmt)
                    line.open(fmt, 32 * 1024)
                    line.start()
                    byteBase = stream.count
                    startedAt = System.currentTimeMillis()
                }

                val pcm = buf.buffer
                val len = buf.bufferLength
                val bytes = ByteArray(len * 2)
                var i = 0
                while (i < len) {
                    val v = pcm[i].toInt()
                    bytes[i * 2] = (v and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                    i++
                }
                line.write(bytes, 0, bytes.size)

                frames++
                posMs = startMs + (frames * msPerFrame).toLong()
                positionMs = posMs

                // 用前若干帧测码率，再按文件大小估算总时长（CBR 精确，VBR 近似）
                if (frames == 24 && fileSize > 0) {
                    val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
                    bps = (stream.count - byteBase) * 8.0 * 1000.0 / elapsed
                    if (bps > 0) {
                        durationMs = (fileSize * 8.0 * 1000.0 / bps).toLong()
                        onDuration?.invoke(durationMs)
                    }
                }

                val now = System.currentTimeMillis()
                if (now - lastEmit > 250) {
                    lastEmit = now
                    onPosition?.invoke(posMs)
                }
            }

            runCatching { line?.drain() }
            onPosition?.invoke(posMs)
            if (!stopFlag && frames > 0) onEnded?.invoke()
        } catch (e: Exception) {
            if (!stopFlag) onError?.invoke(e.message ?: "播放失败")
        } finally {
            runCatching { line?.close() }
            runCatching { stream?.close() }
        }
    }

    /** 读几帧估算码率（远程地址 seek 前用） */
    private fun probeBps(ref: String): Double? {
        return try {
            val conn = URL(ref).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Range", "bytes=0-65535")
            conn.connect()
            if (conn.responseCode !in 200..299) return null
            val s = Counting(BufferedInputStream(conn.inputStream))
            val bs = Bitstream(s)
            val dec = Decoder()
            val h = bs.readFrame() ?: return null
            val buf = dec.decodeFrame(h, bs)
            bs.closeFrame()
            val ch = if (h.mode() == 3) 1 else 2
            val spf = (buf.bufferLength / ch).coerceAtLeast(1)
            val ms = spf * 1000.0 / h.frequency()
            val bytes = s.count.toDouble()
            val r = bytes * 8.0 / (ms / 1000.0)
            runCatching { s.close() }
            r
        } catch (e: Exception) {
            null
        }
    }

    private fun probeBpsOfFile(ref: String): Double? {
        return try {
            val f = File(ref)
            val s = Counting(BufferedInputStream(FileInputStream(f)))
            val bs = Bitstream(s)
            val dec = Decoder()
            val h = bs.readFrame() ?: return null
            val buf = dec.decodeFrame(h, bs)
            bs.closeFrame()
            val ch = if (h.mode() == 3) 1 else 2
            val spf = (buf.bufferLength / ch).coerceAtLeast(1)
            val ms = spf * 1000.0 / h.frequency()
            val r = s.count.toDouble() * 8.0 / (ms / 1000.0)
            runCatching { s.close() }
            r
        } catch (e: Exception) {
            null
        }
    }
}
