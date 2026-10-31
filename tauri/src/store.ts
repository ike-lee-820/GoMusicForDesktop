import { computed, reactive, ref } from "vue"
import { api } from "./api"
import type { DownloadRecord, DlStatus, Group, LrcLine, Page, Playlist, Song } from "./types"
import { buildLyricRows, hasNonChinese, type LyricRow } from "./lyric"
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
  // 是否显示译文：已经有译文后再点翻译按钮就是切换显示
  showTrans: true,
  // 正在整篇翻译
  transLoading: false,
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
  rebuildIndex()
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

/** ssid -> 歌曲 的索引。曲库几千首时，直接 find 是 O(n)，
 *  历史/歌单解析会做 n×m 次比较，是卡死的主因之一。 */
let songIndex: Map<string, Song> | null = null
export function rebuildIndex() {
  const m = new Map<string, Song>()
  for (const s of st.songs) m.set(s.ssid, s)
  for (const s of Object.values(st.snapshots)) if (!m.has(s.ssid)) m.set(s.ssid, s)
  for (const s of st.queue) if (!m.has(s.ssid)) m.set(s.ssid, s)
  songIndex = m
}
export function songOf(ssid: string): Song | null {
  if (!songIndex) rebuildIndex()
  return songIndex!.get(ssid)
    ?? st.snapshots[ssid]
    ?? st.queue.find((s) => s.ssid === ssid)
    ?? null
}

// 播放一次就 bump 一次计数，若每次都重排整个曲库（几千首）会明显卡顿。
// 这里用版本号控制：只有计数真的变了才重排。
const rankVer = ref(0)
export function markRankDirty() { rankVer.value++ }

export const ranked = computed(() => {
  rankVer.value // 依赖版本号
  return [...st.songs].sort((a, b) => (st.playCounts[b.ssid] ?? 0) - (st.playCounts[a.ssid] ?? 0))
})

// 已下载集合：列表每行都要判断，原来用 some 是 O(n×m)
let dlSet: Set<string> | null = null
let dlVer = -1
function dlIndexVer() { return st.downloads.length }
export function isDownloaded(ssid: string): boolean {
  const v = dlIndexVer()
  if (!dlSet || dlVer !== v) { dlSet = new Set(st.downloads.map((d) => d.ssid)); dlVer = v }
  return dlSet.has(ssid)
}

export const historySongs = computed(() =>
  st.history.map((id) => songOf(id)).filter((s): s is Song => !!s),
)

// 分组按曲库引用缓存：切搜索词时不必重建整个 Map
let grpCache: { ref: Song[]; albums: Group[]; artists: Group[] } | null = null
function groups() {
  if (grpCache && grpCache.ref === st.songs) return grpCache
  const albums: Group[] = []
  const artists: Group[] = []
  const am = new Map<string, Group>()
  const rm = new Map<string, Group>()
  for (const s of st.songs) {
    const ak = `${s.album}|${s.artist}`
    let g = am.get(ak)
    if (g) g.songs.push(s)
    else {
      g = {
        title: s.album || "未知专辑", subtitle: s.artist, cover: s.coverUrl, songs: [s],
        artist: s.artist, album: s.album, source: s.source, nmId: 0, nmKind: "album",
      }
      am.set(ak, g); albums.push(g)
    }
    let r = rm.get(s.artist)
    if (r) r.songs.push(s)
    else {
      r = {
        title: s.artist, subtitle: "", cover: s.coverUrl, songs: [s],
        artist: s.artist, album: "", source: s.source, nmId: 0, nmKind: "artist",
      }
      rm.set(s.artist, r); artists.push(r)
    }
  }
  grpCache = { ref: st.songs, albums, artists }
  return grpCache
}

export function albumGroups(): Group[] { return groups().albums }

export function artistGroups(): Group[] { return groups().artists }

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
  markRankDirty()
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

// ============================ 歌词翻译 ============================

const TRANS_KEY = "lyric_trans"
/** 译文缓存上限：整篇译文不小，无限堆积会把 localStorage 撑大 */
const MAX_TRANS = 300

