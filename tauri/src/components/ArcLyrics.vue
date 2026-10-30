<script setup lang="ts">
/**
 * 圆弧歌词：每行落在一个真正的圆上，位置与倾斜角由同一个圆心角算出。
 * 外框与文字共用同一个 rotate，所以两者始终平行、不会被斜切；
 * 宽高都不设上限，长句折行也完整显示，不会被裁掉。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue"
import type { LrcLine } from "../types"
import { zhText } from "../zh"

const props = withDefaults(defineProps<{
  lines: LrcLine[]
  idx: number
  loading?: boolean
  color?: string
  rowSpacing?: number
  radiusBoost?: number
  minSpan?: number
  curSp?: number
  otherSp?: number
}>(), {
  loading: false, color: "#d0bcff", rowSpacing: 63, radiusBoost: 0.7,
  minSpan: 2, curSp: 21, otherSp: 16,
})
const emit = defineEmits<{ (e: "seek", ms: number): void }>()

const PI_F = Math.PI
const MAX_TILT_DEG = 20
const DRAG_SENSITIVITY = 2.5

const box = ref<HTMLElement | null>(null)
const height = ref(400)
const width = ref(500)

let ro: ResizeObserver | null = null
onMounted(() => {
  if (!box.value) return
  ro = new ResizeObserver(() => {
    height.value = box.value!.clientHeight
    width.value = box.value!.clientWidth
  })
  ro.observe(box.value)
  height.value = box.value.clientHeight
  width.value = box.value.clientWidth
})
onBeforeUnmount(() => ro?.disconnect())

// 当前句位置（动画到 idx）
const cur = ref(props.idx)
let raf = 0
watch(() => props.idx, (to, from) => {
  cancelAnimationFrame(raf)
  const t0 = performance.now(), dur = 400, a = cur.value, b = to
  const step = (t: number) => {
    const k = Math.min(1, (t - t0) / dur)
    cur.value = a + (b - a) * k
    if (k < 1) raf = requestAnimationFrame(step)
  }
  raf = requestAnimationFrame(step)
  void from
})

// 手动翻看偏移 + 停手 3 秒回位
const dragOff = ref(0)
let manualTimer: any = null
function noteManual() {
  clearTimeout(manualTimer)
  manualTimer = setTimeout(() => { dragOff.value = 0 }, 3000)
}
let dragging = false
let lastY = 0
function onDown(e: PointerEvent) { dragging = true; lastY = e.clientY }
function onMove(e: PointerEvent) {
  if (!dragging) return
  const dy = e.clientY - lastY
  lastY = e.clientY
  if (Math.abs(dy) > 0.5) { dragOff.value -= dy / (props.rowSpacing / DRAG_SENSITIVITY); noteManual() }
}
function onUp() { dragging = false }

const geom = computed(() => {
  const rows = Math.min(15, Math.max(3, Math.floor(height.value / props.rowSpacing)))
  const span = Math.max(Math.floor(rows / 2), props.minSpan)
  const tilt = (MAX_TILT_DEG * PI_F) / 180
  const r = Math.max((span * props.rowSpacing) / tilt * props.radiusBoost, height.value * 0.5)
  const step = props.rowSpacing / r
  return { span, r, step, shift: (r * (1 - Math.cos(span * step))) / 2 }
})

const visible = computed(() => {
  const g = geom.value
  const c = cur.value + dragOff.value
  const edge = g.span + 1
  const from = Math.max(0, Math.floor(c - edge))
  const to = Math.min(props.lines.length - 1, Math.ceil(c + edge))
  const out: {
    i: number; x: number; y: number; deg: number; alpha: number; isCur: boolean; text: string
  }[] = []
  for (let i = from; i <= to; i++) {
    const d = i - c
    const th = d * g.step
    const t = Math.min(1, Math.abs(d) / g.span)
    const fade = 1 - t * t * (3 - 2 * t) // smoothstep：边缘值与斜率都为 0
    out.push({
      i,
      x: -g.r * (1 - Math.cos(th)) + g.shift,
      y: g.r * Math.sin(th),
      deg: (th * 180) / PI_F,
      alpha: fade,
      isCur: i === props.idx,
      text: zhText(props.lines[i].text, undefined as any) || props.lines[i].text,
    })
  }
  return out
})

function onLineClick(l: LrcLine) {
  if (l.timeMs < 0) return
  dragOff.value = 0
  clearTimeout(manualTimer)
  emit("seek", l.timeMs)
}
</script>

<template>
  <div ref="box" class="arc" @pointerdown="onDown" @pointermove="onMove"
       @pointerup="onUp" @pointerleave="onUp" @pointercancel="onUp">
    <template v-if="lines.length">
      <div
        v-for="l in visible" :key="l.i"
        class="line" :class="{ cur: l.isCur }"
        :style="{
          transform: `translate(-50%,-50%) translate(${l.x}px, ${l.y}px) rotate(${l.deg}deg)`,
          opacity: l.alpha,
          fontSize: (l.isCur ? curSp : otherSp) + 'px',
          color: l.isCur ? color : 'rgba(255,255,255,.55)',
          fontWeight: l.isCur ? 700 : 500,
          maxWidth: Math.max(160, width - 24) + 'px',
        }"
        @click="onLineClick(lines[l.i])"
      >{{ l.text }}</div>
    </template>
    <div v-else class="empty">
      <template v-if="loading">
        <span class="mini-spin" /> 正在加载歌词…
      </template>
      <template v-else>暂无歌词</template>
    </div>
  </div>
</template>

<style scoped>
.arc {
  position: relative; width: 100%; height: 100%; overflow: hidden;
  touch-action: none;
}
.line {
  position: absolute; left: 50%; top: 50%;
  width: fit-content; text-align: left; cursor: pointer;
  line-height: 1.45; word-break: break-word; white-space: normal;
  padding: 3px 6px; transform-origin: center center;
  /* 不做 overflow 裁剪：长句折行完整显示 */
}
.line:hover { text-decoration: underline dotted rgba(255, 255, 255, .3); }
.empty {
  position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%);
  color: rgba(255, 255, 255, .55); font-size: 14px; display: flex;
  align-items: center; gap: 8px;
}
.mini-spin {
  width: 13px; height: 13px; border-radius: 50%; display: inline-block;
  border: 2px solid rgba(255, 255, 255, .25); border-top-color: #fff;
  animation: spin .9s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
