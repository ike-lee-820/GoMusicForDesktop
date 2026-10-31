<script setup lang="ts">
/**
 * 横版全屏播放器：桌面端唯一的播放器形态（无竖版）。
 * 顶栏：× 关闭 + 功能键/播放控制/进度条/播放列表/播放顺序 横排，最右箭头控制展开收起；
 * 主体：左侧封面与歌曲信息，右侧圆弧歌词。
 */
import { computed, ref, watch } from "vue"
import ArcLyrics from "./ArcLyrics.vue"
import Icon from "./Icon.vue"
import { api } from "../api"
import type { Song } from "../types"
import {
  fmt, isDownloaded, LOCAL_QUALITIES, lyricHasTrans, lyricNeedTrans, lyricRowIndex,
  lyricRows, NM_QUALITIES, nextSong, prevSong, qualityLabel, seek, setQuality,
  setSpeed, st, startCurrent, toggle, toast, translateLyric,
} from "../store"

const ctrlOpen = ref(true)
const showQueue = ref(false)
const showQuality = ref(false)
const showSpeed = ref(false)

const cur = computed(() => st.cur)
const isNm = computed(() => !!cur.value && (cur.value.source === "netease" || cur.value.ssid.startsWith("nm")))

// 网易云可选音质按歌缓存：同一首反复打开播放器不必每次都查
const levelCache = new Map<string, string[]>()
const levels = ref<string[]>([])

// MV 是否真能播：mvId>0 只说明接口给了 id，下架/付费的 MV 拿不到地址。
// 确认过才显示按钮；请求失败判断不了时按「有」处理，不因为一次抖动藏掉。
const mvOk = ref<Record<string, boolean>>({})
const mvProbing = new Set<string>()
async function probeMv(s: Song) {
  if (!s?.mvId || mvProbing.has(s.ssid) || s.ssid in mvOk.value) return
  mvProbing.add(s.ssid)
  try {
    const u = await api.nmMvUrl(s.mvId)
    mvOk.value = { ...mvOk.value, [s.ssid]: !!u }
  } catch {
    mvOk.value = { ...mvOk.value, [s.ssid]: true }
  } finally { mvProbing.delete(s.ssid) }
}

watch(cur, async (s) => {
  levels.value = []
  if (!s) return
  probeMv(s)
  if (!(s.source === "netease" || s.ssid.startsWith("nm"))) return
  const hit = levelCache.get(s.ssid)
  if (hit) { levels.value = hit; return }
  const v = await api.nmAvailableLevels(s.ssid).catch(() => [])
  levelCache.set(s.ssid, v)
  levels.value = v
}, { immediate: true })

const qualityOptions = computed(() => {
  if (isNm.value) {
    const l = levels.value.length ? levels.value : NM_QUALITIES
    return l.map((k) => ({ k, label: qualityLabel(k) }))
  }
  return LOCAL_QUALITIES.map((k) => ({ k, label: qualityLabel(k) }))
})

const speedOptions = [0.5, 0.75, 1, 1.25, 1.5, 2]
const modeIcons = ["repeat", "shuffle", "repeat_one"]
const modeNames = ["列表循环", "随机播放", "单曲循环"]

async function playMv() {
  const s = cur.value
  if (!s?.mvId) return
  const url = await api.nmMvUrl(s.mvId).catch(() => null)
  if (!url) { toast("没有可播放的 MV"); return }
  st.videoUrl = url
  st.showVideo = true
}

function onSeekRatio(e: MouseEvent) {
  const el = e.currentTarget as HTMLElement
  if (st.durationMs > 0) seek(Math.round((e.offsetX / el.clientWidth) * st.durationMs))
}

function cycleMode() {
  st.playMode = (st.playMode + 1) % 3
  api.setPref("playMode", String(st.playMode))
  toast(modeNames[st.playMode])
}

const coverStyle = computed(() =>
  cur.value?.coverUrl ? `background-image:url(${cur.value.coverUrl})` : "",
)
</script>

