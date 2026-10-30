import { computed, reactive, ref } from "vue"
import { api } from "./api"
import type { DownloadRecord, DlStatus, Group, LrcLine, Page, Playlist, Song } from "./types"
import { zhText } from "./zh"

/** 播放模式 */
export const MODE_LIST_LOOP = 0
export const MODE_SHUFFLE = 1
export const MODE_REPEAT_ONE = 2

export const LOCAL_QUALITIES = ["lossless", "high", "normal"]
export const NM_QUALITIES = ["lossless", "hires", "exhigh", "higher", "standard"]

export const st = reactive({
  // ---- 曲库 ----
  songs: [] as Song[],
  artists: {} as Record<string, { image?: string | null; bio?: string | null }>,
  playCounts: {} as Record<string, number>,
  downloads: [] as DownloadRecord[],
  playlists: [] as Playlist[],
  history: [] as string[],
  snapshots: {} as Record<string, Song>,
  offline: false,
  loading: true,
  error: "" as string,

  // ---- 导航 ----
  tab: 0, // 0 首页 1 歌曲 2 专辑 3 歌手 4 歌单
  page: null as Page | null,
  query: "",

  // ---- 网易云 ----
  nmSongs: [] as Song[],
  nmAlbums: [] as Group[],
  nmArtists: [] as Group[],
  nmSearching: false,
  nmHasMore: false,
  nmOffset: 0,
  nmReady: false,
  home: null as null | {
    recommend: Song[]; newSongs: Song[]; hotWords: string[]
    topArtists: Group[]; newAlbums: Group[]; tops: Group[]
  },

  // ---- 播放 ----
  queue: [] as Song[],
  qIndex: -1,
  cur: null as Song | null,
  playing: false,
  positionMs: 0,
  durationMs: 0,
  quality: "normal",
  playMode: 0,
  speed: 1,
  lyrics: [] as LrcLine[],
  lyricLoading: false,

  // ---- 设置 ----
  fontKind: 0,
  zhConvert: 0,
  themeColor: null as number | null,
  nmEnabled: true,
  nmAccount: "",
  version: "",

  // ---- 界面 ----
  showSettings: false,
  showHistory: false,
  showLogin: false,
  showFull: false,
  showVideo: false,
  videoUrl: "" as string,
  toastMsg: "" as string,
  dl: null as DlStatus | null,
  update: null as null | { version: string; url: string; notes: string },
})

let toastTimer: any = null
export function toast(msg: string) {
  st.toastMsg = msg
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (st.toastMsg = ""), 2200)
}

// ============================ 载入 ============================

export async function load() {
  st.loading = true
  st.error = ""
  try {
    const lib = await api.loadLibrary()
    st.songs = lib.songs
    st.artists = lib.artists
    st.playCounts = lib.playCounts
    st.downloads = lib.downloads
    st.offline = lib.offline
  } catch (e: any) {
    st.error = String(e?.message ?? e)
    st.offline = true
  }
  try {
    const p = await api.getPrefs()
    st.playlists = p.playlists ?? []
    st.history = p.history ?? []
    st.snapshots = p.songSnapshots ?? {}
    st.quality = p.quality || "normal"
    st.playMode = p.playMode ?? 0
    st.fontKind = p.fontKind ?? 0
    st.zhConvert = p.zhConvert ?? 0
    st.speed = p.speed || 1
    st.themeColor = p.themeColor ?? null
    st.nmEnabled = p.nmEnabled ?? true
    st.nmAccount = p.nmAccount ?? ""
  } catch { /* 首次启动没有配置 */ }
  st.loading = false
  st.nmReady = await api.nmLoggedIn().catch(() => false)
  st.version = await api.appVersion().catch(() => "")
}

export async function loadHome() {
  if (st.home) return
  try {
    st.home = await api.nmHome(0)
  } catch { st.home = null }
}

// ============================ 列表派生 ============================

export function songOf(ssid: string): Song | null {
  return st.songs.find((s) => s.ssid === ssid)
    ?? st.snapshots[ssid]
    ?? st.queue.find((s) => s.ssid === ssid)
    ?? null
}

export const ranked = computed(() =>
  [...st.songs].sort((a, b) => (st.playCounts[b.ssid] ?? 0) - (st.playCounts[a.ssid] ?? 0)),
)

export const historySongs = computed(() =>
  st.history.map((id) => songOf(id)).filter((s): s is Song => !!s),
)

