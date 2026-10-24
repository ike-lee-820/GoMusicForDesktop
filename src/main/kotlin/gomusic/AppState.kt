package gomusic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
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

    /** 待加入歌单的请求 */
    data class PickRequest(val ids: List<String>, val name: String, val cover: String? = null)
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
    /** 歌曲是否还有下一页（滑到底继续加载） */
    var nmHasMore by mutableStateOf(false); private set
    /** 专辑是否还有下一页 */
    var nmAlbumsHasMore by mutableStateOf(false); private set
    /** 歌手是否还有下一页 */
    var nmArtistsHasMore by mutableStateOf(false); private set
    /** 搜索代号：切标签/改关键词会自增，旧请求的结果不再生效，避免串扰 */
    private var nmGen = 0
    var nmSongs by mutableStateOf<List<Song>>(emptyList()); private set
    var nmAlbums by mutableStateOf<List<Group>>(emptyList()); private set
    var nmArtists by mutableStateOf<List<Group>>(emptyList()); private set
    /** 累积的网易云歌曲：切界面清空 nmSongs 后，历史仍能查到 */
    private val nmKnown = java.util.concurrent.ConcurrentHashMap<String, Song>()
    /** 歌曲快照（持久化） */
    var snapshots: Map<String, Song> by mutableStateOf<Map<String, Song>>(Prefs.songSnapshots); private set

    private var nmKeyword = ""
    private var nmOffset = 0
    private var nmAlbumOffset = 0
    private var nmArtistOffset = 0
    private var nmJob: kotlinx.coroutines.Job? = null
    /** 歌曲 id -> 实际能拿到的音质档位，探测过就缓存，避免每首都重探 */
    private val nmLevelsCache = java.util.concurrent.ConcurrentHashMap<Long, List<String>>()

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

    /** 搜索网易云（防抖）。未开启或未登录时直接清空，不发请求 */
    fun searchNetease(q: String, kind: Int = 0) {
        nmJob?.cancel()
        nmJob = null
        val kw = q.trim()
        NmLog.note("searchNetease 调用：kw=$kw kind=$kind nmReady=$nmReady")
        nmKeyword = kw
        nmOffset = 0
        nmAlbumOffset = 0
        nmArtistOffset = 0
        nmHasMore = false
        nmAlbumsHasMore = false
        nmArtistsHasMore = false
        if (!nmReady || kw.isEmpty()) {
            nmSearching = false
            nmLoadingMore = false
            nmSongs = emptyList()
            nmAlbums = emptyList()
            nmArtists = emptyList()
            return
        }
        val gen = ++nmGen
        // 立刻置为搜索中：让大加载动画马上出来，不用等防抖
        nmSearching = true
        nmJob = scope.launch {
            try {
                delay(400)
                // 只搜当前标签需要的那一类：三个类型各发一次请求（还要各自补详情）
                // 是搜索慢的主因，这里按 kind 只发一路
                NmLog.note("按标签搜索 kind=$kind kw=$kw")
                var s: List<Song> = emptyList()
                var a: List<Group> = emptyList()
                var ar: List<Group> = emptyList()
                when (kind) {
                    1 -> a = withContext(Dispatchers.IO) { Netease.searchAlbums(kw, NM_PAGE_SIZE, 0) }
                        .distinctBy { if (it.nmId > 0) "nm" + it.nmId else "loc" + it.title }
                    2 -> ar = withContext(Dispatchers.IO) { Netease.searchArtists(kw, NM_PAGE_SIZE, 0) }
                        .distinctBy { if (it.nmId > 0) "nm" + it.nmId else "loc" + it.title }
                    else -> s = withContext(Dispatchers.IO) { Netease.searchSongs(kw, NM_PAGE_SIZE, 0) }
                        .distinctBy { it.ssid }
                }
                // 分类型记日志：便于确认到底是哪一路没返回
                NmLog.note("分类型结果：歌曲=" + s.size + " 专辑=" + a.size + " 歌手=" + ar.size + "（kw=$kw）")
                // 关键词或代号变了说明已经切走，丢弃这批结果
                if (gen != nmGen || kw != nmKeyword || !nmReady) return@launch
                keepNetease(s)
                val c = counts
                when (kind) {
                    1 -> { nmAlbums = a; nmSongs = emptyList(); nmArtists = emptyList() }
                    2 -> { nmArtists = ar; nmSongs = emptyList(); nmAlbums = emptyList() }
                    else -> { nmSongs = s.sortedByDescending { c[it.ssid] ?: 0 }; nmAlbums = emptyList(); nmArtists = emptyList() }
                }
                nmOffset = NM_PAGE_SIZE
                nmAlbumOffset = NM_PAGE_SIZE
                nmArtistOffset = NM_PAGE_SIZE
                when (kind) {
                    1 -> nmAlbumsHasMore = a.size >= NM_PAGE_SIZE
                    2 -> nmArtistsHasMore = ar.size >= NM_PAGE_SIZE
                    else -> nmHasMore = s.size >= NM_PAGE_SIZE
                }
                NmLog.note("搜索完成：歌曲 " + s.size + " / 专辑 " + a.size + " / 歌手 " + ar.size)
                if (s.isEmpty() && a.isEmpty() && ar.isEmpty()) {
                    NmLog.note("网易云三条搜索都为空，请检查登录态与关键词")
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                NmLog.err("searchNetease($kw)", e)
                if (gen == nmGen) {
                    nmSongs = emptyList(); nmAlbums = emptyList(); nmArtists = emptyList()
                    toast("网易云搜索失败：" + (e.message?.take(40) ?: ""))
                }
            } finally {
                // 只有当前这一代搜索才能收起加载态，避免旧任务把新搜索的加载态清掉
                if (gen == nmGen) nmSearching = false
            }
        }
    }

    /**
     * 列表滑到底时加载下一页。
     * [kind]：0 歌曲 1 专辑 2 歌手，各自维护偏移量。
     */
    fun loadMoreNetease(kind: Int = 0) {
        val kw = nmKeyword
        if (kw.isBlank() || !nmReady || nmLoadingMore) return
        val off = when (kind) { 1 -> nmAlbumOffset; 2 -> nmArtistOffset; else -> nmOffset }
        val hasMore = when (kind) { 1 -> nmAlbumsHasMore; 2 -> nmArtistsHasMore; else -> nmHasMore }
        if (!hasMore) return
        nmLoadingMore = true
        val gen = nmGen
        NmLog.note("翻页开始 kind=$kind offset=$off kw=$kw")
        // 用独立协程：不占用搜索的 nmJob，避免翻页被新搜索取消
        scope.launch {
            try {
                when (kind) {
                    1 -> {
                        val add = withContext(Dispatchers.IO) { Netease.searchAlbums(kw, NM_PAGE_SIZE, off) }
                            .distinctBy { if (it.nmId > 0) "nm" + it.nmId else "loc" + it.title }
                        if (gen != nmGen || kw != nmKeyword || !nmReady) return@launch
                        val seen = nmAlbums.map { if (it.nmId > 0) "nm" + it.nmId else "loc" + it.title }.toHashSet()
                        val real = add.filter { (if (it.nmId > 0) "nm" + it.nmId else "loc" + it.title) !in seen }
                        nmAlbums = nmAlbums + real
                        nmAlbumOffset = off + NM_PAGE_SIZE
                        nmAlbumsHasMore = real.isNotEmpty()
                        NmLog.note("专辑翻页完成 offset=$off 新增 " + real.size)
                    }
                    2 -> {
                        val add = withContext(Dispatchers.IO) { Netease.searchArtists(kw, NM_PAGE_SIZE, off) }
                            .distinctBy { if (it.nmId > 0) "nm" + it.nmId else "loc" + it.title }
                        if (gen != nmGen || kw != nmKeyword || !nmReady) return@launch
                        val seen = nmArtists.map { if (it.nmId > 0) "nm" + it.nmId else "loc" + it.title }.toHashSet()
                        val real = add.filter { (if (it.nmId > 0) "nm" + it.nmId else "loc" + it.title) !in seen }
                        nmArtists = nmArtists + real
                        nmArtistOffset = off + NM_PAGE_SIZE
                        nmArtistsHasMore = real.isNotEmpty()
                        NmLog.note("歌手翻页完成 offset=$off 新增 " + real.size)
                    }
                    else -> {
                        val add = withContext(Dispatchers.IO) { Netease.searchSongs(kw, NM_PAGE_SIZE, off) }
                            .distinctBy { it.ssid }
                        if (gen != nmGen || kw != nmKeyword || !nmReady) return@launch
                        keepNetease(add)
                        val seen = nmSongs.map { it.ssid }.toHashSet()
                        val real = add.filter { it.ssid !in seen }
                        nmSongs = nmSongs + real
                        nmOffset = off + NM_PAGE_SIZE
                        nmHasMore = real.isNotEmpty()
                        NmLog.note("歌曲翻页完成 offset=$off 新增 " + real.size)
                    }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                NmLog.err("loadMoreNetease($kw,$off)", e)
                when (kind) { 1 -> nmAlbumsHasMore = false; 2 -> nmArtistsHasMore = false; else -> nmHasMore = false }
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
            // 不传 s.coverUrl：那是歌曲/专辑封面，用了会把歌手头像显示成专辑图。
            // 歌手真实头像进页面后用 /artist/detail 取。
            openNmGroup(Group(s.artist, "", null, emptyList(),
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
        nmPageAlbums = emptyList()
        nmPageLoading = true
        nmPageAlbumsLoading = g.nmKind == "artist"
        nmPageTab = 0
        nmPageSongOffset = 0
        nmPageAlbumOffset = 0
        nmPageSongsHasMore = false
        nmPageAlbumsHasMore = false
        // 歌手页不沿用歌曲封面：先留空，拿到歌手详情后填真实头像
        nmPageCover = if (g.nmKind == "artist") null else g.cover
        // 专辑页副标题显示歌手名（列表没带时稍后补）；歌手页留空
        nmPageSubtitle = if (g.nmKind == "album") g.artist else ""
        val kind = g.nmKind
        val id = g.nmId
        val ck = "$kind/$id"
        // 命中缓存：直接用，不再发请求（返回上层时尤其有用）
        val hit = nmPageCache[ck]
        if (hit != null) {
            nmPageSongs = hit.first
            nmPageAlbums = hit.second
            nmPageLoading = false
            nmPageAlbumsLoading = false
            nmPageSongOffset = if (kind == "artist") hit.first.size.coerceAtLeast(NM_PAGE_SIZE) else 0
            nmPageAlbumOffset = hit.second.size.coerceAtLeast(NM_PAGE_SIZE)
            nmPageSongsHasMore = kind == "artist" && hit.first.size >= NM_PAGE_SIZE
            nmPageAlbumsHasMore = hit.second.size >= NM_PAGE_SIZE
            return
        }
        scope.launch {
            val l = withContext(Dispatchers.IO) {
                runCatching { Netease.songsOf(kind, id, NM_PAGE_SIZE, 0) }.getOrDefault(emptyList())
            }
            keepNetease(l)
            nmPageSongs = l
            nmPageSongOffset = NM_PAGE_SIZE
            nmPageSongsHasMore = kind == "artist" && l.size >= NM_PAGE_SIZE
            nmPageLoading = false
            if (kind != "artist") {
                nmPageCache[ck] = l to emptyList()
                // 专辑页副标题显示歌手名；列表没带歌手时，单独取一次详情
                if (nmPageSubtitle.isBlank()) {
                    launch {
                        val a = withContext(Dispatchers.IO) {
                            runCatching { Netease.albumArtist(id) }.getOrNull()
                        }
                        if (!a.isNullOrBlank()) nmPageSubtitle = a
                    }
                }
            } else {
                // 歌手：并行取头像与专辑列表
                launch {
                    val d = withContext(Dispatchers.IO) {
                        runCatching { Netease.artistDetail(id) }.getOrNull()
                    }
                    if (d?.cover != null) nmPageCover = d.cover
                }
                launch {
                    val al = withContext(Dispatchers.IO) {
                        runCatching { Netease.artistAlbums(id, NM_PAGE_SIZE, 0) }.getOrDefault(emptyList())
                    }
                    nmPageAlbums = al
                    nmPageAlbumOffset = NM_PAGE_SIZE
                    nmPageAlbumsHasMore = al.size >= NM_PAGE_SIZE
                    nmPageAlbumsLoading = false
                    nmPageCache[ck] = nmPageSongs to al
                }
            }
        }
    }

    /**
     * 确保当前显示的详情页内容与 [kind]/[id] 对应。
     * 从歌手页点进专辑、或返回上层时按此校正，
     * 否则会出现「请求成功却不显示，要再进一次才有」。
     */
    fun ensureNmPage(kind: String, id: Long, title: String, cover: String?, artist: String) {
        val g = nmPage
        if (g != null && g.nmId == id && g.nmKind == kind) return
        NmLog.note("校正详情页：$kind/$id（原来是 ${g?.nmKind}/${g?.nmId}）")
        openNmGroup(
            Group(title, "", cover, emptyList(),
                artist = artist, source = SRC_NETEASE, nmId = id, nmKind = kind)
        )
    }

    /** 歌手页：切换分栏（0 歌曲 1 专辑） */
    fun chooseNmPageTab(t: Int) { nmPageTab = t }

    /** 歌手 / 专辑页：歌曲或专辑滑到底时加载下一页 */
    fun loadMoreNmPage(kind: Int) {
        val g = nmPage ?: return
        if (nmPageLoadingMore) return
        val songMode = kind == 0
        if (songMode && (!nmPageSongsHasMore || g.nmKind != "artist")) return
        if (!songMode && !nmPageAlbumsHasMore) return
        val off = if (songMode) nmPageSongOffset else nmPageAlbumOffset
        val kindStr = g.nmKind
        val id = g.nmId
        nmPageLoadingMore = true
        NmLog.note("详情页翻页 kind=$kind offset=$off id=$id")
        scope.launch {
            try {
                if (songMode) {
                    val add = withContext(Dispatchers.IO) {
                        runCatching { Netease.songsOf(kindStr, id, NM_PAGE_SIZE, off) }.getOrDefault(emptyList())
                    }
                    keepNetease(add)
                    val seen = nmPageSongs.map { it.ssid }.toHashSet()
                    val real = add.filter { it.ssid !in seen }
                    nmPageSongs = nmPageSongs + real
                    nmPageSongOffset = off + NM_PAGE_SIZE
                    nmPageSongsHasMore = real.isNotEmpty()
                } else {
                    val add = withContext(Dispatchers.IO) {
                        runCatching { Netease.artistAlbums(id, NM_PAGE_SIZE, off) }.getOrDefault(emptyList())
                    }
                    val seen = nmPageAlbums.map { it.nmId }.toHashSet()
                    val real = add.filter { it.nmId !in seen }
                    nmPageAlbums = nmPageAlbums + real
                    nmPageAlbumOffset = off + NM_PAGE_SIZE
                    nmPageAlbumsHasMore = real.isNotEmpty()
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                NmLog.err("loadMoreNmPage($kind,$off)", e)
                if (songMode) nmPageSongsHasMore = false else nmPageAlbumsHasMore = false
            } finally {
                nmPageLoadingMore = false
            }
        }
    }

    fun openNmGroupPublic(g: Group) = openNmGroup(g)

    fun clearNmPage() {
        nmPage = null
        nmPageSongs = emptyList()
        nmPageAlbums = emptyList()
        nmPageLoading = false
        nmPageAlbumsLoading = false
        nmPageLoadingMore = false
        nmPageSongsHasMore = false
        nmPageAlbumsHasMore = false
        nmPageTab = 0
        nmPageSongOffset = 0
        nmPageAlbumOffset = 0
        nmPageCover = null
        nmPageSubtitle = ""
    }

    /** 网易云专辑 / 歌手页 */
    var nmPage by mutableStateOf<Group?>(null); private set
    /** 歌手页的专辑列表（歌手页有「歌曲」「专辑」两栏） */
    var nmPageAlbums by mutableStateOf<List<Group>>(emptyList()); private set
    var nmPageAlbumsLoading by mutableStateOf(false); private set
    /** 歌手页当前分栏：0 歌曲 1 专辑 */
    var nmPageTab by mutableIntStateOf(0); private set
    var nmPageSongsHasMore by mutableStateOf(false); private set
    var nmPageAlbumsHasMore by mutableStateOf(false); private set
    var nmPageLoadingMore by mutableStateOf(false); private set
    /** 页头封面（歌手页用歌手自己的头像，不用专辑封面） */
    var nmPageCover by mutableStateOf<String?>(null); private set
    /** 页头副标题：专辑显示歌手名，歌手页留空 */
    var nmPageSubtitle by mutableStateOf(""); private set
    /** 详情页内容缓存：kind/id -> (歌曲, 专辑)，返回上层时直接复用 */
    private val nmPageCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<Song>, List<Group>>>()
    private var nmPageSongOffset = 0
    private var nmPageAlbumOffset = 0
    var nmPageSongs by mutableStateOf<List<Song>>(emptyList()); private set
    var nmPageLoading by mutableStateOf(false); private set

    /** 找到网易云歌曲（含累积与快照） */
    fun nmSongOf(ssid: String): Song? = nmKnown[ssid] ?: snapshots[ssid]

    // ================= 首页 =================
    var homeRecommend by mutableStateOf<List<Song>>(emptyList()); private set
    var homeNewSongs by mutableStateOf<List<Song>>(emptyList()); private set
    var homeHotWords by mutableStateOf<List<String>>(emptyList()); private set
    var homeTopArtists by mutableStateOf<List<Group>>(emptyList()); private set
    var homeNewAlbums by mutableStateOf<List<Group>>(emptyList()); private set
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
                // 热门歌手与最新专辑：跟着首页一起加载
                val ta = runCatching { Netease.topArtists(20, 0) }.getOrDefault(emptyList())
                val na = runCatching { Netease.newestAlbums(20) }.getOrDefault(emptyList())
                homeTopArtists = ta
                homeNewAlbums = na
                NmLog.note("首页加载完成：推荐${r.size} 新歌${n.size} 热搜${h.size} 歌手${ta.size} 新碟${na.size}")
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

    /**
     * ssid -> Song。
     * 歌单里可能混着本站与网易云的歌，只查本地 songs 的话
     * 网易云歌曲会查不到 —— 表现就是「加了歌单却看不见」。
     */
    fun songsByIds(ids: List<String>): List<Song> {
        val m = songMap
        val nm = nmSongs.associateBy { it.ssid }
        val known = nmKnown.toMap()
        val sn = snapshots
        val dl = downloads.associateBy { it.ssid }
        return ids.mapNotNull { id ->
            m[id] ?: nm[id] ?: known[id] ?: sn[id] ?: dl[id]?.let {
                Song(it.ssid, it.title, it.artist, it.album, it.cover.ifBlank { null })
            }
        }
    }

    /** 把一批歌曲记进快照，保证加进歌单后随时能查到 */
    fun keepForPlaylist(l: List<Song>) {
        if (l.isEmpty()) return
        rememberSongs(l)
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
        // 网易云歌曲：音质档位与本站分开，菜单里列网易云自己的档位
        val nid0 = neteaseIdOf(song.ssid)
        qualities = if (nid0 != null) NM_LEVELS else emptyList()
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
                // 后台逐档位探测真实存在的音质，探测完只显示拿得到的那些
                val hit = nmLevelsCache[nid]
                if (hit != null) {
                    if (hit.isNotEmpty() && curSsid == song.ssid) qualities = hit
                } else {
                    launch {
                        val lv = withContext(Dispatchers.IO) {
                            runCatching { Netease.availableLevels(nid) }.getOrDefault(emptyList())
                        }
                        nmLevelsCache[nid] = lv
                        if (lv.isNotEmpty() && curSsid == song.ssid) qualities = lv
                    }
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

    /** 待加入歌单的请求：UI 检测到非空就弹歌单选择 */
    var pendingPick by mutableStateOf<PickRequest?>(null); private set

    /** 请求把一批歌曲加入某个歌单 */
    fun pickRequest(ids: List<String>, name: String, cover: String? = null) {
        if (ids.isEmpty()) return
        keepForPlaylist(songsByIds(ids))
        pendingPick = PickRequest(ids.distinct(), name, cover)
    }

    /** 取消加入歌单 */
    fun dismissPick() { pendingPick = null }

    // ================= 检查更新 ================
    val appVersion: String get() = Updater.current()
    var updateChecking by mutableStateOf(false); private set
    var updateInfo by mutableStateOf<Updater.Info?>(null); private set
    /** 显示更新弹窗 */
    var showUpdate by mutableStateOf(false)
    var updateProgress by mutableStateOf<Pair<Long, Long>?>(null); private set
    var updateFile by mutableStateOf<java.io.File?>(null); private set
    private var autoChecked = false

    /** 检查更新。[silent] = true 时查不到更新不提示 */
    fun checkUpdate(silent: Boolean = false) {
        if (updateChecking) return
        updateChecking = true
        scope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { Updater.check() }.getOrNull() }
            updateInfo = info
            updateChecking = false
            when {
                info == null -> if (!silent) toast("检查更新失败，请检查网络")
                info.hasUpdate -> showUpdate = true
                !silent -> toast("当前已是最新版本 $appVersion")
            }
        }
    }

    /** 启动时静默检查一次，有新版就弹窗 */
    fun autoCheckUpdate() {
        if (autoChecked) return
        autoChecked = true
        checkUpdate(silent = true)
    }

    fun closeUpdate() {
        showUpdate = false
        updateProgress = null
    }

    /** 应用内更新：下载安装包后交给系统打开安装 */
    fun updateInApp() {
        val info = updateInfo ?: return
        if (updateProgress != null) return
        updateProgress = 0L to 0L
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                Updater.downloadInstaller(info) { done, total ->
                    scope.launch { updateProgress = done to total }
                }
            }
            if (f == null) {
                updateProgress = null
                toast("下载失败，可改用应用外更新")
                return@launch
            }
            updateProgress = null
            updateFile = f
            toast("已下载到：" + f.absolutePath)
            Updater.openFile(f)
        }
    }

    /** 应用外更新：交给浏览器打开下载页 */
    fun updateByBrowser(): String? {
        val info = updateInfo ?: return null
        return Updater.downloadUrl(info)
    }

    /** 用系统浏览器打开链接 */
    fun openUrl(u: String) {
        runCatching {
            java.awt.Desktop.getDesktop().browse(java.net.URI(u))
        }.onFailure { toast("打不开链接：" + (it.message ?: "")) }
    }

    /** 简繁转换模式：0 简体 / 1 香港繁体 / 2 仅搜索统一 / 3 不转换 */
    var zhConvert by mutableIntStateOf(Prefs.zhConvert)
        private set

    /** 改简繁设置：立即生效。名字不能叫 setZhConvert（会撞 setter 的 JVM 签名） */
    fun chooseZhConvert(m: Int) {
        zhConvert = m
        Prefs.zhConvert = m
    }

    // ================= 歌单 =================
    fun createPlaylist(name: String, songs: List<String> = emptyList(), cover: String? = null) {
        keepForPlaylist(songsByIds(songs))
        val p = Playlist(id = System.currentTimeMillis().toString(), name = name, songs = songs.distinct(), cover = cover)
        playlists = playlists + p
        Prefs.playlists = playlists
    }

    /** 名字已被占用时追加 (2)(3)…，保证不覆盖已有歌单 */
    private fun uniqueName(base: String): String {
        val used = playlists.map { it.name }.toHashSet()
        if (base !in used) return base
        var i = 2
        while ("$base($i)" in used) i++
        return "$base($i)"
    }

    /** 网易云专辑 / 歌手页：整页歌曲新建一个歌单 */
    fun createFromNmPage(name: String, cover: String?) {
        val list = nmPageSongs
        if (list.isEmpty()) { toast("还没有歌曲"); return }
        createPlaylist(name, list.map { it.ssid }, cover)
        toast("已新建歌单「$name」，共 ${list.size} 首")
    }

    /** 导出单个歌单：返回还原字符 */
    fun exportPlaylist(id: String): String? {
        val p = playlists.firstOrNull { it.id == id } ?: return null
        val songs = songsByIds(p.songs)
        if (songs.isEmpty()) {
            toast("歌单是空的，没有可导出的内容")
            return null
        }
        return PlaylistShare.encode(p, songs)
    }

    /** 一次导出多个歌单：每个歌单一行还原字符 */
    fun exportPlaylists(ids: List<String>): String {
        val lines = ids.mapNotNull { exportPlaylist(it) }
        if (lines.isEmpty()) toast("选中的歌单都没有可导出的歌曲")
        return lines.joinToString("\n")
    }

    /** 导入：支持一次粘贴多行，逐行还原成多个歌单 */
    fun importPlaylist(text: String): Boolean {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        if (lines.size > 1) {
            var ok = 0
            lines.forEach { if (importOne(it)) ok++ }
            if (ok > 0) toast("已导入 $ok 个歌单")
            else toast("还原字符无法识别，请检查是否完整")
            return ok > 0
        }
        return importOne(text)
    }

    /** 还原单个歌单（一行还原字符）；重名自动改名，不覆盖 */
    private fun importOne(text: String): Boolean {
        val snap = PlaylistShare.decode(text) ?: return false
        if (snap.songs.isEmpty()) { toast("这个歌单里没有歌曲"); return false }
        rememberSongs(snap.songs.map {
            Song(it.ssid, it.title, it.artist, it.album, it.cover, source = it.source)
        })
        val ids = snap.songs.map { it.ssid }.distinct()
        val p = Playlist(
            id = System.currentTimeMillis().toString() + "_" + ids.size,
            name = uniqueName(snap.name.ifBlank { "导入的歌单" }),
            songs = ids,
        )
        playlists = playlists + p
        Prefs.playlists = playlists
        toast("已导入：${p.name}（${ids.size} 首）")
        return true
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
        keepForPlaylist(songsByIds(ids))
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

    /** 把一首歌追加到播放列表末尾（只加这一首，不动队列里已有的歌） */
    fun addToQueue(s: Song) {
        keepNetease(listOf(s))
        queue = queue + s
        toast("已加入播放列表：${s.title}")
    }

    /** 清空播放列表（保留当前正在播放的那一首） */
    fun clearQueue() {
        val l = queue
        if (l.isEmpty()) return
        val cur = curIndex.coerceIn(0, l.lastIndex)
        val keep = l[cur]
        queue = listOf(keep)
        curIndex = 0
        toast("已清空播放列表")
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
