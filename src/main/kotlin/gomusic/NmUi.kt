package gomusic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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

/**
 * 首页：每日推荐 / 新歌速递 / 历史记录 / 热搜歌曲。
 * 排版与歌曲页顶部的历史栏一致（横向卡片行）。
 */
@Composable
fun HomeTab(state: AppState) {
    LaunchedEffect(state.nmReady) { state.loadHome() }
    val rec = state.homeRecommend
    val news = state.homeNewSongs
    val hots = state.homeHotWords
    val topArtists = state.homeTopArtists
    val newAlbums = state.homeNewAlbums
    val his = state.historySongs
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (!state.nmReady) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Txt("首页需要接入网易云曲库", 14, weight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Txt(
                        "在「设置」里开启「接入网易云曲库」并登录后，这里会显示每日推荐、新歌速递与热搜",
                        12, color = MutedColor,
                    )
                    Spacer(Modifier.height(10.dp))
                    GoButton("去设置", primary = true) { state.showSettings = true }
                }
            }
        }
        if (state.homeLoading) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Txt("正在加载首页", 12, color = MutedColor)
                }
            }
        }
        if (rec.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    HomeHeader("每日推荐", "播放全部") { state.playSongs(rec, 0) }
                    HomeSongRow(rec.take(20), state)
                }
            }
        }
        if (news.isNotEmpty()) {
            item { NewSongSection(state, news) }
        }
        if (his.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    HomeHeader("历史记录", "全部") { state.showHistory = true }
                    HomeSongRow(his.take(20), state)
                }
            }
        }
        if (hots.isNotEmpty()) {
            item { HotSection(state, hots) }
        }
        // 热门歌手：横向卡片
        if (topArtists.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    HomeHeader("热门歌手", null) {}
                    LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(topArtists, key = { "ta${it.nmId}" }) { g ->
                            GroupCard(g.title, "", g.cover, onClick = { state.openNmGroupPublic(g) })
                        }
                    }
                }
            }
        }
        // 最新专辑（新碟上架）：横向卡片
        if (newAlbums.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    HomeHeader("最新专辑", null) {}
                    LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(newAlbums, key = { "na${it.nmId}" }) { g ->
                            GroupCard(g.title, g.subtitle, g.cover, onClick = { state.openNmGroupPublic(g) })
                        }
                    }
                }
            }
        }
    }
}

/** 首页区块标题：左边标题，右边可选的操作文字 */
@Composable
private fun HomeHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(title, 15, Modifier.weight(1f), FontWeight.SemiBold)
        if (action != null) {
            Txt(action, 12, Modifier.clickable(onClick = onAction), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
        }
    }
}

/** 横向歌曲卡片行，与历史栏排版一致 */
@Composable
private fun HomeSongRow(list: List<Song>, state: AppState) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(list, key = { it.ssid }) { s ->
            Column(Modifier.width(84.dp).clickable { state.playSongs(list, list.indexOf(s)) }) {
                Cover(s.coverUrl, Modifier.fillMaxWidth().aspectRatio(1f), 128, 12.dp)
                Spacer(Modifier.height(6.dp))
                Txt(titleWithSource(s.title, s.source), 12, maxLines = 1)
                Txt(s.artist, 11, color = MutedColor, maxLines = 1)
            }
        }
    }
    Spacer(Modifier.height(14.dp))
}

/** 新歌速递：可切换地区（全部 / 华语 / 欧美 / 日本 / 韩国） */
@Composable
private fun NewSongSection(state: AppState, list: List<Song>) {
    val types = listOf("全部" to 0, "华语" to 7, "欧美" to 96, "日本" to 8, "韩国" to 16)
    Column(Modifier.fillMaxWidth()) {
        HomeHeader("新歌速递")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            types.forEach { (n, t) ->
                val sel = state.newSongType == t
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (sel) MaterialTheme.colorScheme.primary else SurfaceHiColor)
                        .clickable { state.chooseNewSongType(t) }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Txt(n, 12, color = if (sel) Color(0xFF1A1A1A) else MutedColor)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        HomeSongRow(list.take(20), state)
    }
}

