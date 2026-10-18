package gomusic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.abs

val DefaultPrimary = Color(0xFFD0BCFF)

/** 二级页面（在主界面左侧区域内切换） */
sealed class Page {
    data class Album(val artist: String, val name: String) : Page()
    data class Artist(val name: String) : Page()
    data class PlaylistPage(val id: String) : Page()
}

data class Group(
    val title: String,
    val subtitle: String,
    val cover: String?,
    val songs: List<Song>,
    val artist: String = "",
    val album: String = "",
)

data class DlStatus(
    val title: String,
    val index: Int,
    val total: Int,
    val done: Long,
    val size: Long,
    val bps: Long,
) {
    val percent: Int get() = if (size > 0) (done * 100 / size).toInt().coerceIn(0, 100) else -1
}

fun sizeText(bytes: Long): String {
    if (bytes <= 0) return ""
    val kb = bytes / 1024.0
    return when {
        kb < 1024 -> "%.0f KB".format(kb)
        kb < 1024 * 1024 -> "%.1f MB".format(kb / 1024)
        else -> "%.2f GB".format(kb / 1024 / 1024)
    }
}

fun speedText(bps: Long): String = if (bps <= 0) "" else "${sizeText(bps)}/s"

class AppState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val player = MusicPlayer()
    /** 当前播放内核名（VLC / 内置 MP3 解码） */
    var engineName: String = ""
        get() = player.engineName
        private set

    // ---------- 数据 ----------
    var songs by mutableStateOf<List<Song>>(emptyList()); private set
    var artistInfo by mutableStateOf<Map<String, ArtistInfo>>(emptyMap()); private set
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var offline by mutableStateOf(false); private set
    var downloads by mutableStateOf(Prefs.downloads); private set
    val downloadedIds: Set<String> get() = LocalLib.downloadedIds(downloads)
    var downloading by mutableStateOf(emptySet<String>()); private set
    var dlStatus by mutableStateOf<DlStatus?>(null); private set

    var playlists by mutableStateOf(Prefs.playlists); private set
    var counts by mutableStateOf(Prefs.playCounts); private set

    // ---------- 设置 ----------
    var quality by mutableStateOf(Prefs.quality); private set
    var playMode by mutableStateOf(Prefs.playMode); private set
    var speed by mutableStateOf(Prefs.speed); private set
    var fixedTheme by mutableStateOf(Prefs.themeColor?.let { Color(it.toULong()) }); private set
    var coverColor by mutableStateOf<Color?>(null); private set
    val primary: Color get() = fixedTheme ?: coverColor ?: DefaultPrimary

    // ---------- 界面 ----------
    var tab by mutableStateOf(0)
    var query by mutableStateOf("")
    var page by mutableStateOf<Page?>(null)
    var showSettings by mutableStateOf(false)
    var toastMsg by mutableStateOf<String?>(null); private set
    private var toastJob: kotlinx.coroutines.Job? = null

    // ---------- 播放 ----------
    var queue by mutableStateOf<List<Song>>(emptyList()); private set
    var curIndex by mutableStateOf(0); private set
    var curSsid by mutableStateOf<String?>(null); private set
    var isPlaying by mutableStateOf(false); private set
    var positionMs by mutableStateOf(0L); private set
    var durationMs by mutableStateOf(0L); private set
    var lyric by mutableStateOf(emptyList<LrcLine>()); private set
    var qualities by mutableStateOf(emptyList<String>()); private set

    val currentSong: Song? get() = curSsid?.let { id -> queue.firstOrNull { it.ssid == id } ?: songs.firstOrNull { it.ssid == id } }
    val lyricIndex: Int
        get() {
            val l = lyric
            if (l.isEmpty() || l[0].timeMs < 0) return -1
            var idx = -1
            for (i in l.indices) if (l[i].timeMs <= positionMs) idx = i else break
            return idx
        }

    private val songMap: Map<String, Song> get() = songs.associateBy { it.ssid }
    private var rankedCache: List<Song>? = null
    private var rankedInput: List<Song>? = null
    private var albumCache: List<Group>? = null
    private var albumInput: List<Song>? = null
    private var artistCache: List<Group>? = null
    private var artistInput: List<Song>? = null

    init {
        player.onEnded = { onEnded() }
        player.onError = { msg -> toast("播放失败：$msg"); isPlaying = false }
        load()
        // 播放内核没有进度回调，界面上定时读一次
        scope.launch {
            while (true) {
                delay(250)
                if (isPlaying) {
                    positionMs = player.position()
                    val d = player.duration()
                    if (d > 0) durationMs = d
                }
            }
        }
    }

    // ================= 加载 =================
    private fun netMsg(e: Throwable): String {
        val m = e.message ?: ""
        return when {
            m.contains("timeout", true) || m.contains("timed out", true) -> "连接服务器超时，已切换到离线模式"
            m.contains("Unable to resolve", true) || m.contains("UnknownHost", true) -> "无法解析服务器地址，已切换到离线模式"
            m.contains("Failed to connect", true) || m.contains("ECONNREFUSED", true) -> "无法连接服务器，已切换到离线模式"
            m.contains("Software caused connection abort", true) || m.contains("ECONNABORTED", true) ->
                "连接被中断（网络不稳定），已切换到离线模式"
            m.startsWith("HTTP ") -> "服务器返回 $m"
            else -> "加载失败：$m"
        }
    }

    fun load() = scope.launch {
        loading = true
        error = null
        var ok = false
        try {
            val s = withContext(Dispatchers.IO) { Api.songs(quick = true) }
            songs = s.map { if (it.coverUrl.isNullOrBlank()) it.copy(coverUrl = guessCoverUrl(it)) else it }
            ok = true
        } catch (e: Exception) {
            error = netMsg(e)
        }
        if (ok) {
            offline = false
            error = null
        } else {
            offline = true
            songs = LocalLib.songs(Prefs.downloads)
        }
        loading = false
        if (!offline) artistInfo = withContext(Dispatchers.IO) { Api.artists() }
    }

    // ================= 列表派生 =================
    fun ranked(): List<Song> {
        if (rankedInput !== songs) {
            rankedCache = songs.shuffled().sortedByDescending { counts[it.ssid] ?: 0 }
            rankedInput = songs
        }
        return rankedCache ?: emptyList()
    }

    fun albumGroups(): List<Group> {
        if (albumInput !== songs) {
            albumCache = songs.groupBy { it.artist to it.album }.map { (k, v) ->
                Group(
                    title = k.second.ifBlank { "未知专辑" },
                    subtitle = "${k.first} · ${v.size} 首",
                    cover = v.firstNotNullOfOrNull { it.coverUrl },
                    songs = v, artist = k.first, album = k.second,
                )
            }
            albumInput = songs
        }
        return albumCache ?: emptyList()
    }

    fun artistGroups(): List<Group> {
        if (artistInput !== songs) {
            artistCache = songs.groupBy { it.artist }.map { (a, v) ->
                Group(
                    title = a.ifBlank { "未知歌手" },
                    subtitle = "${v.map { it.album }.distinct().size} 张专辑 · ${v.size} 首",
                    cover = artistInfo[a]?.image?.takeIf { it.isNotBlank() }?.let { Api.abs(it) }
                        ?: v.firstNotNullOfOrNull { it.coverUrl },
                    songs = v, artist = a,
                )
            }
            artistInput = songs
        }
        return artistCache ?: emptyList()
    }

    fun songsByIds(ids: List<String>): List<Song> {
        val m = songMap
        return ids.mapNotNull { m[it] }
    }

    // ================= 播放 =================
    fun playSongs(list: List<Song>, index: Int) {
        if (list.isEmpty()) return
        queue = list
        curIndex = index.coerceIn(0, list.lastIndex)
        startCurrent(0)
    }

    private fun startCurrent(fromMs: Long) {
        val song = queue.getOrNull(curIndex) ?: return
        curSsid = song.ssid
        isPlaying = true
        positionMs = fromMs
        durationMs = 0
        lyric = emptyList()
        updateCover(song)
        Prefs.bump(song.ssid)
        counts = Prefs.playCounts
        scope.launch {
            val rec = downloads.firstOrNull { it.ssid == song.ssid }
            val src: Pair<String, Boolean>? = if (rec != null && rec.audio.isNotBlank()) {
                rec.audio to true
            } else {
                if (offline) {
                    toast("离线模式：这首歌还没下载")
                    isPlaying = false
                    null
                } else {
                    try {
                        withContext(Dispatchers.IO) { Api.audioUrl(song.ssid, quality) } to false
                    } catch (e: Exception) {
                        toast("获取播放地址失败：${e.message}")
                        isPlaying = false
                        null
                    }
                }
            }
            if (src == null) return@launch
            player.play(src.first, src.second, fromMs)
            loadLyric(song.ssid)
        }
    }

    private fun updateCover(song: Song) {
        val ref = coverRef(song.coverUrl)
        if (ref.isNullOrBlank()) {
            coverColor = null
            return
        }
        scope.launch(Dispatchers.IO) {
            val color = Images.get(ref, 64)?.color
            run {
                if (curSsid == song.ssid && color != null) coverColor = color
            }
        }
    }

    private suspend fun loadLyric(ssid: String) {
        val rec = downloads.firstOrNull { it.ssid == ssid }
        if (offline) {
            val t = rec?.let { LocalLib.readText(it.lyric) } ?: ""
            run { if (curSsid == ssid) lyric = lyricLines(t) }
            return
        }
        var t = runCatching { withContext(Dispatchers.IO) { Api.detail(ssid) } }
            .onSuccess { d ->
                run {
                    if (curSsid == ssid) qualities = d.audio.keys.sortedBy { Api.rank(it) }
                }
            }
            .map { it.lyrics }
            .getOrDefault("")
        if (t.isBlank()) t = runCatching { withContext(Dispatchers.IO) { Api.lyrics(ssid) } }.getOrDefault("")
        if (t.isBlank()) t = rec?.let { LocalLib.readText(it.lyric) } ?: ""
        val lines = lyricLines(t)
        run { if (curSsid == ssid) lyric = lines }
    }

    fun toggle() {
        if (isPlaying) {
            player.pause()
            isPlaying = false
        } else {
            if (curSsid == null) {
                if (queue.isEmpty()) queue = ranked()
                if (queue.isNotEmpty()) playSongs(queue, 0)
                return
            }
            player.resume()
            isPlaying = true
        }
    }

    fun seek(ms: Long) {
        positionMs = ms
        player.seek(ms)
    }

    fun next() = step(1)

    fun prev() {
        if (positionMs > 3000) {
            seek(0)
            return
        }
        step(-1)
    }

    private fun step(delta: Int) {
        val q = queue
        if (q.isEmpty()) return
        val i = when (playMode) {
            1 -> q.indices.random()
            else -> (curIndex + delta + q.size) % q.size
        }
        curIndex = i
        startCurrent(0)
    }

    private fun onEnded() {
        when (playMode) {
            2 -> startCurrent(0)
            else -> step(1)
        }
    }

    fun cycleMode() {
        playMode = (playMode + 1) % 3
        Prefs.playMode = playMode
        toast(listOf("列表循环", "随机播放", "单曲循环")[playMode])
    }

    fun chooseSpeed(v: Float) {
        speed = v
        Prefs.speed = v
        // VLC 支持变速；回退到内置解码器时不支持
        if (player.setRate(v)) toast("倍速：${"%.2f".format(v).trimEnd('0').trimEnd('.')}×")
        else toast("当前内核不支持倍速")
    }

    fun chooseQuality(q: String) {
        quality = q
        Prefs.quality = q
        Api.clearCache()
        val pos = positionMs
        if (curSsid != null) startCurrent(pos)
        toast("音质：${qualityLabel(q)}")
    }

    // ================= 歌单 =================
    fun createPlaylist(name: String, songs: List<String> = emptyList(), cover: String? = null) {
        val p = Playlist(id = System.currentTimeMillis().toString(), name = name, songs = songs, cover = cover)
        playlists = playlists + p
        Prefs.playlists = playlists
    }

    fun renamePlaylist(id: String, name: String) {
        playlists = playlists.map { if (it.id == id) it.copy(name = name) else it }
        Prefs.playlists = playlists
    }

    fun deletePlaylist(id: String) {
        playlists = playlists.filter { it.id != id }
        Prefs.playlists = playlists
        if ((page as? Page.PlaylistPage)?.id == id) page = null
    }

    fun addToPlaylist(id: String, ids: List<String>) {
        playlists = playlists.map { p ->
            if (p.id == id) p.copy(songs = (p.songs + ids).distinct()) else p
        }
        Prefs.playlists = playlists
        toast("已加入歌单")
    }

    fun removeFromPlaylist(id: String, ssid: String) {
        playlists = playlists.map { p -> if (p.id == id) p.copy(songs = p.songs - ssid) else p }
        Prefs.playlists = playlists
    }

    fun moveInPlaylist(id: String, from: Int, to: Int) {
        playlists = playlists.map { p ->
            if (p.id != id) p
            else {
                val l = p.songs.toMutableList()
                if (from in l.indices && to in l.indices) {
                    val item = l.removeAt(from)
                    l.add(to, item)
                }
                p.copy(songs = l)
            }
        }
        Prefs.playlists = playlists
    }

    fun removeQueue(i: Int) {
        val l = queue.toMutableList()
        if (i !in l.indices) return
        l.removeAt(i)
        queue = l
        if (i < curIndex) curIndex--
    }

    // ================= 下载 =================
    fun isDownloaded(ssid: String) = ssid in downloadedIds

    fun download(list: List<Song>) {
        if (offline) {
            toast("离线模式下无法下载")
            return
        }
        val todo = list.filter { !isDownloaded(it.ssid) && it.ssid !in downloading }
        if (todo.isEmpty()) {
            toast(if (list.all { isDownloaded(it.ssid) }) "已下载过，可在「已下载」里重新下载" else "已在下载队列中")
            return
        }
        downloading = downloading + todo.map { it.ssid }
        toast("开始下载 ${todo.size} 首")
        scope.launch(Dispatchers.IO) {
            todo.forEachIndexed { i, s ->
                dlStatus = DlStatus(s.title, i + 1, todo.size, 0L, -1L, 0L)
                var lastBytes = 0L
                var lastTime = System.currentTimeMillis()
                val r = runCatching {
                    LocalLib.download(s, quality) { done, total ->
                        val now = System.currentTimeMillis()
                        val dt = now - lastTime
                        if (dt < 400 && done < total) return@download
                        val bps = if (dt > 0) (done - lastBytes) * 1000 / dt else 0
                        lastBytes = done
                        lastTime = now
                        dlStatus = DlStatus(s.title, i + 1, todo.size, done, total, bps)
                    }
                }
                r.onSuccess { rec ->
                    Prefs.downloads = Prefs.downloads.filter { it.ssid != rec.ssid } + rec
                    downloads = Prefs.downloads
                    if (offline) songs = LocalLib.songs(Prefs.downloads)
                    toast("已下载：${s.title}")
                }.onFailure {
                    toast("下载失败：${s.title}")
                }
                downloading = downloading - s.ssid
            }
            dlStatus = null
        }
    }

    fun redownload(ssid: String) {
        val rec = Prefs.downloads.firstOrNull { it.ssid == ssid } ?: return
        scope.launch(Dispatchers.IO) {
            LocalLib.delete(rec)
            Prefs.downloads = Prefs.downloads.filter { it.ssid != ssid }
            downloads = Prefs.downloads
            run {
                download(listOf(Song(ssid, rec.title, rec.artist, rec.album, rec.cover.ifBlank { null })))
            }
        }
    }

    fun deleteDownload(ssid: String) {
        val rec = Prefs.downloads.firstOrNull { it.ssid == ssid } ?: return
        scope.launch(Dispatchers.IO) {
            LocalLib.delete(rec)
            Prefs.downloads = Prefs.downloads.filter { it.ssid != ssid }
            downloads = Prefs.downloads
            if (offline) songs = LocalLib.songs(Prefs.downloads)
            toast("已删除下载")
        }
    }

    // ================= 主题 / 提示 =================
    fun setTheme(c: Color?) {
        fixedTheme = c
        Prefs.themeColor = c?.let { packColor(it) }
    }

    /** 主题色存成 0xAARRGGBB，方便落盘 */
    private fun packColor(c: Color): Long {
        val r = (c.red * 255).toInt().coerceIn(0, 255)
        val g = (c.green * 255).toInt().coerceIn(0, 255)
        val b = (c.blue * 255).toInt().coerceIn(0, 255)
        return (0xFF000000L) or ((r shl 16).toLong()) or ((g shl 8).toLong()) or b.toLong()
    }

    fun toast(msg: String) {
        toastMsg = msg
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(2500)
            toastMsg = null
        }
    }
}
