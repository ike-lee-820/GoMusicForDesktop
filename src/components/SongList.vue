<script setup lang="ts">
/**
 * 歌曲列表：虚拟滚动。
 *
 * 之前是「分批渲染 80 行」，滚得越多 DOM 越多（几千首就是几千个节点，
 * 每个还带封面背景图），滚到底时必然卡。现在只渲染可视区域内的十几行，
 * 上下留一点 overscan，总高度用占位撑出来，滚动条长度仍然正确。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue"
import Icon from "./Icon.vue"
import { addToQueue, download, isDownloaded, playSongs, st } from "../store"
import type { Song } from "../types"

const props = defineProps<{ list: Song[] }>()
const emit = defineEmits<{ (e: "pick", s: Song): void; (e: "reachEnd"): void }>()

/** 行高固定，虚拟化才能用算式定位 */
const ITEM_H = 62
/** 上下各多渲染几行，避免快速滚动出现空白 */
const OVERSCAN = 4

const el = ref<HTMLElement | null>(null)
const scrollTop = ref(0)
const viewH = ref(600)

let ro: ResizeObserver | null = null
onMounted(() => {
  if (!el.value) return
  viewH.value = el.value.clientHeight || 600
  ro = new ResizeObserver(() => { viewH.value = el.value?.clientHeight || viewH.value })
  ro.observe(el.value)
})
onBeforeUnmount(() => ro?.disconnect())

watch(() => props.list, () => { scrollTop.value = 0; el.value?.scrollTo({ top: 0 }) })

const start = computed(() => Math.max(0, Math.floor(scrollTop.value / ITEM_H) - OVERSCAN))
const count = computed(() => Math.ceil(viewH.value / ITEM_H) + OVERSCAN * 2 + 2)
const visible = computed(() => props.list.slice(start.value, start.value + count.value))
const padTop = computed(() => start.value * ITEM_H)
const totalH = computed(() => props.list.length * ITEM_H)

function onScroll() {
  const e = el.value
  if (!e) return
  scrollTop.value = e.scrollTop
  // 滚到底部附近：通知外层去拉下一页（网易云分页）
  if (e.scrollTop + e.clientHeight >= e.scrollHeight - 300) emit("reachEnd")
}
</script>

<template>
  <div ref="el" class="list" @scroll="onScroll">
    <div class="spacer" :style="{ height: totalH + 'px' }">
      <div v-for="(s, i) in visible" :key="s.ssid + (start + i)" class="row"
           :class="{ on: s.ssid === st.cur?.ssid }"
           :style="{ transform: `translateY(${(start + i) * ITEM_H}px)` }"
           @click="playSongs(props.list, start + i)">
        <div class="cv" :style="s.coverUrl ? `background-image:url(${s.coverUrl})` : ''" />
        <div class="meta">
          <div class="t ellip">{{ s.title }}</div>
          <div class="a ellip muted">{{ s.artist }} · {{ s.album }}</div>
        </div>
        <button class="iconbtn" :class="{ on: isDownloaded(s.ssid) }" title="下载"
                @click.stop="download([s])">
          <Icon :name="isDownloaded(s.ssid) ? 'check_circle' : 'download'" :size="20" />
        </button>
        <button class="iconbtn" title="加入播放列表" @click.stop="addToQueue(s)">
          <Icon name="queue_music" :size="20" />
        </button>
        <button class="iconbtn" title="加入歌单" @click.stop="emit('pick', s)">
          <Icon name="playlist_add" :size="20" />
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.list { height: 100%; overflow: auto; padding-bottom: 12px; }
/* 占位层撑出滚动高度，行绝对定位到算出来的位置 */
.spacer { position: relative; width: 100%; }
.row {
  position: absolute; left: 0; right: 0; top: 0; height: 62px;
  display: flex; align-items: center; gap: 12px; padding: 0 12px 0 16px;
  border-radius: 10px; cursor: pointer; will-change: transform;
}
.row:hover { background: var(--surface-hi); }
.row.on { background: rgba(208, 188, 255, .12); }
.row.on .t { color: var(--primary); }
.cv {
  width: 46px; height: 46px; border-radius: 8px; flex: none;
  background-size: cover; background-position: center; background-color: var(--bg);
}
.meta { flex: 1; min-width: 0; }
.t { font-size: 14px; font-weight: 500; }
.a { font-size: 12px; }
</style>