export function albumGroups(): Group[] {
  const m = new Map<string, Group>()
  for (const s of st.songs) {
    const key = `${s.album}|${s.artist}`
    const g = m.get(key)
    if (g) g.songs.push(s)
    else m.set(key, {
      title: s.album || "未知专辑", subtitle: s.artist, cover: s.coverUrl, songs: [s],
      artist: s.artist, album: s.album, source: s.source, nmId: 0, nmKind: "album",
    })
  }
  return [...m.values()]
}

export function artistGroups(): Group[] {
  const m = new Map<string, Group>()
  for (const s of st.songs) {
    const g = m.get(s.artist)
    if (g) g.songs.push(s)
    else m.set(s.artist, {
      title: s.artist, subtitle: `${st.artists[s.artist]?.bio ? "" : ""}`, cover: s.coverUrl,
      songs: [s], artist: s.artist, album: "", source: s.source, nmId: 0, nmKind: "artist",
    })
  }
  return [...m.values()]
}

/** 本地结果与网易云结果合并，本地在前 */
export function mix<T>(local: T[], nm: T[]): T[] { return [...local, ...nm] }

export function localMatch(text: string, q: string): boolean {
  if (!q) return true
  const t = text.toLowerCase()
  if (t.includes(q.toLowerCase())) return true
  if (st.zhConvert === 2) {
    const c = zhText(text, 2).toLowerCase()
    if (c.includes(q.toLowerCase())) return true
  }
  return false
}

export function isDownloaded(ssid: string) { return st.downloads.some((d) => d.ssid === ssid) }

export function coverOf(ref?: string | null): string {
  if (!ref) return ""
  if (ref.startsWith("http")) return ref
  return ref
}

export function fmt(ms: number): string {
  if (!ms || ms < 0) return "--:--"
  const s = Math.floor(ms / 1000)
  return `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`
}

export function sizeText(b: number): string {
  if (b < 1024) return `${b} B`
  const kb = b / 1024
  if (kb < 1024) return `${kb.toFixed(0)} KB`
  const mb = kb / 1024
  if (mb < 1024) return `${mb.toFixed(1)} MB`
  return `${(mb / 1024).toFixed(2)} GB`
}

export function qualityLabel(k: string): string {
  const map: Record<string, string> = {
    lossless: "无损", high: "高品质", normal: "标准", source: "原声",
    standard: "标准 128k", higher: "较高 192k", exhigh: "极高 320k", hires: "Hi-Res",
    jyeffect: "高清臻音", dolby: "杜比全景声", vivid: "臻音全景声",
    jymaster: "超清母带", sky: "沉浸环绕声",
  }
  return map[k] ?? k
}

// ============================ 网易云搜索 ============================

let searchTimer: any = null
let searchSeq = 0

export function searchNetease(q: string, kind: number) {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => doSearch(q, kind), 350)
}

async function doSearch(q: string, kind: number) {
  const seq = ++searchSeq
  if (!q.trim()) { st.nmSongs = []; st.nmAlbums = []; st.nmArtists = []; st.nmSearching = false; return }
  st.nmSearching = true
  st.nmOffset = 0
  try {
    const r = await api.nmSearch(kind, q.trim(), 30, 0)
    if (seq !== searchSeq) return
    st.nmSongs = r.songs ?? []
    st.nmAlbums = r.albums ?? []
    st.nmArtists = r.artists ?? []
    st.nmHasMore = (kind === 0 ? r.songs.length : kind === 1 ? r.albums.length : r.artists.length) >= 30
  } catch { /* 搜索失败不影响本地结果 */ }
  if (seq === searchSeq) st.nmSearching = false
}

export async function loadMoreNetease(kind: number, q: string) {
  if (st.nmSearching || !st.nmHasMore) return
  st.nmSearching = true
  st.nmOffset += 30
  try {
    const r = await api.nmSearch(kind, q, 30, st.nmOffset)
    if (kind === 0) st.nmSongs.push(...(r.songs ?? []))
    if (kind === 1) st.nmAlbums.push(...(r.albums ?? []))
    if (kind === 2) st.nmArtists.push(...(r.artists ?? []))
    st.nmHasMore = (kind === 0 ? r.songs.length : kind === 1 ? r.albums.length : r.artists.length) >= 30
  } finally { st.nmSearching = false }
}

// ============================ 播放 ============================