/** ssid -> 按逻辑行对齐的译文 */
const transCache = ref<Record<string, string[]>>(loadTrans())

function loadTrans(): Record<string, string[]> {
  try {
    return JSON.parse(localStorage.getItem(TRANS_KEY) || "{}") || {}
  } catch {
    return {}
  }
}

function saveTrans() {
  try {
    const keys = Object.keys(transCache.value)
    const cut = keys.length > MAX_TRANS ? keys.slice(keys.length - MAX_TRANS) : keys
    const keep: Record<string, string[]> = {}
    for (const k of cut) keep[k] = transCache.value[k]
    localStorage.setItem(TRANS_KEY, JSON.stringify(keep))
  } catch {
    /* 存不下就算了，不影响播放 */
  }
}

/** 当前歌词整理后的逻辑行（自带翻译的歌词会把原文与译文并成一行） */
export const lyricRows = computed<LyricRow[]>(() => {
  if (!st.lyrics.length) return []
  return buildLyricRows(st.lyrics, st.cur ? transCache.value[st.cur.ssid] : undefined)
})

/** 当前句落在哪个逻辑行：播放位置是按原始行算的，这里换算一次 */
export const lyricRowIndex = computed(() => {
  const i = lyricIndex.value
  if (i < 0) return -1
  return lyricRows.value.findIndex((r) => r.index === i)
})

/** 歌词里有非中文才给翻译按钮（中文歌不需要） */
export const lyricNeedTrans = computed(() => lyricRows.value.some((r) => hasNonChinese(r.text)))

/** 是否已经译过：自带译文，或已经翻过并写进了缓存 */
export const lyricHasTrans = computed(() => lyricRows.value.some((r) => !!r.trans))

/**
 * 翻译当前歌词。
 *
 * 点一次就并发翻完整篇并缓存（接口没有频率限制，不用一句一句等）。
 * 已经有译文时不发请求，改成切换译文的显示 —— 按钮这时不该重复翻译。
 */
export async function translateLyric() {
  const rows = lyricRows.value
  const id = st.cur?.ssid
  if (!id || !rows.length) { toast("暂无歌词"); return }
  if (rows.some((r) => r.trans)) { st.showTrans = !st.showTrans; return }
  if (st.transLoading) return
  st.transLoading = true
  try {
    const res = await api.translateLines(rows.map((r) => r.text))
    if (res.every((s) => !s.trim())) { toast("翻译失败，请检查网络"); return }
    transCache.value = { ...transCache.value, [id]: res }
    saveTrans()
    // 翻的过程中可能已经切歌了：缓存照存，但不改动当前显示
    if (st.cur?.ssid === id) {
      st.showTrans = true
      toast(`已翻译 ${res.filter((s) => s.trim()).length}/${res.length} 句`)
    }
  } catch (e: any) {
    toast(String(e?.message ?? e))
  } finally {
    st.transLoading = false
  }
}

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
  rebuildIndex()
  toast("已加入播放列表")
}

export function removeFromQueue(i: number) {
  if (i === st.qIndex) return
  st.queue.splice(i, 1)
  rebuildIndex()
  if (i < st.qIndex) st.qIndex--
}

// ============================ 轮询播放状态 ============================

export const posRatio = computed(() =>
  st.durationMs > 0 ? Math.min(1, st.positionMs / st.durationMs) : 0,
)

let statusTimer: any = null
let busy = false
export function startStatusLoop() {
  if (statusTimer) return
  statusTimer = setInterval(async () => {
    // 没有在播放就不用查：省掉每秒数次的跨进程调用
    if (busy || (!st.playing && !st.cur)) return
    busy = true
    try {
      const s = await api.status().catch(() => null)
      if (s) {
        st.playing = s.playing && !s.paused
        st.positionMs = s.positionMs
        st.durationMs = s.durationMs
        if (s.ended && st.queue.length) await nextSong(false)
      }
    } finally { busy = false }
  }, 500)
}

// ============================ 更新 ============================

export async function checkUpdate(silent = false) {
  const u = await api.checkUpdate().catch(() => null)
  if (u) st.update = u
  else if (!silent) toast("已是最新版本")
}
