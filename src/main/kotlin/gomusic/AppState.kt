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

/** 网易云搜索每次请求的条数（翻页步长） */
const val NM_PAGE_SIZE = 20

/** 标签：0 歌曲 1 专辑 2 首页 3 歌手 4 歌单 */
const val TAB_SONGS = 0
const val TAB_ALBUMS = 1
const val TAB_HOME = 2
const val TAB_ARTISTS = 3
const val TAB_PLAYLISTS = 4

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
    /** 曲库来源：[SRC_LOCAL] 或 [SRC_NETEASE] */
    val source: String = SRC_LOCAL,
    /** 网易云专辑 / 歌手 id */
    val nmId: Long = 0,
    /** album / artist */
    val nmKind: String = "",
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

/** Compose Color -> 0xAARRGGBB */
fun Color.argbLong(): Long {
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    return (((a shl 24) or (r shl 16) or (g shl 8) or b).toLong()) and 0xFFFFFFFFL
}

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
    var showHistory by mutableStateOf(false)
    var history by mutableStateOf(Prefs.history); private set

    fun pushHistory(ssid: String) {
        val l = (listOf(ssid) + history.filter { it != ssid }).take(MAX_HISTORY)
        history = l
        Prefs.history = l
    }

    fun removeHistory(ssid: String) {
        val l = history.filter { it != ssid }
        history = l
        Prefs.history = l
    }

    fun clearHistory() {
        history = emptyList()
        Prefs.history = emptyList()
    }
    val historySongs: List<Song> get() {
        val m = songs.associateBy { it.ssid }
        val nm = nmSongs.associateBy { it.ssid }
        val known = nmKnown.toMap()
        val sn = snapshots
        val dl = downloads.associateBy { it.ssid }
        return history.mapNotNull { id ->
            m[id] ?: nm[id] ?: known[id] ?: sn[id] ?: dl[id]?.let {
                Song(it.ssid, it.title, it.artist, it.album, it.cover.ifBlank { null })
            }
        }
    }
    var counts by mutableStateOf(Prefs.playCounts); private set

    // ================= 网易云曲库 =================
    /** 是否开启「接入网易云曲库」 */
    var nmOn by mutableStateOf(Prefs.nmEnabled)
    /** 网易云账号昵称，空 = 未登录 */
    var nmUser by mutableStateOf(Prefs.nmAccount.ifBlank { null })
    /** 是否已登录并可用 */
    val nmReady: Boolean get() = nmOn && !nmUser.isNullOrBlank() && Netease.cookie.isNotBlank()
    var nmSearching by mutableStateOf(false); private set
    var nmLoadingMore by mutableStateOf(false); private set
    var nmHasMore by mutableStateOf(false); private set
    var nmSongs by mutableStateOf<List<Song>>(emptyList()); private set
    var nmAlbums by mutableStateOf<List<Group>>(emptyList()); private set
    var nmArtists by mutableStateOf<List<Group>>(emptyList()); private set
    /** 累积的网易云歌曲：切界面清空 nmSongs 后，历史仍能查到 */
    private val nmKnown = java.util.concurrent.ConcurrentHashMap<String, Song>()
    /** 歌曲快照（持久化） */
    var snapshots: Map<String, Song> by mutableStateOf<Map<String, Song>>(Prefs.songSnapshots); private set

    private var nmKeyword = ""
    private var nmOffset = 0
    private var nmJob: kotlinx.coroutines.Job? = null
    val nmKeywordForUi: String get() = nmKeyword
    val nmOffsetForUi: Int get() = nmOffset

    fun rememberSongs(list: List<Song>) {
        if (list.isEmpty()) return
        val cur = LinkedHashMap<String, Song>(snapshots)
        var changed = false
        for (s in list) {
            if (s.title.isBlank()) continue
            val old = cur[s.ssid]
            if (old == null || (old.coverUrl.isNullOrBlank() && !s.coverUrl.isNullOrBlank())) {
                cur.remove(s.ssid)
                cur[s.ssid] = s
                changed = true
            }
        }
        if (changed) {
            while (cur.size > MAX_SNAPSHOTS) {
                val first = cur.keys.firstOrNull() ?: break
                cur.remove(first)
            }
            snapshots = cur
            Prefs.songSnapshots = cur
        }
    }

    private fun keepNetease(l: List<Song>) {
        val nm = l.filter { it.source == SRC_NETEASE }
        if (nm.isEmpty()) return
        nm.forEach { nmKnown[it.ssid] = it }
        rememberSongs(nm)
    }

    /** 两套曲库不合并：本地不足 8 条时全列本地再列网易云，
     *  否则每 2 条本地插 1 条网易云 */
    fun <T> mix(local: List<T>, nm: List<T>): List<T> {
        if (nm.isEmpty()) return local
        if (local.isEmpty()) return nm
        if (local.size < 8) return local + nm
        val out = ArrayList<T>(local.size + nm.size)
        var i = 0
        var j = 0
        while (i < local.size || j < nm.size) {
            repeat(2) { if (i < local.size) out.add(local[i++]) }
            if (j < nm.size) out.add(nm[j++])
        }
        return out
    }

    /** 搜索网易云（防抖） */
    fun searchNetease(q: String) {
        nmJob?.cancel()
        val kw = q.trim()
        nmKeyword = kw
        nmOffset = 0
        nmHasMore = false
        if (!nmReady || kw.isEmpty()) {
            nmSearching = false
            nmLoadingMore = false
            nmSongs = emptyList()
            nmAlbums = emptyList()
            nmArtists = emptyList()
            return
        }
        NmLog.note("searchNetease 调用：kw=$kw nmReady=$nmReady")
        nmSearching = true
        nmJob = scope.launch {
            try {
                delay(400)
                val dS = kotlinx.coroutines.async(Dispatchers.IO) { Netease.searchSongs(kw, NM_PAGE_SIZE, 0) }
                val dA = kotlinx.coroutines.async(Dispatchers.IO) { Netease.searchAlbums(kw, NM_PAGE_SIZE, 0) }
                val dAr = kotlinx.coroutines.async(Dispatchers.IO) { Netease.searchArtists(kw, NM_PAGE_SIZE, 0) }
                val s = dS.await(); val a = dA.await(); val ar = dAr.await()
                if (!nmReady) return@launch
                keepNetease(s)
                val c = counts
                nmSongs = s.sortedByDescending { c[it.ssid] ?: 0 }
                nmAlbums = a
                nmArtists = ar
                nmOffset = NM_PAGE_SIZE
                nmHasMore = s.size >= NM_PAGE_SIZE
                NmLog.note("搜索完成：歌曲 ${s.size} / 专辑 ${a.size} / 歌手 ${ar.size}")
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                NmLog.err("searchNetease($kw)", e)
                nmSongs = emptyList(); nmAlbums = emptyList(); nmArtists = emptyList()
                toast("网易云搜索失败：${e.message?.take(40) ?: ""}")
            } finally {
                nmSearching = false
            }
        }
    }

    /** 列表滑到底时加载下一页 */
    fun loadMoreNetease() {
        val kw = nmKeyword
        if (kw.isBlank() || !nmReady || nmLoadingMore || !nmHasMore) return
        val off = nmOffset
        if (nmJob != null && nmJob?.isActive == true) return
        nmLoadingMore = true
        scope.launch {
            try {
                val s = withContext(Dispatchers.IO) { Netease.searchSongs(kw, NM_PAGE_SIZE, off) }
                if (kw != nmKeyword || !nmReady) return@launch
                keepNetease(s)
                val seen = nmSongs.map { it.ssid }.toHashSet()
                nmSongs = nmSongs + s.filter { it.ssid !in seen }
                nmOffset = off + NM_PAGE_SIZE
                nmHasMore = s.size >= NM_PAGE_SIZE
                NmLog.note("翻页完成 offset=$off 新增 ${s.size} 首")
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                NmLog.err("loadMoreNetease($kw,$off)", e)
                nmHasMore = false
            } finally {
                nmLoadingMore = false
            }
        }
    }

    fun cancelNeteaseSearch() {
        nmJob?.cancel()
        nmJob = null
        nmSearching = false
    }

    /** 开启 / 关闭网易云曲库 */
    fun chooseNmOn(on: Boolean) {
        nmOn = on
        Prefs.nmEnabled = on
        if (on) {
            if (Netease.cookie.isBlank()) Netease.cookie = Prefs.nmCookie
            if (Netease.cookie.isNotBlank()) refreshNmAccount()
        } else {
            nmSongs = emptyList(); nmAlbums = emptyList(); nmArtists = emptyList()
        }
    }

    /** 用当前 cookie 刷新账号昵称 */
    fun refreshNmAccount() = scope.launch {
        val name = withContext(Dispatchers.IO) { runCatching { Netease.accountName() }.getOrNull() }
        nmUser = name
        if (name.isNullOrBlank()) {
            Prefs.nmAccount = ""
        } else {
            Prefs.nmAccount = name
        }
    }

    /** 登录成功：保存 cookie 与昵称 */
    fun nmLogin(cookie: String, name: String? = null) {
        if (cookie.isBlank()) {
            toast("登录失败：没有拿到登录凭证，请重新扫码")
            return
        }
        Prefs.nmCookie = cookie
        Netease.cookie = cookie
        nmUser = null
        refreshNmAccount()
    }

    fun nmLogout() {
        Prefs.nmCookie = ""
        Prefs.nmAccount = ""
        Netease.cookie = ""
        nmUser = null
        nmSongs = emptyList(); nmAlbums = emptyList(); nmArtists = emptyList()
    }

    /** 网易云歌曲：点封面进专辑页（走网易云，不查本地库） */
    fun openNmSongAlbum(s: Song) {
        if (s.source != SRC_NETEASE) {
            page = Page.Album(s.artist, s.album)
            return
        }
        if (s.nmAlbumId > 0) {
            openNmGroup(Group(s.album, s.artist, s.coverUrl, emptyList(),
                artist = s.artist, album = s.album,
                source = SRC_NETEASE, nmId = s.nmAlbumId, nmKind = "album"))
            return
        }
        scope.launch {
            val g = withContext(Dispatchers.IO) {
                Netease.searchAlbums("${s.artist} ${s.album}", 5).firstOrNull()
            }
            if (g != null) openNmGroup(g) else toast("没有找到这张专辑")
        }
    }

    /** 网易云歌曲：点歌手进歌手页（走网易云，不查本地库） */
    fun openNmSongArtist(s: Song) {
        if (s.source != SRC_NETEASE) {
            page = Page.Artist(s.artist)
            return
        }
        if (s.nmArtistId > 0) {
            openNmGroup(Group(s.artist, "网易云歌手", s.coverUrl, emptyList(),
                artist = s.artist, source = SRC_NETEASE, nmId = s.nmArtistId, nmKind = "artist"))
            return
        }
        scope.launch {
            val g = withContext(Dispatchers.IO) { Netease.searchArtists(s.artist, 5).firstOrNull() }
            if (g != null) openNmGroup(g) else toast("没有找到这个歌手")
        }
    }

    /** 打开网易云专辑 / 歌手的歌曲列表 */
    private fun openNmGroup(g: Group) {
        nmPage = g
        nmPageSongs = emptyList()
        nmPageLoading = true
        scope.launch(Dispatchers.IO) {
            val l = runCatching { Netease.songsOf(g.nmKind, g.nmId) }.getOrDefault(emptyList())
            keepNetease(l)
            nmPageSongs = l
            nmPageLoading = false
        }
    }

    fun openNmGroupPublic(g: Group) = openNmGroup(g)

    fun clearNmPage() {
        nmPage = null
        nmPageSongs = emptyList()
        nmPageLoading = false
    }

    /** 网易云专辑 / 歌手页 */
    var nmPage by mutableStateOf<Group?>(null); private set
    var nmPageSongs by mutableStateOf<List<Song>>(emptyList()); private set
    var nmPageLoading by mutableStateOf(false); private set

    /** 找到网易云歌曲（含累积与快照） */
    fun nmSongOf(ssid: String): Song? = nmKnown[ssid] ?: snapshots[ssid]

    // ================= 首页 =================
    var homeRecommend by mutableStateOf<List<Song>>(emptyList()); private set
    var homeNewSongs by mutableStateOf<List<Song>>(emptyList()); private set
    var homeHotWords by mutableStateOf<List<String>>(emptyList()); private set
    var homeLoading by mutableStateOf(false); private set
    var newSongType by mutableStateOf(0); private set

    private var homeJob: kotlinx.coroutines.Job? = null

    /** 加载首页：每日推荐 + 新歌速递 + 热搜，三路并行 */
    fun loadHome() {
        homeJob?.cancel()
        if (!nmReady) {
            homeRecommend = emptyList()
            homeNewSongs = emptyList()
            homeHotWords = emptyList()
            homeLoading = false
            return
        }
        homeLoading = true
        homeJob = scope.launch {
            try {
                val dR = kotlinx.coroutines.async(Dispatchers.IO) { Netease.recommendSongs() }
                val dN = kotlinx.coroutines.async(Dispatchers.IO) { Netease.topSongs(newSongType) }
                val dH = kotlinx.coroutines.async(Dispatchers.IO) { Netease.hotSearch() }
                val r = dR.await(); val n = dN.await(); val h = dH.await()
                keepNetease(r + n)
                homeRecommend = r
                homeNewSongs = n
                homeHotWords = h
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                NmLog.err("loadHome", e)
            } finally {
                homeLoading = false
            }
        }
    }

    /** 切换新歌速递地区 */
    fun chooseNewSongType(t: Int) {
        if (newSongType == t) return
        newSongType = t
        loadHome()
    }

    /** 点热搜词：填入搜索框并回到歌曲页 */
    fun searchHotWord(w: String) {
        query = w
        chooseTab(TAB_SONGS)
    }

    /** 切换标签（取消网易云搜索，避免切界面时结果回来导致重组问题） */
    fun chooseTab(t: Int) {
        cancelNeteaseSearch()
        page = null
        nmPage = null
        nmPageSongs = emptyList()
        tab = t
    }

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

    val currentSong: Song? get() = curSsid?.let { id ->
        queue.firstOrNull { it.ssid == id }
            ?: songs.firstOrNull { it.ssid == id }
            ?: nmKnown[id]
            ?: snapshots[id]
    }
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
        NmLog.init()
        Netease.cookie = Prefs.nmCookie
        if (nmOn && Netease.cookie.isNotBlank()) refreshNmAccount()
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
        pushHistory(song.ssid)
        counts = Prefs.playCounts
        scope.launch {
            val rec = downloads.firstOrNull { it.ssid == song.ssid }
            val nid = neteaseIdOf(song.ssid)
            val src: Pair<String, Boolean>? = if (rec != null && rec.audio.isNotBlank()) {
                rec.audio to true
            } else if (nid != null && Netease.cookie.isNotBlank()) {
                // 网易云歌曲：取不到就先用 detail 补全信息
                val d = withContext(Dispatchers.IO) { runCatching { Netease.detailOf(nid) }.getOrNull() }
                if (d != null && curSsid == song.ssid) {
                    nmKnown[song.ssid] = d
                    rememberSongs(listOf(d))
                    updateCover(d)
                }
                val u = withContext(Dispatchers.IO) { Netease.audioUrl(nid, quality) }
                if (u.isNullOrBlank()) {
                    toast("网易云取不到播放地址")
                    isPlaying = false
                    null
                } else {
                    // 网易云 CDN 给的是 http://，部分环境播不了，升成 https
                    val safe = if (u.startsWith("http://")) "https://" + u.removePrefix("http://") else u
                    NmLog.note("解析到播放地址：$u -> $safe（网易云）")
                    safe to false
                }
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
        // 网易云歌曲：歌词走网易云接口，优先级高于本站
        val nid = neteaseIdOf(ssid)
        if (nid != null) {
            val t = withContext(Dispatchers.IO) { runCatching { Netease.lyric(nid) }.getOrDefault("") }
            val final = if (t.isBlank()) {
                downloads.firstOrNull { it.ssid == ssid }?.let { LocalLib.readText(it.lyric) } ?: ""
            } else t
            if (curSsid == ssid) lyric = lyricLines(final)
            return
        }
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
