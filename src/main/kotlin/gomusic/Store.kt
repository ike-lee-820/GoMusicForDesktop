package gomusic

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LocalLib {
    const val ROOT = "GoMusic"
    const val AUDIO_DIR = "音乐"
    const val COVER_DIR = "封面"
    const val LYRIC_DIR = "歌词"
    const val META_DIR = "歌曲信息"

    /** 下载根目录：优先 ~/Downloads，没有就用 ~/Music */
    val root: File by lazy {
        val home = File(System.getProperty("user.home"))
        val dl = File(home, "Downloads")
        val base = if (dl.exists() || dl.mkdirs()) dl else File(home, "Music")
        File(base, ROOT).apply { mkdirs() }
    }

    private fun sub(name: String): File = File(root, name).apply { mkdirs() }

    private fun safe(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(80).ifBlank { "unknown" }

    /** 已下载的歌曲转成列表用的 Song（封面指向本地文件） */
    fun songs(recs: List<DownloadRecord>): List<Song> =
        recs.map { Song(it.ssid, it.title, it.artist, it.album, it.cover.ifBlank { null }) }

    fun downloadedIds(recs: List<DownloadRecord>): Set<String> = recs.mapTo(HashSet()) { it.ssid }

    /** 下载一首歌：音频 + 封面 + 歌词 + 歌曲信息 */
    fun download(song: Song, quality: String, onProgress: ((Long, Long) -> Unit)? = null): DownloadRecord {
        val d = runCatching { Api.detail(song.ssid) }
            .getOrElse { SongDetail(song.ssid, "", coverRef(song.coverUrl), emptyMap()) }
        val url = if (d.audio.isEmpty()) {
            Api.audioUrlMp3(song.ssid, quality)
        } else {
            val mp3 = d.audio.mapValues { it.value.firstOrNull { u -> u.contains(".mp3", true) } }
                .filterValues { it != null }.mapValues { it.value!! }
            val src = if (mp3.isEmpty()) d.audio.mapValues { it.value.first() } else mp3
            val key = src.keys.minByOrNull { kotlin.math.abs(Api.rank(it) - Api.rank(quality)) }!!
            src[key]!!
        }
        val base = safe("${song.artist} - ${song.title}")
        val ext = url.substringBefore('?').substringAfterLast('.', "mp3")
            .takeIf { it.length in 2..4 } ?: "mp3"

        // 音频
        val audioFile = File(sub(AUDIO_DIR), "$base.$ext")
        audioFile.outputStream().use { Api.fetch(url, it, onProgress) }

        // 封面
        var coverRef = ""
        val coverUrl = d.cover ?: coverRef(song.coverUrl)
        if (!coverUrl.isNullOrBlank()) {
            val bytes = if (coverUrl.startsWith("data:")) decodeDataUri(coverUrl) else Api.bytes(coverUrl)
            if (bytes != null) {
                val f = File(sub(COVER_DIR), "$base.jpg")
                f.writeBytes(bytes)
                coverRef = f.absolutePath
            }
        }

        // 歌词
        var lrc = d.lyrics
        if (lrc.isBlank()) lrc = runCatching { Api.lyrics(song.ssid) }.getOrDefault("")
        var lyricRef = ""
        if (lrc.isNotBlank()) {
            val f = File(sub(LYRIC_DIR), "$base.lrc")
            f.writeText(lrc)
            lyricRef = f.absolutePath
        }

        val rec = DownloadRecord(
            ssid = song.ssid,
            title = song.title,
            artist = song.artist,
            album = song.album,
            quality = quality,
            audio = audioFile.absolutePath,
            cover = coverRef,
            lyric = lyricRef,
            meta = "",
            time = System.currentTimeMillis(),
        )

        // 歌曲信息
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(rec.time))
        val metaText = buildString {
            appendLine("标题：${song.title}")
            appendLine("歌手：${song.artist}")
            appendLine("专辑：${song.album}")
            appendLine("ssid：${song.ssid}")
            appendLine("音质：$quality")
            appendLine("音频地址：$url")
            appendLine("封面：${song.coverUrl ?: ""}")
            appendLine("歌词：${if (lyricRef.isEmpty()) "无" else "$base.lrc"}")
            appendLine("下载时间：$stamp")
            appendLine()
            appendLine(lrc)
        }
        val meta = File(sub(META_DIR), "$base.txt")
        meta.writeText(metaText)
        return rec.copy(meta = meta.absolutePath)
    }

    fun readText(ref: String): String {
        if (ref.isBlank()) return ""
        return runCatching { File(ref).takeIf { it.exists() }?.readText() ?: "" }.getOrDefault("")
    }

    fun delete(rec: DownloadRecord) {
        listOf(rec.audio, rec.cover, rec.lyric, rec.meta).forEach {
            if (it.isBlank()) return@forEach
            runCatching { File(it).delete() }
        }
    }
}