export async function playSongs(list: Song[], i: number) {
  if (!list.length) return
  st.queue = list
  st.qIndex = i
  await startCurrent(0)
}

export async function startCurrent(atMs = 0) {
  const s = st.queue[st.qIndex]
  if (!s) return
  st.cur = s
  st.showFull = true // 桌面端直接进横版全屏播放器
  st.lyrics = []
  st.lyricLoading = true
  try {
    const stt = await api.playSong(s, currentQuality(s))
    st.playing = stt.playing
    st.durationMs = stt.durationMs
    st.positionMs = stt.positionMs
    if (atMs > 0) await api.seek(atMs)
  } catch (e: any) {
    toast(String(e?.message ?? e))
  }
  api.loadLyric(s.ssid).then((l) => {
    if (st.cur?.ssid === s.ssid) { st.lyrics = l ?? [] }
    st.lyricLoading = false
  }).catch(() => { st.lyricLoading = false })
  await api.rememberSongs([s]).catch(() => {})
}

/** 当前歌曲实际使用的音质：网易云歌曲用档位，本站用本地键 */
function currentQuality(s: Song): string {
  const isNm = s.source === "netease" || s.ssid.startsWith("nm")
  if (isNm && !NM_QUALITIES.includes(st.quality)) return "exhigh"
  return st.quality
}

export async function toggle() {
  if (!st.cur) return
  if (st.playing) { await api.pause(); st.playing = false }
  else { await api.resume(); st.playing = true }
}

export async function nextSong(user = true) {
  if (!st.queue.length) return
  if (st.playMode === MODE_REPEAT_ONE && !user) { await startCurrent(0); return }
  let i = st.qIndex + 1
  if (st.playMode === MODE_SHUFFLE) i = Math.floor(Math.random() * st.queue.length)
  if (i >= st.queue.length) i = 0
  st.qIndex = i
  await startCurrent(0)
}

export async function prevSong() {
  if (!st.queue.length) return
  if (st.playMode === MODE_REPEAT_ONE) { await startCurrent(0); return }
  let i = st.qIndex - 1
  if (i < 0) i = st.queue.length - 1
  st.qIndex = i
  await startCurrent(0)
}

export async function seek(ms: number) {
  await api.seek(Math.max(0, ms))
  st.positionMs = ms
}

export async function cycleMode() {
  st.playMode = (st.playMode + 1) % 3
  await setPref("playMode", String(st.playMode))
  toast(["列表循环", "随机播放", "单曲循环"][st.playMode])
}

export const lyricIndex = computed(() => {
  if (!st.lyrics.length) return -1
  let lo = 0, hi = st.lyrics.length - 1, ans = -1
  const t = st.positionMs
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    if (st.lyrics[mid].timeMs <= t + 200) { ans = mid; lo = mid + 1 } else hi = mid - 1
  }
  return ans
})

// ============================ 设置 ============================

export async function setPref(key: string, value: string) {
  await api.setPref(key, value).catch(() => {})
}

export async function setQuality(q: string) {
  st.quality = q
  await setPref("quality", q)
  toast("音质已切换为 " + qualityLabel(q))
  if (st.cur) await startCurrent(st.positionMs)
}

export async function setSpeed(v: number) {
  st.speed = v
  await api.setRate(v)
  await setPref("speed", String(v))
}

export async function setFontKind(v: number) {
  st.fontKind = v
  await setPref("fontKind", String(v))
}

export async function setZhConvert(v: number) {
  st.zhConvert = v
  await setPref("zhConvert", String(v))
}

export async function setThemeColor(c: number | null) {
  st.themeColor = c
  await setPref("themeColor", c == null ? "" : String(c))
}

export function themeHex(): string {
  const c = st.themeColor
  if (c == null) return "#d0bcff"
  const v = c >>> 0
  return "#" + (v & 0xffffff).toString(16).padStart(6, "0")
}

// ============================ 下载 ============================

export async function download(songs: Song[], quality?: string) {
  const q = quality ?? st.quality
  try {
    await api.downloadSongs(songs, q)
    toast(`已加入下载：${songs.length} 首`)
  } catch (e: any) { toast(String(e?.message ?? e)) }
  pollDl()
}

let dlTimer: any = null
export function pollDl() {
  if (dlTimer) return
  dlTimer = setInterval(async () => {
    const d = await api.downloadStatus().catch(() => null)
    st.dl = d
    if (!d) { clearInterval(dlTimer); dlTimer = null; await refreshDownloads() }
  }, 500)
}

