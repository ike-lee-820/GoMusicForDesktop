<script setup lang="ts">
/** 歌曲列表：分批渲染，滚到底再加载下一批。
 *  曲库几千首时一次性渲染所有行会卡死界面（手机端是 LazyColumn，本身就是虚拟的）。 */
import { onMounted, ref, watch } from "vue"
import { download, isDownloaded, playSongs, st, addToQueue } from "../store"
import type { Song } from "../types"

const props = defineProps<{ list: Song[] }>()
const emit = defineEmits<{ (e: "pick", s: Song): void; (e: "reachEnd"): void }>()

const STEP = 80
const limit = ref(STEP)
const el = ref<HTMLElement | null>(null)

watch(() => props.list, () => { limit.value = STEP })

function onScroll() {
  const e = el.value
  if (!e) return
  if (e.scrollTop + e.clientHeight >= e.scrollHeight - 300) {
    if (limit.value < props.list.length) limit.value += STEP
    else emit("reachEnd")
  }
}
onMounted(() => { limit.value = STEP })
</script>

<template>
  <div ref="el" class="list" @scroll="onScroll">
    <div v-for="(s, i) in props.list.slice(0, limit)" :key="s.ssid + i" class="row"
         :class="{ on: s.ssid === st.cur?.ssid }" @click="playSongs(props.list, i)">
      <div class="cv" :style="s.coverUrl ? `background-image:url(${s.coverUrl})` : ''" />
      <div class="meta">
        <div class="t ellip">{{ s.title }}</div>
        <div class="a ellip muted">{{ s.artist }} · {{ s.album }}</div>
      </div>
      <button class="iconbtn" :class="{ on: isDownloaded(s.ssid) }" title="下载"
              @click.stop="download([s])">{{ isDownloaded(s.ssid) ? "✓" : "⇩" }}</button>
      <button class="iconbtn" title="加入播放列表" @click.stop="addToQueue(s)">☰</button>
      <button class="iconbtn" title="加入歌单" @click.stop="emit('pick', s)">＋</button>
    </div>
    <div v-if="limit < props.list.length" class="more muted">滚动加载更多…</div>
  </div>
</template>

<style scoped>
.list { height: 100%; overflow: auto; padding-bottom: 12px; }
.row {
  display: flex; align-items: center; gap: 12px; height: 62px; padding: 0 12px 0 16px;
  border-radius: 10px; cursor: pointer;
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
.more { text-align: center; padding: 12px; font-size: 12px; }
</style>
