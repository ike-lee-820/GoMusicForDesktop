package gomusic

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Slider
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun App(state: AppState) {
    GoTheme(state.primary) {
        Box(Modifier.fillMaxSize().background(BgColor)) {
            Row(Modifier.fillMaxSize()) {
                LeftPane(state, Modifier.weight(1.15f).fillMaxHeight().widthIn(min = 420.dp))
                Box(Modifier.fillMaxHeight().width(1.dp).background(SurfaceHiColor))
                RightPane(state, Modifier.weight(1f).fillMaxHeight().widthIn(min = 380.dp))
            }
            ToastHost(state.toastMsg)
            if (state.showSettings) SettingsDialog(state)
        }
    }
}

// =====================================================================
// 左栏：搜索 + 列表 + 二级页面
// =====================================================================

@Composable
fun LeftPane(state: AppState, modifier: Modifier) {
    var pick by remember { mutableStateOf<Pair<List<String>, String>?>(null) }
    val titles = listOf("歌曲", "专辑", "歌手", "歌单")
    val hints = listOf("搜索歌名或歌手", "搜索专辑名或歌手", "搜索歌手", "搜索歌单")
    Column(modifier.background(BgColor)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.page != null) {
                    IconBtn(Icons.Outlined.ArrowBack, "返回") { state.page = null }
                    Spacer(Modifier.width(4.dp))
                }
                Txt(
                    when (state.page) {
                        is Page.Album -> (state.page as Page.Album).name.ifBlank { "未知专辑" }
                        is Page.Artist -> (state.page as Page.Artist).name.ifBlank { "未知歌手" }
                        is Page.PlaylistPage -> state.playlists.firstOrNull { it.id == (state.page as Page.PlaylistPage).id }?.name ?: "歌单"
                        else -> titles[state.tab]
                    },
                    20, Modifier.weight(1f), FontWeight.Bold,
                )
                IconBtn(Icons.Outlined.Settings, "设置") { state.showSettings = true }
            }
            Spacer(Modifier.height(8.dp))
            if (state.page == null) SearchField(state.query, { state.query = it }, hints[state.tab])
            if (state.offline) OfflineBar(state)
            state.dlStatus?.let { DownloadBar(it) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val p = state.page) {
                null -> TabList(state) { ids, title -> pick = ids to title }
                is Page.Album -> AlbumPage(state, p) { ids, title -> pick = ids to title }
                is Page.Artist -> ArtistPage(state, p) { ids, title -> pick = ids to title }
                is Page.PlaylistPage -> PlaylistPage(state, p) { ids, title -> pick = ids to title }
            }
        }
    }
    pick?.let { (ids, title) ->
        PickDialog(state, title, ids) { pick = null }
    }
}

@Composable
fun OfflineBar(state: AppState) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
            .background(SurfaceHiColor).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(Icons.Outlined.CloudOff, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Txt("离线模式 · 已下载 ${state.downloads.size} 首", 13, Modifier.weight(1f), color = MutedColor)
        GoButton("重试", primary = true) { state.load() }
    }
}