async function refreshDownloads() {
  try {
    const p = await api.getPrefs()
    st.downloads = p.downloads ?? []
  } catch { /* ignore */ }
}

export async function deleteDownload(ssid: string) {
  st.downloads = await api.deleteDownload(ssid)
}

// ============================ 歌单 ============================

export async function createPlaylist(name: string, songs: string[] = []) {
  st.playlists = await api.createPlaylist(name, songs)
}

export async function renamePlaylist(id: string, name: string) {
  st.playlists = await api.renamePlaylist(id, name)
  const p = st.playlists.find((x) => x.id === id)
  if (p?.name) await syncNmRename(id, p.name)
}

export async function deletePlaylist(id: string) {
  st.playlists = await api.deletePlaylist(id)
}

export async function addToPlaylist(id: string, ids: string[]) {
  st.playlists = await api.addToPlaylist(id, ids)
  await syncNmAdd(id, ids)
}

export async function removeFromPlaylist(id: string, ssid: string) {
  st.playlists = await api.removeFromPlaylist(id, ssid)
  await syncNmDel(id, [ssid])
}

/** 本地歌单若绑定了网易云歌单（id 形如 nm-xxx 或记录在 name 后），把改动回写 */
function nmIdOf(pl: Playlist): number {
  const m = /^nm(\d+)$/.exec(pl.id)
  return m ? Number(m[1]) : 0
}

async function syncNmAdd(id: string, ids: string[]) {
  const pl = st.playlists.find((x) => x.id === id)
  if (!pl) return
  const pid = nmIdOf(pl)
  if (pid > 0) await api.nmPlaylistAdd(pid, ids).catch(() => {})
}
async function syncNmDel(id: string, ids: string[]) {
  const pl = st.playlists.find((x) => x.id === id)
  if (!pl) return
  const pid = nmIdOf(pl)
  if (pid > 0) await api.nmPlaylistDel(pid, ids).catch(() => {})
}
async function syncNmRename(id: string, name: string) {
  const pl = st.playlists.find((x) => x.id === id)
  if (!pl) return
  const pid = nmIdOf(pl)
  if (pid > 0) await api.nmPlaylistRename(pid, name).catch(() => {})
}

/** 同步网易云「我创建的」歌单 */
export async function syncNeteasePlaylists() {
  const list = await api.nmUserPlaylists()
  if (!list.length) { toast("没有可同步的歌单，请先登录网易云"); return }
  let n = 0
  for (const g of list) {
    const id = `nm${g.nmId}`
    const ids = g.songs.map((s) => s.ssid)
    const exist = st.playlists.find((p) => p.id === id)
    if (exist) {
      st.playlists = await api.renamePlaylist(id, g.title)
      if (ids.length) st.playlists = await api.addToPlaylist(id, ids)
    } else {
      st.playlists = await api.createPlaylist(g.title, ids, g.cover ?? null)
      const created = st.playlists[st.playlists.length - 1]
      if (created) {
        // 用网易云 id 作为歌单 id，便于后续回写
        st.playlists = st.playlists.map((p) => p.id === created.id ? { ...p, id } : p)
        await api.renamePlaylist(id, g.title)
      }
    }
    n++
  }
  toast(`已同步 ${n} 个网易云歌单`)
}

// ============================ 播放列表（队列） ============================

export function addToQueue(s: Song) {
  st.queue.push(s)
  toast("已加入播放列表")
}

export function removeFromQueue(i: number) {
  if (i === st.qIndex) return
  st.queue.splice(i, 1)
  if (i < st.qIndex) st.qIndex--
}

// ============================ 轮询播放状态 ============================

export const posRatio = computed(() =>
  st.durationMs > 0 ? Math.min(1, st.positionMs / st.durationMs) : 0,
)

let statusTimer: any = null
export function startStatusLoop() {
  if (statusTimer) return
  statusTimer = setInterval(async () => {
    const s = await api.status().catch(() => null)
    if (!s) return
    st.playing = s.playing && !s.paused
    st.positionMs = s.positionMs
    st.durationMs = s.durationMs
    if (s.ended && st.queue.length) await nextSong(false)
  }, 250)
}

// ============================ 更新 ============================

export async function checkUpdate(silent = false) {
  const u = await api.checkUpdate().catch(() => null)
  if (u) st.update = u
  else if (!silent) toast("已是最新版本")
}
