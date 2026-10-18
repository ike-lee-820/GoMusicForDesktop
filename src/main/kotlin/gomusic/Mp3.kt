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
 * 回退播放内核：JLayer（纯 Java MP3 解码）+ javax.sound.sampled 输出。
 * 只在没装 VLC 时用；只支持 MP3，不支持倍速。
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

    fun play(ref: String, isFile: Boolean, startMs: Long = 0) {
        stop()
        currentRef = ref
        currentIsFile = isFile
        stopFlag = false
        pauseFlag = false
        positionMs = startMs
        durationMs = 0
        thread = Thread({ run(ref, isFile, startMs) }, "gomusic-mp3").apply { isDaemon = true; start() }
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

    private fun open(ref: String, isFile: Boolean, skipBytes: Long): Pair<Counting, Long>? = try {
        if (isFile) {
            val f = File(ref)
            if (!f.exists()) null
            else {
                val s = Counting(BufferedInputStream(FileInputStream(f), 64 * 1024))
                if (skipBytes > 0) s.skip(skipBytes)
                s to f.length()
            }
        } else {
            val conn = URL(ref).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 40000
            conn.setRequestProperty("Connection", "close")
            conn.connect()
            if (conn.responseCode !in 200..299) null
            else {
                val s = Counting(BufferedInputStream(conn.inputStream, 64 * 1024))
                if (skipBytes > 0) s.skip(skipBytes)
                s to conn.contentLengthLong
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun seekBytes(ms: Long, bps: Double): Long =
        if (bps <= 0) 0 else (ms / 1000.0 * bps / 8.0).toLong()

    private fun run(ref: String, isFile: Boolean, startMs: Long) {
        var line: SourceDataLine? = null
        var stream: Counting? = null
        try {
            val bps = if (startMs > 0) {
                if (isFile) probeBpsOfFile(ref) ?: 0.0 else probeBps(ref) ?: 0.0
            } else 0.0
            val opened = open(ref, isFile, seekBytes(startMs, bps)) ?: run {
                onError?.invoke("无法打开音频")
                return
            }
            stream = opened.first
            val fileSize = opened.second

            val bs = Bitstream(stream)
            val dec = Decoder()
            var frames = 0
            var posMs = startMs
            var lastEmit = 0L
            var startedAt = 0L
            var byteBase = 0L
            var msPerFrame = 26.0

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
                val buf = try {
                    dec.decodeFrame(h, bs) as SampleBuffer
                } catch (e: Exception) {
                    bs.closeFrame()
                    continue
                }
                bs.closeFrame()

                if (line == null) {
                    val freq = h.frequency()
                    val ch = if (h.mode() == 3) 1 else 2
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

                if (frames == 24 && fileSize > 0) {
                    val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
                    val measured = (stream.count - byteBase) * 8.0 * 1000.0 / elapsed
                    if (measured > 0) {
                        durationMs = (fileSize * 8.0 * 1000.0 / measured).toLong()
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

    private fun bpsOf(s: Counting, h: Header, buf: SampleBuffer): Double {
        val ch = if (h.mode() == 3) 1 else 2
        val spf = (buf.bufferLength / ch).coerceAtLeast(1)
        val ms = spf * 1000.0 / h.frequency()
        return s.count.toDouble() * 8.0 / (ms / 1000.0)
    }

    private fun probeBps(ref: String): Double? = try {
        val conn = URL(ref).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Range", "bytes=0-65535")
        conn.connect()
        if (conn.responseCode !in 200..299) null
        else {
            val s = Counting(BufferedInputStream(conn.inputStream))
            val bs = Bitstream(s)
            val dec = Decoder()
            val h = bs.readFrame() ?: return null
            val buf = dec.decodeFrame(h, bs) as SampleBuffer
            bs.closeFrame()
            val r = bpsOf(s, h, buf)
            runCatching { s.close() }
            r
        }
    } catch (e: Exception) {
        null
    }

    private fun probeBpsOfFile(ref: String): Double? = try {
        val s = Counting(BufferedInputStream(FileInputStream(ref)))
        val bs = Bitstream(s)
        val dec = Decoder()
        val h = bs.readFrame() ?: return null
        val buf = dec.decodeFrame(h, bs) as SampleBuffer
        bs.closeFrame()
        val r = bpsOf(s, h, buf)
        runCatching { s.close() }
        r
    } catch (e: Exception) {
        null
    }
}