@Composable
fun DownloadBar(d: DlStatus) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(Icons.Outlined.Download, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Txt("正在下载 ${d.index}/${d.total} · ${d.title}", 13, Modifier.weight(1f), color = MutedColor, maxLines = 1)
            Txt(speedText(d.bps).ifBlank { "准备中" }, 13, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(6.dp))
        if (d.percent >= 0) {
            Bar(d.percent / 100f, Modifier.fillMaxWidth(), MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Txt("${sizeText(d.done)} / ${sizeText(d.size)} · ${d.percent}%", 12, color = MutedColor)
        } else {
            Bar(null, Modifier.fillMaxWidth(), MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Txt("${sizeText(d.done)} 已下载", 12, color = MutedColor)
        }
    }
}

@Composable
fun TabList(state: AppState, onPick: (List<String>, String) -> Unit) {
    val q = state.query.trim()
    when {
        state.loading && state.songs.isEmpty() ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Spinner(color = MaterialTheme.colorScheme.primary)
            }
        state.error != null && state.songs.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.Icon(Icons.Outlined.CloudOff, null, tint = MutedColor, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(12.dp))
                Txt(state.error ?: "", 14, color = MutedColor)
                Spacer(Modifier.height(12.dp))
                GoButton("重试", primary = true) { state.load() }
            }
        }
        else -> when (state.tab) {
            0 -> {
                val list = state.ranked().filter { q.isEmpty() || it.title.contains(q, true) || it.artist.contains(q, true) }
                SongList(state, list, onPick)
            }
            1 -> {
                val groups = state.albumGroups().filter { q.isEmpty() || it.title.contains(q, true) || it.artist.contains(q, true) }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                    items(groups, key = { it.artist to it.album }) { g ->
                        GroupRow(g.title, g.subtitle, g.cover, onClick = { state.page = Page.Album(g.artist, g.album) }) {
                            onPick(g.songs.map { it.ssid }, g.title)
                        }
                    }
                }
            }
            2 -> {
                val groups = state.artistGroups().filter { q.isEmpty() || it.title.contains(q, true) }
                LazyVerticalGrid(
                    GridCells.Adaptive(150.dp),
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(groups, key = { it.title }) { g ->
                        GroupCard(g.title, g.subtitle, g.cover, onClick = { state.page = Page.Artist(g.artist) }) {
                            onPick(g.songs.map { it.ssid }, g.title)
                        }
                    }
                }
            }
            else -> PlaylistsTab(state, onPick)
        }
    }
}