/** 热搜歌曲：点一下填入搜索框并回到歌曲页 */
@Composable
private fun HotSection(state: AppState, words: List<String>) {
    Column(Modifier.fillMaxWidth()) {
        HomeHeader("热搜歌曲")
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            words.take(20).forEach { w ->
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(SurfaceHiColor)
                        .clickable { state.searchHotWord(w) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) { Txt(w, 12) }
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

/** 列表滑到底时触发一次（用于翻页）。放到列表外常驻，避免被回收 */
@Composable
private fun ReachEnd(hasMore: Boolean, lastIndex: Int, total: Int, onReachEnd: () -> Unit) {
    if (!hasMore) return
    LaunchedEffect(hasMore, lastIndex) {
        if (lastIndex >= total - 3 && total > 0) onReachEnd()
    }
}

/** 网易云专辑 / 歌手页。歌手有「歌曲」「专辑」两栏，各自滑到底加载下一页 */
@Composable
fun NmSongsPage(state: AppState) {
    val g = state.nmPage ?: return
    val isArtist = g.nmKind == "artist"
    val cover = state.nmPageCover ?: g.cover
    val listState = rememberLazyListState()
    // 显示时确保数据与当前页对应：从歌手页点进专辑 / 返回上层都要校正，
    // 否则会出现「请求成功却不显示，要再进一次才有」
    LaunchedEffect(g.nmKind, g.nmId) {
        state.ensureNmPage(g.nmKind, g.nmId, g.title, g.cover, g.artist)
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(56.dp)) {
                CoverWithSource(cover, Modifier.fillMaxSize(), 8.dp, source = g.source)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(g.title, 18, weight = FontWeight.Bold, maxLines = 1)
                // 专辑显示歌手名；歌手页副标题留空
                if (!isArtist && state.nmPageSubtitle.isNotBlank()) {
                    Txt(state.nmPageSubtitle, 12, color = MutedColor, maxLines = 1)
                }
            }
            // 歌单操作：整页新建歌单，或选已有歌单加入
            if (state.nmPageSongs.isNotEmpty()) {
                GoButton("新建歌单并添加") { state.createFromNmPage(g.title, cover) }
                Spacer(Modifier.width(8.dp))
                GoButton("添加至歌单") { state.pickRequest(state.nmPageSongs.map { it.ssid }, g.title, cover) }
            }
            IconBtn(Icons.Outlined.Close, "关闭") { state.clearNmPage() }
        }
        if (isArtist) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                GoButton("歌曲", primary = state.nmPageTab == 0) { state.chooseNmPageTab(0) }
                Spacer(Modifier.width(8.dp))
                GoButton("专辑", primary = state.nmPageTab == 1) { state.chooseNmPageTab(1) }
            }
        }
        if (state.nmPageLoading && state.nmPageTab == 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (isArtist && state.nmPageTab == 1) {
            // ---------- 歌手：专辑栏 ----------
            if (state.nmPageAlbumsLoading && state.nmPageAlbums.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                val albums = state.nmPageAlbums
                if (albums.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Txt("没有拿到专辑", 14, color = MutedColor)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 12.dp)) {
                        itemsIndexed(albums, key = { i, a -> "nm${a.nmId}#$i" }) { _, a ->
                            GroupRow(a.title, a.subtitle, a.cover, onClick = { state.openNmGroupPublic(a) })
                        }
                        if (state.nmPageAlbumsHasMore) {
                            item { LoadingMoreRow("正在加载更多专辑") }
                        }
                    }
                    // 专辑栏翻页：按可见项判断
                    val li = listState.layoutInfo
                    ReachEnd(
                        hasMore = state.nmPageAlbumsHasMore && !state.nmPageLoadingMore,
                        lastIndex = li.visibleItemsInfo.lastOrNull()?.index ?: -1,
                        total = li.totalItemsCount,
                    ) { state.loadMoreNmPage(1) }
                }
            }
        } else {
            // ---------- 歌曲栏 ----------
            val list = state.nmPageSongs
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Txt("没有拿到歌曲", 14, color = MutedColor)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 12.dp)) {
                    itemsIndexed(list, key = { i, s -> "${s.ssid}#$i" }) { i, s ->
                        NmSongRow(state, s, i, list)
                    }
                    if (state.nmPageSongsHasMore) {
                        item { LoadingMoreRow("正在加载更多歌曲") }
                    }
                }
                val li = listState.layoutInfo
                ReachEnd(
                    hasMore = state.nmPageSongsHasMore && !state.nmPageLoadingMore,
                    lastIndex = li.visibleItemsInfo.lastOrNull()?.index ?: -1,
                    total = li.totalItemsCount,
                ) { state.loadMoreNmPage(0) }
            }
        }
    }
}

