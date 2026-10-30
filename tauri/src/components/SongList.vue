<script setup lang="ts">
import { download, isDownloaded, playSongs, st, addToQueue } from "../store"
import type { Song } from "../types"

const props = defineProps<{ list: Song[]; onPick: (s: Song) => void }>()
defineEmits<{ (e: "reachEnd"): void }>()
</script>

<template>
  <div class="list">
    <div v-for="(s, i) in props.list" :key="s.ssid + i" class="row"
         :class="{ on: s.ssid === st.cur?.ssid }" @click="playSongs(props.list, i)">
      <div class="cv" :style="s.coverUrl ? `background-image:url(${s.coverUrl})` : ''" />
      <div class="meta">
        <div class="t ellip">{{ s.title }}</div>
        <div class="a ellip muted">{{ s.artist }} · {{ s.album }}</div>
      </div>
      <button class="iconbtn" :class="{ on: isDownloaded(s.ssid) }" title="下载"
              @click.stop="download([s])">{{ isDownloaded(s.ssid) ? "✓" : "⇩" }}</button>
      <button class="iconbtn" title="加入播放列表" @click.stop="addToQueue(s)">☰</button>
      <button class="iconbtn" title="加入歌单" @click.stop="props.onPick(s)">＋</button>
    </div>
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
</style>
