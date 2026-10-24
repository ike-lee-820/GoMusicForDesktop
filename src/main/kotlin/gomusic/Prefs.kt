package gomusic

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 播放历史上限 */
const val MAX_HISTORY = 10000

/** 歌曲快照上限 */
const val MAX_SNAPSHOTS = 3000

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val songs: List<String> = emptyList(),
    val cover: String? = null,
)

/** 一条下载记录：音频 + 封面 + 歌词 + 信息文件 */
@Serializable
data class DownloadRecord(
    val ssid: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val quality: String = "",
    val audio: String = "",
    val cover: String = "",
    val lyric: String = "",
    val meta: String = "",
    val time: Long = 0L,
)

@Serializable
data class PrefsFile(
    val playCounts: Map<String, Int> = emptyMap(),
    val history: List<String> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val downloads: List<DownloadRecord> = emptyList(),
    val quality: String = "normal",
    val playMode: Int = 0,
    /** 简繁转换模式：0 简体 / 1 香港繁体 / 2 仅搜索统一 / 3 不转换 */
    val zhConvert: Int = 0,
    val speed: Float = 1f,
    val themeColor: Long? = null,
    val nmEnabled: Boolean = false,
    val nmCookie: String = "",
    val nmAccount: String = "",
    val songSnapshots: Map<String, Song> = emptyMap(),
)

/** 配置与本地索引，存在 ~/.gomusic/ 下 */
object Prefs {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    val dir: File = File(System.getProperty("user.home"), ".gomusic").apply { mkdirs() }
    private val file = File(dir, "prefs.json")

    private var data: PrefsFile = load()

    private fun load(): PrefsFile = runCatching {
        if (!file.exists()) return@runCatching PrefsFile()
        json.decodeFromString<PrefsFile>(file.readText())
    }.getOrDefault(PrefsFile())

    private fun save() {
        runCatching { file.writeText(json.encodeToString(data)) }
    }

    var playCounts: Map<String, Int>
        get() = data.playCounts
        set(v) { data = data.copy(playCounts = v); save() }

    fun bump(ssid: String) {
        val m = data.playCounts.toMutableMap()
        m[ssid] = (m[ssid] ?: 0) + 1
        data = data.copy(playCounts = m)
        save()
    }

    /** 播放历史（最近在前） */
    var history: List<String>
        get() = data.history
        set(v) { data = data.copy(history = v.take(MAX_HISTORY)); save() }

    var playlists: List<Playlist>
        get() = data.playlists
        set(v) { data = data.copy(playlists = v); save() }

    var downloads: List<DownloadRecord>
        get() = data.downloads
        set(v) { data = data.copy(downloads = v); save() }

    /**
     * 简繁转换模式：
     * 0 转成简体（默认）/ 1 转成香港繁体 / 2 仅搜索统一 / 3 不转换
     */
    var zhConvert: Int
        get() = data.zhConvert
        set(v) { data = data.copy(zhConvert = v); save() }

    var quality: String
        get() = data.quality
        set(v) { data = data.copy(quality = v); save() }

    var playMode: Int
        get() = data.playMode
        set(v) { data = data.copy(playMode = v); save() }

    var speed: Float
        get() = data.speed
        set(v) { data = data.copy(speed = v); save() }

    /** null = 跟随封面取色 */
    var themeColor: Long?
        get() = data.themeColor
        set(v) { data = data.copy(themeColor = v); save() }

    /** 是否接入网易云曲库，默认关闭 */
    var nmEnabled: Boolean
        get() = data.nmEnabled
        set(v) { data = data.copy(nmEnabled = v); save() }

    var nmCookie: String
        get() = data.nmCookie
        set(v) { data = data.copy(nmCookie = v); save() }

    var nmAccount: String
        get() = data.nmAccount
        set(v) { data = data.copy(nmAccount = v); save() }

    /** 歌曲信息快照（含网易云），保证历史随时能显示歌名 */
    var songSnapshots: Map<String, Song>
        get() = data.songSnapshots
        set(v) {
            val trimmed = if (v.size > MAX_SNAPSHOTS) {
                v.entries.sortedByDescending { it.key }.take(MAX_SNAPSHOTS).associate { it.key to it.value }
            } else v
            data = data.copy(songSnapshots = trimmed); save()
        }

}
