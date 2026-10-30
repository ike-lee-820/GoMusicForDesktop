<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue"
import Icon from "./components/Icon.vue"
import SideRail from "./components/SideRail.vue"
import HomeTab from "./components/HomeTab.vue"
import SongList from "./components/SongList.vue"
import GroupGrid from "./components/GroupGrid.vue"
import PlaylistsTab from "./components/PlaylistsTab.vue"
import DetailPage from "./components/DetailPage.vue"
import NowPlayingBar from "./components/NowPlayingBar.vue"
import FullPlayer from "./components/FullPlayer.vue"
import SettingsDialog from "./components/SettingsDialog.vue"
import NmLoginDialog from "./components/NmLoginDialog.vue"
import HistoryDialog from "./components/HistoryDialog.vue"
import VideoOverlay from "./components/VideoOverlay.vue"
import {
  addToPlaylist, albumGroups, artistGroups, checkUpdate, download, load, loadMoreNetease,
  localMatch, mix, playSongs, ranked, searchNetease, setPref, sizeText, st, themeHex,
  startStatusLoop, toast,
} from "./store"
import { api } from "./api"
import type { Group, Page, Song } from "./types"

const TITLES = ["首页", "歌曲", "专辑", "歌手", "歌单"]
const HINTS = ["搜索歌名或歌手", "搜索歌名或歌手", "搜索专辑名或歌手", "搜索歌手", "搜索歌单"]

const title = computed(() => {
  const p = st.page
  if (!p) return TITLES[st.tab] ?? ""
  if (p.kind === "album") return p.name
  if (p.kind === "artist") return p.name
  if (p.kind === "playlist") return st.playlists.find((x) => x.id === p.id)?.name ?? "歌单"
  return p.title
})

// 搜索：切到歌曲/专辑/歌手页时触发网易云搜索
watch(() => [st.query, st.tab, st.page], () => {
  if (st.page) return
  const k = st.tab === 1 ? 0 : st.tab === 2 ? 1 : st.tab === 3 ? 2 : -1
  if (k >= 0) searchNetease(st.query, k)
})

// 主题色：写到 CSS 变量
watch(() => st.themeColor, () => {
  document.documentElement.style.setProperty("--primary", themeHex())
}, { immediate: true })

// 字体：思源宋体
watch(() => st.fontKind, (v) => {
  document.body.classList.toggle("font-song", v === 1)
}, { immediate: true })

const localSongs = computed<Song[]>(() => {
  const q = st.query.trim()
  const base = ranked.value
  if (!q) return base
  return base.filter((s) => localMatch(s.title, q) || localMatch(s.artist, q) || localMatch(s.album, q))
})

const songList = computed<Song[]>(() => mix(localSongs.value, st.nmSongs))

const albums = computed<Group[]>(() => {
  const q = st.query.trim()
  const loc = albumGroups().filter((g) => !q || localMatch(g.title, q) || localMatch(g.artist, q))
  return dedup(mix(loc, st.nmAlbums))
})
const artists = computed<Group[]>(() => {
  const q = st.query.trim()
  const loc = artistGroups().filter((g) => !q || localMatch(g.title, q))
  return dedup(mix(loc, st.nmArtists))
})
function dedup(list: Group[]): Group[] {
  const seen = new Set<string>()
  return list.filter((g) => {
    const k = g.nmId > 0 ? `nm${g.nmId}` : `loc${g.title}|${g.artist}`
    if (seen.has(k)) return false
    seen.add(k)
    return true
  })
}

function openGroup(g: Group) {
  if (g.source === "netease" || g.nmId > 0) {
    st.page = { kind: "nm", title: g.title, kind2: g.nmKind || "playlist", id: g.nmId }
  } else if (g.nmKind === "artist") {
    st.page = { kind: "artist", name: g.title }
  } else {
    st.page = { kind: "album", name: g.album || g.title, artist: g.artist }
  }
}

// 加入歌单：从歌曲列表点「＋」
const pickSong = ref<Song | null>(null)
function onPick(s: Song) { pickSong.value = s }
async function doAdd(id: string) {
  if (!pickSong.value) return
  await addToPlaylist(id, [pickSong.value.ssid])
  toast("已加入歌单")
  pickSong.value = null
}

onMounted(async () => {
  await load()
  startStatusLoop()
  applyMode()
  checkUpdate(true)
})

/** 把已保存的播放模式 / 倍速同步给播放引擎 */
async function applyMode() {
  if (st.speed && st.speed !== 1) await api.setRate(st.speed)
}

function onReachEnd() {
  if (st.tab === 1) loadMoreNetease(0, st.query)
}
</script>