/** 列表底部「正在加载更多」占位 */
@Composable
private fun LoadingMoreRow(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(8.dp))
        Txt(text, 12, color = MutedColor)
    }
}

@Composable
private fun NmSongRow(state: AppState, s: Song, i: Int, list: List<Song>) {
    val (hover, hoverM) = hoverState()
    val cur = s.ssid == state.curSsid
    val bg = when {
        cur -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        hover -> SurfaceHiColor
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(bg).then(hoverM)
            .clickable { state.playSongs(list, i) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Cover(s.coverUrl, Modifier.fillMaxSize(), 96, 8.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(titleWithSource(s.title, s.source), 15, weight = FontWeight.Medium, maxLines = 1)
            Txt("${s.artist} · ${s.album}", 12, color = MutedColor, maxLines = 1)
        }
    }
}

/** 在 IO 线程跑一个阻塞的网络调用 */
private suspend fun <T> withIo(block: suspend () -> T): T =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

/** 网易云登录：二维码为主，验证码登录备用 */
@Composable
fun NmLoginDialog(state: AppState) {
    var qr by remember { mutableStateOf<String?>(null) }
    var key by remember { mutableStateOf<String?>(null) }
    var tip by remember { mutableStateOf("正在获取二维码") }
    var phone by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(0) } // 0 二维码 1 验证码

    LaunchedEffect(Unit) {
        val k = withIo { Netease.qrKey() }
        if (k.isNullOrBlank()) {
            tip = "获取二维码失败，请检查网络"
            return@LaunchedEffect
        }
        key = k
        qr = withIo { Netease.qrImage(k) }
        tip = if (qr == null) "二维码生成失败" else "请用网易云 App 扫码"
    }

    // 轮询扫码结果
    LaunchedEffect(key) {
        val k = key ?: return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(1500)
            val st = withIo { Netease.qrCheck(k) }
            when (st.code) {
                800 -> { tip = "二维码已过期，请重新获取"; return@LaunchedEffect }
                801 -> tip = "请用网易云 App 扫码"
                802 -> tip = "已扫码，请在 App 上确认"
                803 -> {
                    val ck = st.cookie
                    if (ck.isNullOrBlank()) {
                        tip = "授权完成但未拿到登录凭证，请重新扫码"
                    } else {
                        state.nmLogin(ck)
                        return@LaunchedEffect
                    }
                }
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
            .clickable { state.showSettings = false },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(20.dp)).background(SurfaceColor)
                .clickable(enabled = false) {}
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Txt("登录网易云", 18, weight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoButton("扫码登录", primary = mode == 0) { mode = 0 }
                GoButton("验证码登录", primary = mode == 1) { mode = 1 }
            }
            Spacer(Modifier.height(16.dp))
            if (mode == 0) {
                if (qr != null) {
                    Cover(qr, Modifier.size(200.dp), 256, 12.dp)
                } else {
                    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Txt(tip, 13, color = MutedColor)
            } else {
                GoField("手机号", phone) { phone = it }
                Spacer(Modifier.height(8.dp))
                GoField("验证码", captcha) { captcha = it }
                Spacer(Modifier.height(10.dp))
                GoButton(if (sent) "重新发送" else "发送验证码") {
                    state.toast(
                        if (withIo { Netease.sendCaptcha(phone) }) "验证码已发送" else "发送失败",
                    )
                    sent = true
                }
                Spacer(Modifier.height(8.dp))
                GoButton("登录", primary = true) {
                    val ck = withIo { Netease.loginByCaptcha(phone, captcha) }
                    if (ck.isNullOrBlank()) state.toast("登录失败，请检查验证码")
                    else state.nmLogin(ck)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (state.nmReady) {
                GoButton("已完成登录，关闭") { state.showSettings = false }
            }
        }
    }
}
