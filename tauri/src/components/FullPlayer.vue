<script setup lang="ts">
/**
 * 横版全屏播放器：桌面端唯一的播放器形态（无竖版）。
 * 顶栏：× 关闭 + 功能键/播放控制/进度条/播放列表/播放顺序 横排，最右箭头控制展开收起；
 * 主体：左侧封面与歌曲信息，右侧圆弧歌词。
 */
import { computed, ref, watch } from "vue"
import ArcLyrics from "./ArcLyrics.vue"
import {
  fmt, isDownloaded, lyricIndex, LOCAL_QUALITIES, nextSong, NM_QUALITIES, prevSong,
  qualityLabel, seek, setQuality, setSpeed, st, startCurrent, toggle, toast,
} from "../store"
import { api } from "../api"

const ctrlOpen = ref(true)
const showQueue = ref(false)
const showQuality = ref(false)
const showSpeed = ref(false)
const showPick = ref(false)

const cur = computed(() => st.cur)
const isNm = computed(() => !!cur.value && (cur.value.source === "netease" || cur.value.ssid.startsWith("nm")))

const levels = ref<string[]>([])
watch(cur, async (s) => {
  levels.value = []
  if (!s) return
  if (s.source === "netease" || s.ssid.startsWith("nm")) {
    levels.value = await api.nmAvailableLevels(s.ssid).catch(() => [])
  }
}, { immediate: true })

const qualityOptions = computed(() => {
  if (isNm.value) {
    const l = levels.value.length ? levels.value : NM_QUALITIES
    return l.map((k) => ({ k, label: qualityLabel(k) }))
  }
  return LOCAL_QUALITIES.map((k) => ({ k, label: qualityLabel(k) }))
})

const speedOptions = [0.5, 0.75, 1, 1.25, 1.5, 2]

async function playMv() {
  const s = cur.value
  if (!s?.mvId) return
  const url = await api.nmMvUrl(s.mvId).catch(() => null)
  if (!url) { toast("没有可播放的 MV"); return }
  st.videoUrl = url
  st.showVideo = true
}

function onSeekRatio(r: number) {
  if (st.durationMs > 0) seek(Math.round(r * st.durationMs))
}

const coverStyle = computed(() =>
  cur.value?.coverUrl ? `background-image:url(${cur.value.coverUrl})` : "",
)
</script>

<template>
  <div v-if="cur" class="fp">
    <!-- 顶栏 -->
    <div class="top">
      <button class="iconbtn close" title="关闭播放器" @click="st.showFull = false">✕</button>
      <div class="ctrls" :class="{ hide: !ctrlOpen }">
        <div class="row">
          <button v-if="cur.mvId" class="btn mv" title="播放 MV" @click="playMv">MV</button>
          <button class="iconbtn" title="下载"
                  :class="{ on: isDownloaded(cur.ssid) }"
                  @click="st.showFull = false">{{ isDownloaded(cur.ssid) ? "✓" : "⇩" }}</button>
          <button class="iconbtn" title="加入歌单" @click="showPick = true">＋</button>
          <button class="btn qbtn" title="音质" @click="showQuality = !showQuality">
            {{ qualityLabel(st.quality) }}
          </button>
          <button class="btn qbtn" title="倍速" @click="showSpeed = !showSpeed">
            {{ st.speed }}×
          </button>
          <span class="sep" />
          <button class="iconbtn" title="上一首" @click="prevSong">⏮</button>
          <button class="iconbtn play" :title="st.playing ? '暂停' : '播放'" @click="toggle">
            {{ st.playing ? "⏸" : "▶" }}
          </button>
          <button class="iconbtn" title="下一首" @click="nextSong(true)">⏭</button>
          <span class="sep" />
          <span class="time">{{ fmt(st.positionMs) }}</span>
          <div class="seek" @click="(e) => onSeekRatio(e.offsetX / (e.currentTarget as HTMLElement).clientWidth)">
            <div class="track"><i :style="{ width: (st.durationMs ? st.positionMs / st.durationMs * 100 : 0) + '%' }" /></div>
          </div>
          <span class="time">{{ fmt(st.durationMs) }}</span>
          <span class="sep" />
          <button class="iconbtn" title="播放列表" @click="showQueue = !showQueue">☰</button>
          <button class="iconbtn mode" :title="['列表循环', '随机播放', '单曲循环'][st.playMode]"
                  @click="st.playMode = (st.playMode + 1) % 3; api.setPref('playMode', String(st.playMode))">
            {{ ["↻", "⤨", "①"][st.playMode] }}
          </button>
        </div>
        <!-- 音质 / 倍速 / 歌单浮层 -->
        <div v-if="showQuality" class="pop">
          <div v-for="o in qualityOptions" :key="o.k" class="pop-item"
               :class="{ on: o.k === st.quality }" @click="setQuality(o.k); showQuality = false">
            {{ o.label }}
          </div>
        </div>
        <div v-if="showSpeed" class="pop">
          <div v-for="s in speedOptions" :key="s" class="pop-item" :class="{ on: s === st.speed }"
               @click="setSpeed(s); showSpeed = false">{{ s }}×</div>
        </div>
      </div>
      <button class="iconbtn arrow" :title="ctrlOpen ? '收起' : '展开'"
              @click="ctrlOpen = !ctrlOpen">{{ ctrlOpen ? "‹" : "⌄" }}</button>
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
          :lines="st.lyrics" :idx="lyricIndex" :loading="st.lyricLoading"
          :color="'var(--primary)'" :row-spacing="63" :radius-boost="0.7"
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
.close { font-size: 18px; }
.ctrls { flex: 1; min-width: 0; position: relative; }
.ctrls.hide .row { display: none; }
.ctrls .row {
  display: flex; align-items: center; gap: 6px; overflow-x: auto;
}
.sep { width: 1px; height: 20px; background: rgba(255, 255, 255, .12); flex: none; margin: 0 2px; }
.time { font-size: 12px; color: var(--muted); flex: none; font-variant-numeric: tabular-nums; }
.seek { flex: none; width: 160px; height: 24px; display: flex; align-items: center; cursor: pointer; }
.track { width: 100%; height: 4px; border-radius: 2px; background: rgba(255, 255, 255, .2); }
.track > i { display: block; height: 100%; background: #fff; border-radius: 2px; }
.play { background: var(--primary) !important; color: var(--on-primary) !important; }
.btn.qbtn { height: 28px; padding: 0 10px; font-size: 12px; }
.btn.mv { height: 28px; padding: 0 9px; font-size: 12px; background: var(--primary); color: var(--on-primary); }
.arrow { font-size: 18px; }
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