<template>
  <div class="app">
    <div class="main-row">
      <SideRail />
      <div class="content">
        <div class="head">
          <button v-if="st.page" class="iconbtn" title="返回" @click="st.page = null"><Icon name="arrow_back" :size="20" /></button>
          <span class="t ellip">{{ title }}</span>
          <button class="iconbtn" title="设置" @click="st.showSettings = true"><Icon name="settings" :size="20" /></button>
        </div>
        <div v-if="!st.page" class="searchrow">
          <input v-model="st.query" class="field" :placeholder="HINTS[st.tab]" />
          <button v-if="st.query" class="iconbtn" title="清除搜索" @click="st.query = ''"><Icon name="close" :size="18" /></button>
        </div>

        <div v-if="st.offline" class="notice">
          <span>离线模式 · 已下载 {{ st.downloads.length }} 首</span>
          <button class="btn primary" @click="load()">重试</button>
        </div>
        <div v-if="st.dl" class="notice col">
          <div class="dlrow">
            <span class="ellip">正在下载 {{ st.dl.index }}/{{ st.dl.total }} · {{ st.dl.title }}</span>
            <span v-if="st.dl.bps > 0">{{ sizeText(st.dl.bps) }}/s</span>
          </div>
          <div class="bar"><i :style="{ width: (st.dl.size ? st.dl.done / st.dl.size * 100 : 0) + '%' }" /></div>
          <div class="muted small">{{ sizeText(st.dl.done) }} / {{ sizeText(st.dl.size) }}</div>
        </div>

        <div class="body">
          <DetailPage v-if="st.page" :page="st.page as Page" @back="st.page = null" />
          <template v-else>
            <div v-if="st.loading && !st.songs.length" class="center"><span class="spinner" /></div>
            <HomeTab v-else-if="st.tab === 0" @open-group="openGroup" />
            <template v-else-if="st.tab === 1">
              <div v-if="st.nmSearching && !st.nmSongs.length" class="center muted small">
                正在搜索网易云曲库
              </div>
              <SongList :list="songList" @pick="onPick" @reach-end="onReachEnd" />
            </template>
            <GroupGrid v-else-if="st.tab === 2" :groups="albums" @open="openGroup" />
            <GroupGrid v-else-if="st.tab === 3" :groups="artists" wide @open="openGroup" />
            <PlaylistsTab v-else @open="(id) => (st.page = { kind: 'playlist', id })" />
          </template>
        </div>
      </div>
    </div>
    <NowPlayingBar />

    <FullPlayer v-if="st.showFull" />
    <VideoOverlay v-if="st.showVideo" />
    <SettingsDialog v-if="st.showSettings" />
    <NmLoginDialog v-if="st.showLogin" />
    <HistoryDialog v-if="st.showHistory" />

    <!-- 更新 -->
    <div v-if="st.update" class="overlay" @click="st.update = null">
      <div class="dialog up" @click.stop>
        <div class="tt">发现新版本 {{ st.update.version }}</div>
        <div class="notes muted">{{ st.update.notes || "无更新说明" }}</div>
        <div class="row">
          <button class="btn ghost" @click="st.update = null">稍后</button>
          <button class="btn primary" @click="api.openUrl(st.update.url); st.update = null">去下载</button>
        </div>
      </div>
    </div>

    <!-- 加入歌单 -->
    <div v-if="pickSong" class="overlay" @click="pickSong = null">
      <div class="dialog pick" @click.stop>
        <div class="ph">加入歌单：{{ pickSong.title }}</div>
        <div v-if="!st.playlists.length" class="pi muted">还没有歌单</div>
        <div v-for="p in st.playlists" :key="p.id" class="pi" @click="doAdd(p.id)">{{ p.name }}</div>
      </div>
    </div>

    <div v-if="st.toastMsg" class="toast">{{ st.toastMsg }}</div>
  </div>
</template>

<style scoped>
.app { height: 100%; display: flex; flex-direction: column; }
.main-row { flex: 1; display: flex; min-height: 0; }
.content { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.head { display: flex; align-items: center; gap: 6px; padding: 12px 16px 6px; }
.head .t { flex: 1; font-size: 20px; font-weight: 800; }
.searchrow { display: flex; gap: 8px; padding: 4px 16px 8px; }
.searchrow .field { max-width: 420px; }
.notice {
  display: flex; align-items: center; gap: 10px; margin: 0 16px 8px; padding: 8px 12px;
  border-radius: 12px; background: var(--surface-hi); font-size: 13px;
}
.notice.col { flex-direction: column; align-items: stretch; gap: 6px; }
.dlrow { display: flex; justify-content: space-between; gap: 10px; }
.small { font-size: 12px; }
.body { flex: 1; min-height: 0; overflow: auto; }
.center { display: flex; align-items: center; justify-content: center; padding: 40px; }
.up { width: 420px; padding: 18px; }
.up .tt { font-size: 16px; font-weight: 800; margin-bottom: 8px; }
.up .notes { max-height: 200px; overflow: auto; font-size: 12px; white-space: pre-wrap; }
.up .row { display: flex; justify-content: flex-end; gap: 8px; margin-top: 14px; }
.pick { width: 320px; padding: 6px; }
.ph { padding: 10px 12px; font-weight: 700; }
.pi { padding: 10px 12px; border-radius: 8px; cursor: pointer; }
.pi:hover { background: var(--surface-hi); }
</style>