@Composable
fun SongList(state: AppState, list: List<Song>, onPick: (List<String>, String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
        items(list, key = { it.ssid }) { s ->
            val (hover, hoverM) = hoverState()
            val cur = s.ssid == state.curSsid
            val bg = when {
                cur -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                hover -> SurfaceHiColor
                else -> Color.Transparent
            }
            Row(
                Modifier.fillMaxWidth().height(64.dp).background(bg)
                    .then(hoverM)
                    .clickable { state.playSongs(list, list.indexOf(s)) }
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Cover(s.coverUrl, Modifier.fillMaxSize(), 96, 8.dp)
                    if (cur) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                            PlayingBars(state.isPlaying)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Txt(s.title, 15, weight = FontWeight.Medium, color = if (cur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    Txt("${s.artist} · ${s.album}", 12, color = MutedColor, maxLines = 1)
                }
                IconBtn(
                    if (state.isDownloaded(s.ssid)) Icons.Outlined.CheckCircle else Icons.Outlined.Download,
                    if (state.isDownloaded(s.ssid)) "已下载" else "下载歌曲",
                    tint = if (state.isDownloaded(s.ssid)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                ) { state.download(listOf(s)) }
                IconBtn(Icons.Outlined.PlaylistAdd, "加入歌单") { onPick(listOf(s.ssid), s.title) }
            }
        }
    }
}

@Composable
fun PlayingBars(playing: Boolean) {
    val c = MaterialTheme.colorScheme.primary
    Row(Modifier.height(16.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        repeat(3) { i ->
            val h = if (playing) (0.4f + ((System.currentTimeMillis() / (180 + i * 90)) % 3) * 0.25f) else 0.5f
            Box(Modifier.width(3.dp).fillMaxHeight(h.coerceIn(0.25f, 1f)).background(c, RoundedCornerShape(1.dp)))
        }
    }
}

@Composable
fun GroupRow(
    title: String,
    subtitle: String,
    cover: String?,
    onClick: () -> Unit,
    onAdd: (() -> Unit)? = null,
) {
    val (hover, hoverM) = hoverState()
    Row(
        Modifier.fillMaxWidth().height(64.dp)
            .background(if (hover) SurfaceHiColor else Color.Transparent)
            .then(hoverM)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(cover, Modifier.size(48.dp), 96, 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, 15, weight = FontWeight.Medium, maxLines = 1)
            Txt(subtitle, 12, color = MutedColor, maxLines = 1)
        }
        if (onAdd != null) IconBtn(Icons.Outlined.PlaylistAdd, "加入歌单", onClick = onAdd)
    }
}

@Composable
fun GroupCard(
    title: String,
    subtitle: String,
    cover: String?,
    onClick: () -> Unit,
    onAdd: (() -> Unit)? = null,
) {
    val (hover, hoverM) = hoverState()
    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(if (hover) SurfaceHiColor else SurfaceColor).clickable(onClick = onClick).padding(8.dp).then(hoverM)) {
        Box(Modifier.fillMaxWidth()) {
            Cover(cover, Modifier.fillMaxWidth().aspectRatio(1f), 256, 16.dp)
            if (onAdd != null) {
                Box(Modifier.padding(6.dp).align(Alignment.BottomEnd).size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(SurfaceHiColor).clickable { onAdd() }, contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(Icons.Outlined.Add, "加入歌单", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Txt(title, 14, weight = FontWeight.Medium, maxLines = 1)
        Txt(subtitle, 12, color = MutedColor, maxLines = 1)
    }
}

// =====================================================================
// 二级页面
// =====================================================================

@Composable
fun AlbumPage(state: AppState, p: Page.Album, onPick: (List<String>, String) -> Unit) {
    val songs = state.songs.filter { it.artist == p.artist && it.album == p.name }
    val cover = songs.firstNotNullOfOrNull { it.coverUrl }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Cover(cover, Modifier.size(160.dp), 512, 24.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.height(160.dp), verticalArrangement = Arrangement.Center) {
                    Txt(p.name.ifBlank { "未知专辑" }, 20, weight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Txt("${p.artist} · ${songs.size} 首", 13, color = MutedColor,
                        modifier = Modifier.clickable { state.page = Page.Artist(p.artist) })
                    Spacer(Modifier.height(12.dp))
                    Row {
                        GoButton("播放全部", primary = true, enabled = songs.isNotEmpty()) { state.playSongs(songs, 0) }
                        Spacer(Modifier.width(8.dp))
                        GoButton("下载", enabled = songs.isNotEmpty()) { state.download(songs) }
                        Spacer(Modifier.width(8.dp))
                        GoButton("加入歌单", enabled = songs.isNotEmpty()) { onPick(songs.map { it.ssid }, p.name) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        items(songs, key = { it.ssid }) { s ->
            val (hover, hoverM) = hoverState()
            val cur = s.ssid == state.curSsid
            Row(
                Modifier.fillMaxWidth().height(64.dp)
                    .background(if (cur) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else if (hover) SurfaceHiColor else Color.Transparent)
                    .then(hoverM)
                    .clickable { state.playSongs(songs, songs.indexOf(s)) }
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Cover(s.coverUrl, Modifier.fillMaxSize(), 96, 8.dp)
                    if (cur) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                            PlayingBars(state.isPlaying)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Txt(s.title, 15, weight = FontWeight.Medium, color = if (cur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    Txt("${s.artist} · ${s.album}", 12, color = MutedColor, maxLines = 1)
                }
                IconBtn(
                    if (state.isDownloaded(s.ssid)) Icons.Outlined.CheckCircle else Icons.Outlined.Download,
                    if (state.isDownloaded(s.ssid)) "已下载" else "下载歌曲",
                    tint = if (state.isDownloaded(s.ssid)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                ) { state.download(listOf(s)) }
                IconBtn(Icons.Outlined.PlaylistAdd, "加入歌单") { onPick(listOf(s.ssid), s.title) }
            }
        }
    }
}

@Composable
fun ArtistPage(state: AppState, p: Page.Artist, onPick: (List<String>, String) -> Unit) {
    val songs = state.songs.filter { it.artist == p.name }
    val albums = state.albumGroups().filter { it.artist == p.name }
    val info = state.artistInfo[p.name]
    var tab by remember(p.name) { mutableStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(info?.image?.let { Api.abs(it) } ?: songs.firstNotNullOfOrNull { it.coverUrl }, Modifier.size(120.dp), 320, 60.dp, circle = true)
                Spacer(Modifier.width(16.dp))
                Column {
                    Txt(p.name.ifBlank { "未知歌手" }, 20, weight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Txt("${albums.size} 张专辑 · ${songs.size} 首", 13, color = MutedColor)
                    if (!info?.bio.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Txt(info!!.bio!!, 13, color = MutedColor, maxLines = if (expanded) Int.MAX_VALUE else 3,
                            modifier = Modifier.clickable { expanded = !expanded })
                    }
                    Spacer(Modifier.height(12.dp))
                    Row {
                        GoButton("播放全部", primary = true, enabled = songs.isNotEmpty()) { state.playSongs(songs, 0) }
                        Spacer(Modifier.width(8.dp))
                        GoButton("下载", enabled = songs.isNotEmpty()) { state.download(songs) }
                        Spacer(Modifier.width(8.dp))
                        GoButton("加入歌单", enabled = songs.isNotEmpty()) { onPick(songs.map { it.ssid }, p.name) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(horizontal = 16.dp)) {
                GoButton("专辑", primary = tab == 0) { tab = 0 }
                Spacer(Modifier.width(8.dp))
                GoButton("歌曲", primary = tab == 1) { tab = 1 }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (tab == 0) {
            items(albums, key = { it.artist to it.album }) { g ->
                GroupRow(g.title, g.subtitle, g.cover, onClick = { state.page = Page.Album(g.artist, g.album) }) { onPick(g.songs.map { it.ssid }, g.title) }
            }
        } else {
            items(songs, key = { it.ssid }) { s ->
                val (hover, hoverM) = hoverState()
                val cur = s.ssid == state.curSsid
                Row(
                    Modifier.fillMaxWidth().height(64.dp)
                        .background(if (cur) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else if (hover) SurfaceHiColor else Color.Transparent)
                        .then(hoverM)
                        .clickable { state.playSongs(songs, songs.indexOf(s)) }
                        .padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(s.coverUrl, Modifier.size(48.dp), 96, 8.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(s.title, 15, weight = FontWeight.Medium, color = if (cur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1)
                        Txt("${s.artist} · ${s.album}", 12, color = MutedColor, maxLines = 1)
                    }
                    IconBtn(
                        if (state.isDownloaded(s.ssid)) Icons.Outlined.CheckCircle else Icons.Outlined.Download,
                        if (state.isDownloaded(s.ssid)) "已下载" else "下载歌曲",
                        tint = if (state.isDownloaded(s.ssid)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    ) { state.download(listOf(s)) }
                    IconBtn(Icons.Outlined.PlaylistAdd, "加入歌单") { onPick(listOf(s.ssid), s.title) }
                }
            }
        }
    }
}

@Composable
fun PlaylistPage(state: AppState, p: Page.PlaylistPage, onPick: (List<String>, String) -> Unit) {
    val pl = state.playlists.firstOrNull { it.id == p.id } ?: return
    val songs = state.songsByIds(pl.songs)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Txt(pl.name, 20, weight = FontWeight.Bold)
            Txt("${songs.size} 首", 13, color = MutedColor)
            Spacer(Modifier.height(12.dp))
            Row {
                GoButton("播放全部", primary = true, enabled = songs.isNotEmpty()) { state.playSongs(songs, 0) }
                Spacer(Modifier.width(8.dp))
                GoButton("下载", enabled = songs.isNotEmpty()) { state.download(songs) }
                Spacer(Modifier.width(8.dp))
                GoButton("删除歌单") { state.deletePlaylist(pl.id); state.page = null }
            }
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(songs, key = { _, s -> s.ssid }) { i, s ->
                    val (hover, hoverM) = hoverState()
                    Row(
                        Modifier.fillMaxWidth().height(64.dp).background(if (hover) SurfaceHiColor else Color.Transparent)
                            .then(hoverM)
                            .clickable { state.playSongs(songs, i) }
                            .padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(s.coverUrl, Modifier.size(48.dp), 96, 8.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(s.title, 15, weight = FontWeight.Medium, color = if (s.ssid == state.curSsid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1)
                            Txt("${s.artist} · ${s.album}", 12, color = MutedColor, maxLines = 1)
                        }
                        IconBtn(Icons.Outlined.Delete, "从歌单移除") { state.removeFromPlaylist(pl.id, s.ssid) }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistsTab(state: AppState, onPick: (List<String>, String) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            GoButton("新建歌单", primary = true) { creating = true }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(state.playlists, key = { it.id }) { pl ->
                val songs = state.songsByIds(pl.songs)
                GroupRow(
                    pl.name, "${songs.size} 首", pl.cover ?: songs.firstNotNullOfOrNull { it.coverUrl },
                    onClick = { state.page = Page.PlaylistPage(pl.id) },
                    onAdd = null,
                )
            }
        }
    }
    if (creating) NameDialog("新建歌单", "", { creating = false }) { state.createPlaylist(it); creating = false }
}

// =====================================================================
// 右栏：正在播放
// =====================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RightPane(state: AppState, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val song = state.currentSong
    var tab by remember { mutableStateOf(0) }
    Column(modifier.background(SurfaceColor).padding(20.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Cover(song?.coverUrl, Modifier.fillMaxWidth(0.72f).aspectRatio(1f), 512, 20.dp)
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(song?.title ?: "未在播放", 20, weight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.basicMarquee())
            Spacer(Modifier.height(4.dp))
            Txt(
                listOf(song?.artist, song?.album).filter { !it.isNullOrBlank() }.joinToString(" · ").ifBlank { "GoMusic 桌面版" },
                13, color = MutedColor, maxLines = 1,
            )
        }
        Spacer(Modifier.height(14.dp))
        SeekBar(state.positionMs, state.durationMs) { state.seek(it) }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            IconBtn(
                when (state.playMode) { 1 -> Icons.Outlined.Shuffle; 2 -> Icons.Outlined.RepeatOne; else -> Icons.Outlined.Repeat },
                "播放模式",
            ) { state.cycleMode() }
            Spacer(Modifier.width(8.dp))
            IconBtn(Icons.Outlined.SkipPrevious, "上一首", size = 44.dp) { state.prev() }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(56.dp).clip(androidx.compose.foundation.shape.CircleShape).background(cs.primary)
                    .clickable { state.toggle() },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    if (state.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    "播放/暂停", tint = cs.onPrimary, modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            IconBtn(Icons.Outlined.SkipNext, "下一首", size = 44.dp) { state.next() }
            Spacer(Modifier.width(8.dp))
            QualityMenu(state)
        }
        Spacer(Modifier.height(14.dp))
        Row {
            GoButton("歌词", primary = tab == 0) { tab = 0 }
            Spacer(Modifier.width(8.dp))
            GoButton("播放列表 ${state.queue.size}", primary = tab == 1) { tab = 1 }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BgColor)) {
            if (tab == 0) LyricView(state) else QueueView(state)
        }
    }
}

@Composable
fun LyricView(state: AppState) {
    val lines = state.lyric
    if (lines.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Txt("暂无歌词", 13, color = MutedColor) }
        return
    }
    val idx = state.lyricIndex
    val listState = rememberLazyListState()
    LaunchedEffect(idx) {
        if (idx >= 0) runCatching { listState.animateScrollToItem((idx - 1).coerceAtLeast(0)) }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), state = listState) {
        itemsIndexed(lines) { i, l ->
            val cur = i == idx
            Txt(
                l.text, if (cur) 17 else 15,
                Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { if (l.timeMs >= 0) state.seek(l.timeMs) },
                weight = if (cur) FontWeight.Bold else FontWeight.Normal,
                color = if (cur) MaterialTheme.colorScheme.primary else MutedColor,
            )
        }
    }
}

@Composable
fun QueueView(state: AppState) {
    if (state.queue.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Txt("播放列表为空", 13, color = MutedColor) }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(state.queue, key = { _, s -> s.ssid }) { i, s ->
            val cur = i == state.curIndex
            val (hover, hoverM) = hoverState()
            Row(
                Modifier.fillMaxWidth().height(56.dp)
                    .background(if (cur) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else if (hover) SurfaceHiColor else Color.Transparent)
                    .then(hoverM)
                    .clickable { state.playSongs(state.queue, i) }
                    .padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(s.coverUrl, Modifier.size(40.dp), 96, 8.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Txt(s.title, 14, weight = FontWeight.Medium, color = if (cur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    Txt(s.artist, 12, color = MutedColor, maxLines = 1)
                }
                IconBtn(Icons.Outlined.Delete, "从列表移除") { state.removeQueue(i) }
            }
        }
    }
}

@Composable
fun QualityMenu(state: AppState) {
    var open by remember { mutableStateOf(false) }
    Box {
        GoButton(qualityLabel(state.quality)) { open = true }
        androidx.compose.material3.DropdownMenu(open, { open = false }, Modifier.background(SurfaceColor)) {
            val list = if (state.qualities.isEmpty()) listOf("lossless", "high", "normal") else state.qualities.sortedBy { Api.rank(it) }
            list.forEach { q ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Txt(qualityLabel(q), 14) },
                    onClick = { open = false; state.chooseQuality(q) },
                )
            }
        }
    }
}

// =====================================================================
// 弹层
// =====================================================================

@Composable
fun SettingsDialog(state: AppState) {
    var hex by remember { mutableStateOf("") }
    val presets = listOf(
        0xFFD0BCFFL, 0xFFFF8A80L, 0xFFFFB74DL, 0xFFFFF176L,
        0xFF81C784L, 0xFF4DD0E1L, 0xFF64B5F6L, 0xFFF48FB1L,
    ).map { Color(it.toULong()) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable { state.showSettings = false },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 560.dp).heightIn(max = 620.dp).clip(RoundedCornerShape(20.dp))
                .background(SurfaceColor).padding(20.dp).verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("设置", 18, Modifier.weight(1f), FontWeight.Bold)
                IconBtn(Icons.Outlined.Delete, "关闭") { state.showSettings = false }
            }
            Spacer(Modifier.height(12.dp))
            Txt("连接", 15, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Txt(if (state.offline) "离线模式：连接服务器失败" else "已连接服务器", 13, color = MutedColor)
            Spacer(Modifier.height(8.dp))
            GoButton("重新连接") { state.load() }
            Spacer(Modifier.height(16.dp))
            // ---------- 悬浮歌词 ----------
            Txt("悬浮歌词", 15, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfaceHiColor)
                    .clickable { state.chooseFloatOn(!state.floatOn) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Txt("显示悬浮歌词", 14, weight = FontWeight.Medium)
                    Txt(if (state.floatOn) "已开启，窗口可拖动" else "关闭", 12, color = MutedColor)
                }
                Switch(
                    checked = state.floatOn,
                    onCheckedChange = { state.chooseFloatOn(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BgColor,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MutedColor,
                        uncheckedTrackColor = SurfaceColor,
                    ),
                )
            }
            if (state.floatOn) {
                Spacer(Modifier.height(12.dp))
                Txt("文字颜色", 13, color = MutedColor)
                Spacer(Modifier.height(6.dp))
                FloatColorRow(state.floatColor) { state.updateFloat(color = it) }
                Spacer(Modifier.height(10.dp))
                Txt("描边颜色", 13, color = MutedColor)
                Spacer(Modifier.height(6.dp))
                FloatColorRow(state.floatStrokeColor) { state.updateFloat(strokeColor = it) }
                Spacer(Modifier.height(10.dp))
                Txt("字号 ${state.floatSize.toInt()} sp", 13, color = MutedColor)
                Slider(
                    value = state.floatSize,
                    onValueChange = { state.updateFloat(size = it) },
                    valueRange = 14f..40f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Txt("描边 ${state.floatStroke.toInt()}", 13, color = MutedColor)
                Slider(
                    value = state.floatStroke,
                    onValueChange = { state.updateFloat(stroke = it) },
                    valueRange = 0f..10f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Txt("字体", 13, color = MutedColor)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FONT_NAMES.forEachIndexed { i, n ->
                        GoButton(n, primary = state.floatFont == i) { state.updateFloat(font = i) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Txt("播放内核", 15, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Txt("当前：${state.engineName}（FFmpeg 解码 + OpenAL 输出；不可用时回退内置 MP3 解码）", 13, color = MutedColor)
            Spacer(Modifier.height(16.dp))
            Txt("主题色", 15, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row {
                GoButton("跟随封面", primary = state.fixedTheme == null) { state.setTheme(null) }
                Spacer(Modifier.width(8.dp))
                presets.forEach { c ->
                    Box(
                        Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c)
                            .clickable { state.setTheme(c) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.fixedTheme == c) {
                            androidx.compose.material3.Icon(Icons.Outlined.CheckCircle, "当前主题色", tint = Color(0xFF1A1423), modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceHiColor).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (hex.isEmpty()) Txt("自定义颜色，如 #64B5F6", 13, color = MutedColor)
                    androidx.compose.foundation.text.BasicTextField(
                        value = hex, onValueChange = { hex = it }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                GoButton("应用") {
                    val t = if (hex.startsWith("#")) hex else "#$hex"
                    val c = runCatching { java.awt.Color.decode(t) }.getOrNull()
                    if (c != null) state.setTheme(Color(c.red, c.green, c.blue)) else state.toast("颜色格式不正确")
                }
            }
            Spacer(Modifier.height(16.dp))
            Txt("下载", 15, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Txt("保存位置：${LocalLib.root.absolutePath}（音乐 / 封面 / 歌词 / 歌曲信息）", 12, color = MutedColor)
            Spacer(Modifier.height(8.dp))
            if (state.downloads.isEmpty()) {
                Txt("还没有下载歌曲", 13, color = MutedColor)
            } else {
                state.downloads.sortedByDescending { it.time }.forEach { rec ->
                    Row(Modifier.fillMaxWidth().height(48.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Txt(rec.title, 14, weight = FontWeight.Medium, maxLines = 1)
                            Txt("${rec.artist} · ${qualityLabel(rec.quality)}", 12, color = MutedColor, maxLines = 1)
                        }
                        IconBtn(Icons.Outlined.Refresh, "重新下载") { state.redownload(rec.ssid) }
                        IconBtn(Icons.Outlined.Delete, "删除下载") { state.deleteDownload(rec.ssid) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Txt("GoMusic 桌面版 1.0.0 · 配置与索引：${Prefs.dir.absolutePath}", 12, color = MutedColor)
        }
    }
}

@Composable
fun PickDialog(state: AppState, title: String, ids: List<String>, onClose: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).heightIn(max = 480.dp).clip(RoundedCornerShape(20.dp))
                .background(SurfaceColor).padding(20.dp),
        ) {
            Txt("把「$title」加入歌单", 16, weight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                state.playlists.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp))
                            .clickable {
                                if (ids.isNotEmpty()) state.addToPlaylist(p.id, ids)
                                onClose()
                            }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { Txt(p.name, 15, Modifier.weight(1f), maxLines = 1) }
                }
            }
            Spacer(Modifier.height(12.dp))
            GoButton("新建歌单", primary = true) { creating = true }
        }
    }
    if (creating) {
        NameDialog("新建歌单", "", { creating = false }) {
            state.createPlaylist(it, ids)
            creating = false
            onClose()
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onClose: () -> Unit, onOk: (String) -> Unit) {
    var v by remember(initial) { mutableStateOf(initial) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = 360.dp).clip(RoundedCornerShape(20.dp)).background(SurfaceColor).padding(20.dp)) {
            Txt(title, 16, weight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceHiColor).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                androidx.compose.foundation.text.BasicTextField(
                    value = v, onValueChange = { v = it }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                GoButton("取消") { onClose() }
                Spacer(Modifier.width(8.dp))
                GoButton("确定", primary = true) { if (v.isNotBlank()) onOk(v.trim()); onClose() }
            }
        }
    }
}
