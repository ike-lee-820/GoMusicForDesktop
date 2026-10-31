<script setup lang="ts">
/**
 * MV 播放：独立 video 元素，与后台音频互不干扰。
 *
 * 比例：video 铺满容器后用 object-fit: contain，画面按真实宽高比居中、
 * 两侧留黑边。之前是 width:100% + flex:1 直接拉伸，比例就失真了。
 *
 * 倍速只作用于这个 video，和音频播放的倍速分开（两者是不同的播放实例）。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue"
import Icon from "./Icon.vue"
import { api } from "../api"
import { fmt, st, toast } from "../store"

const el = ref<HTMLVideoElement | null>(null)
const playing = ref(true)
const pos = ref(0)
const dur = ref(0)
const speed = ref(1)
const speedOpen = ref(false)
const downloading = ref(false)
const progress = ref(-1)

const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 2]

function onTime() {
  const v = el.value
  if (!v) return
  pos.value = v.currentTime * 1000
  dur.value = (Number.isFinite(v.duration) ? v.duration : 0) * 1000
}

function toggle() {
  const v = el.value
  if (!v) return
  if (v.paused) { v.play(); playing.value = true } else { v.pause(); playing.value = false }
}

function setSpeed(s: number) {
  speed.value = s
  speedOpen.value = false
  if (el.value) el.value.playbackRate = s
}

function seek(e: MouseEvent) {
  const t = e.currentTarget as HTMLElement
  const v = el.value
  if (!v || dur.value <= 0) return
  v.currentTime = ((e.offsetX / t.clientWidth) * dur.value) / 1000
}

async function downloadMv() {
  if (downloading.value || !st.videoUrl) return
  downloading.value = true
  progress.value = 0
  try {
    await api.downloadMv(st.cur?.title || "MV", st.videoUrl)
    toast("MV 已保存到下载目录的 MV 子目录")
  } catch {
    toast("MV 下载失败")
  } finally {
    downloading.value = false
    progress.value = -1
  }
}

// 对 video 元素本身请求全屏：浏览器会按视频的真实宽高比铺满屏幕，
// 竖屏 MV 不会像「对容器全屏」那样被压成中间一条
function fullscreen() {
  const v = el.value
  if (!v) return
  if (document.fullscreenElement) document.exitFullscreen()
  else v.requestFullscreen().catch(() => {})
}

// 下载进度：复用歌曲下载那套全局进度槽
let timer: any = null
onMounted(() => {
  timer = setInterval(async () => {
    if (!downloading.value) return
    const s = await api.downloadStatus().catch(() => null)
    progress.value = s && s.size > 0 ? s.done / s.size : -1
  }, 400)
})
onBeforeUnmount(() => { if (timer) clearInterval(timer) })

watch(() => st.videoUrl, () => {
  speed.value = 1
  playing.value = true
  pos.value = 0
  dur.value = 0
})

const ratio = computed(() => (dur.value > 0 ? Math.min(1, pos.value / dur.value) : 0))
</script>

<template>
  <div class="mv">
    <div class="top">
      <button class="iconbtn" title="关闭" @click="st.showVideo = false"><Icon name="close" :size="20" /></button>
      <span class="t ellip">{{ st.cur?.title }} · MV</span>
      <span v-if="downloading" class="dl">
        {{ progress >= 0 ? `下载中 ${Math.round(progress * 100)}%` : "下载中…" }}
      </span>
      <button class="iconbtn" title="下载 MV" @click="downloadMv"><Icon name="download" :size="20" /></button>
    </div>

    <div class="stage">
      <video
        ref="el" :src="st.videoUrl" autoplay playsinline class="v"
        @timeupdate="onTime" @loadedmetadata="onTime" @click="toggle"
        @ended="playing = false"
      />
    </div>

    <div class="bottom">
      <button class="iconbtn" :title="playing ? '暂停' : '播放'" @click="toggle">
        <Icon :name="playing ? 'pause' : 'play_arrow'" :size="20" />
      </button>
      <span class="time">{{ fmt(pos) }}</span>
      <div class="seek" @click="seek">
        <div class="track"><i :style="{ width: ratio * 100 + '%' }" /></div>
      </div>
      <span class="time">{{ fmt(dur) }}</span>
      <div class="spd">
        <button class="btn qbtn" title="倍速" @click="speedOpen = !speedOpen">
          <Icon name="speed" :size="16" />{{ speed }}×
        </button>
        <div v-if="speedOpen" class="pop">
          <div v-for="s in SPEEDS" :key="s" class="pop-item" :class="{ on: s === speed }" @click="setSpeed(s)">
            {{ s === 1 ? "正常" : `${s}×` }}
          </div>
        </div>
      </div>
      <button class="iconbtn" title="全屏" @click="fullscreen"><Icon name="fullscreen" :size="20" /></button>
    </div>
  </div>
</template>

<style scoped>
.mv { position: fixed; inset: 0; z-index: 120; background: #000; display: flex; flex-direction: column; }
.top { display: flex; align-items: center; gap: 10px; padding: 10px 14px; flex: none; }
.t { font-size: 15px; font-weight: 700; flex: 1; min-width: 0; }
.dl { font-size: 12px; color: var(--muted); }
.stage { flex: 1; min-height: 0; display: flex; }
/* 关键：铺满容器但按真实比例显示，不再拉伸变形 */
.v { width: 100%; height: 100%; object-fit: contain; background: #000; }
.bottom {
  display: flex; align-items: center; gap: 8px; padding: 8px 14px; flex: none;
  background: rgba(0, 0, 0, .5);
}
.time { font-size: 12px; color: var(--muted); font-variant-numeric: tabular-nums; flex: none; }
.seek { flex: 1; height: 24px; display: flex; align-items: center; cursor: pointer; }
.track { width: 100%; height: 4px; border-radius: 2px; background: rgba(255, 255, 255, .25); }
.track > i { display: block; height: 100%; background: #fff; border-radius: 2px; }
.spd { position: relative; flex: none; }
.btn.qbtn { height: 28px; padding: 0 10px; font-size: 12px; gap: 4px; }
.pop {
  position: absolute; bottom: 36px; right: 0; z-index: 5; min-width: 110px;
  background: var(--surface); border-radius: 12px; padding: 6px;
  box-shadow: 0 10px 30px rgba(0, 0, 0, .6);
}
.pop-item { padding: 7px 12px; border-radius: 8px; cursor: pointer; font-size: 13px; }
.pop-item:hover { background: var(--surface-hi); }
.pop-item.on { color: var(--primary); }
</style>