<template>
  <div v-if="cur" class="fp">
    <!-- 顶栏 -->
    <div class="top">
      <button class="iconbtn close" title="关闭播放器" @click="st.showFull = false">
        <Icon name="close" :size="20" />
      </button>
      <div class="ctrls">
        <div v-if="ctrlOpen" class="row">
          <button v-if="cur.mvId && mvOk[cur.ssid] === true" class="iconbtn" title="播放 MV" @click="playMv">
            <Icon name="music_video" :size="20" />
          </button>
          <!-- 翻译：歌词里有非中文才出现。用「译」字，一眼就懂 -->
          <button v-if="lyricNeedTrans" class="iconbtn trans-btn"
                  :class="{ on: lyricHasTrans && st.showTrans }"
                  :title="st.transLoading ? '正在翻译…' : (lyricHasTrans ? '译文显示中，点击隐藏' : '翻译歌词')"
                  @click="translateLyric()">
            <span v-if="!st.transLoading">译</span>
            <span v-else class="mini-spin" />
          </button>
          <button class="iconbtn" :class="{ on: isDownloaded(cur.ssid) }"
                  :title="isDownloaded(cur.ssid) ? '已下载' : '下载'"
                  @click="st.showFull = false">
            <Icon :name="isDownloaded(cur.ssid) ? 'check_circle' : 'download'" :size="20" />
          </button>
          <button class="iconbtn" title="加入歌单" @click="st.showFull = false">
            <Icon name="playlist_add" :size="20" />
          </button>
          <button class="btn qbtn" title="音质" @click="showQuality = !showQuality">
            <Icon name="high_quality" :size="16" />{{ qualityLabel(st.quality) }}
          </button>
          <button class="btn qbtn" title="倍速" @click="showSpeed = !showSpeed">
            <Icon name="speed" :size="16" />{{ st.speed }}×
          </button>
          <span class="sep" />
          <button class="iconbtn" title="上一首" @click="prevSong"><Icon name="skip_previous" :size="20" /></button>
          <button class="iconbtn play" :title="st.playing ? '暂停' : '播放'" @click="toggle">
            <Icon :name="st.playing ? 'pause' : 'play_arrow'" :size="20" />
          </button>
          <button class="iconbtn" title="下一首" @click="nextSong(true)"><Icon name="skip_next" :size="20" /></button>
          <span class="sep" />
          <span class="time">{{ fmt(st.positionMs) }}</span>
          <div class="seek" @click="onSeekRatio">
            <div class="track"><i :style="{ width: (st.durationMs ? st.positionMs / st.durationMs * 100 : 0) + '%' }" /></div>
          </div>
          <span class="time">{{ fmt(st.durationMs) }}</span>
          <span class="sep" />
          <button class="iconbtn" :class="{ on: showQueue }" title="播放列表" @click="showQueue = !showQueue">
            <Icon name="queue_music" :size="20" />
          </button>
          <button class="iconbtn" :title="modeNames[st.playMode]" @click="cycleMode">
            <Icon :name="modeIcons[st.playMode]" :size="20" />
          </button>
        </div>
        <!-- 音质 / 倍速浮层 -->
        <div v-if="showQuality && ctrlOpen" class="pop">
          <div v-for="o in qualityOptions" :key="o.k" class="pop-item"
               :class="{ on: o.k === st.quality }" @click="setQuality(o.k); showQuality = false">
            {{ o.label }}
          </div>
        </div>
        <div v-if="showSpeed && ctrlOpen" class="pop">
          <div v-for="s in speedOptions" :key="s" class="pop-item" :class="{ on: s === st.speed }"
               @click="setSpeed(s); showSpeed = false">{{ s }}×</div>
        </div>
      </div>
      <button class="iconbtn arrow" :title="ctrlOpen ? '收起' : '展开'"
              @click="ctrlOpen = !ctrlOpen">
        <Icon :name="ctrlOpen ? 'chevron_left' : 'keyboard_arrow_down'" :size="22" />
      </button>
    </div>

    <!-- 主体：封面 + 歌词 -->
    <div class="body">
      <div class="left">
        <div class="cover" :style="coverStyle" />
        <div class="title ellip">{{ cur.title }}</div>
        <div class="sub ellip">{{ cur.artist }}</div>
        <div class="sub muted ellip">{{ cur.album }}</div>
      </div>
      <div class="right">
        <ArcLyrics
          :rows="lyricRows" :idx="lyricRowIndex" :loading="st.lyricLoading"
          :show-trans="st.showTrans"
          color="var(--primary)" :row-spacing="63" :radius-boost="0.7"
          :min-span="3" :cur-sp="22" :other-sp="17"
          @seek="(ms) => seek(ms)"
        />
      </div>
    </div>

    <!-- 播放队列 -->
    <div v-if="showQueue" class="queue">
      <div class="q-head">播放列表（{{ st.queue.length }}）</div>
      <div v-for="(s, i) in st.queue" :key="s.ssid + i" class="q-row" :class="{ on: i === st.qIndex }"
           @click="st.qIndex = i; startCurrent(0)">
        <span class="idx">{{ i + 1 }}</span>
        <span class="t ellip">{{ s.title }}</span>
        <span class="a ellip muted">{{ s.artist }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.fp {
  position: fixed; inset: 0; z-index: 80; background: #0d0b0f;
  display: flex; flex-direction: column;
}
.top {
  display: flex; align-items: center; gap: 8px; padding: 10px 14px;
  border-bottom: 1px solid rgba(255, 255, 255, .07); flex: none;
}
.ctrls { flex: 1; min-width: 0; position: relative; }
.ctrls .row { display: flex; align-items: center; gap: 6px; overflow-x: auto; }
.sep { width: 1px; height: 20px; background: rgba(255, 255, 255, .12); flex: none; margin: 0 2px; }
.time { font-size: 12px; color: var(--muted); flex: none; font-variant-numeric: tabular-nums; }
.seek { flex: none; width: 160px; height: 24px; display: flex; align-items: center; cursor: pointer; }
.track { width: 100%; height: 4px; border-radius: 2px; background: rgba(255, 255, 255, .2); }
.track > i { display: block; height: 100%; background: #fff; border-radius: 2px; }
.play { background: var(--primary) !important; color: var(--on-primary) !important; }
.btn.qbtn { height: 28px; padding: 0 10px; font-size: 12px; gap: 4px; }
.trans-btn { font-size: 15px; font-weight: 700; }
.trans-btn.on { color: var(--primary); }
.mini-spin {
  width: 14px; height: 14px; border-radius: 50%; display: inline-block;
  border: 2px solid rgba(255, 255, 255, .25); border-top-color: #fff;
  animation: spin .9s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.pop {
  position: absolute; top: 40px; left: 0; z-index: 5; min-width: 130px;
  background: var(--surface); border-radius: 12px; padding: 6px;
  box-shadow: 0 10px 30px rgba(0, 0, 0, .5);
}
.pop-item { padding: 7px 12px; border-radius: 8px; cursor: pointer; font-size: 13px; }
.pop-item:hover { background: var(--surface-hi); }
.pop-item.on { color: var(--primary); }

.body { flex: 1; display: flex; min-height: 0; }
.left {
  width: 34%; flex: none; padding: 24px; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 8px;
}
.cover {
  width: min(320px, 90%); aspect-ratio: 1; border-radius: 14px;
  background-size: cover; background-position: center; background-color: var(--surface-hi);
  margin-bottom: 12px;
}
.title { font-size: 20px; font-weight: 700; max-width: 100%; }
.sub { font-size: 13px; max-width: 100%; }
.right { flex: 1; min-width: 0; }

.queue {
  position: absolute; right: 14px; top: 56px; width: 340px; max-height: 60vh;
  overflow: auto; background: var(--surface); border-radius: 14px; padding: 10px;
  box-shadow: 0 14px 40px rgba(0, 0, 0, .55); z-index: 6;
}
.q-head { font-size: 13px; font-weight: 700; padding: 4px 6px 8px; }
.q-row { display: flex; gap: 8px; padding: 6px; border-radius: 8px; cursor: pointer; font-size: 13px; }
.q-row:hover { background: var(--surface-hi); }
.q-row.on { color: var(--primary); }
.idx { width: 22px; color: var(--muted); flex: none; }
.t { flex: 1; min-width: 0; }
.a { width: 34%; min-width: 0; text-align: right; }
</style>
