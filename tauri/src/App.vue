<script setup lang="ts">
// 骨架版：先把「加载曲库 -> 列表 -> 播放」跑通，界面细节随后补齐
import { onMounted, ref } from "vue"
import { api } from "./api"
import type { PlayerStatus, Song } from "./types"

const songs = ref<Song[]>([])
const status = ref<PlayerStatus | null>(null)
const loading = ref(true)
const offline = ref(false)
const cur = ref<Song | null>(null)

async function reload() {
  const lib = await api.loadLibrary()
  songs.value = lib.songs
  offline.value = lib.offline
  loading.value = false
}

async function play(s: Song) {
  cur.value = s
  status.value = await api.playSong(s, "normal")
}

onMounted(async () => {
  await reload()
  setInterval(async () => { status.value = await api.status() }, 300)
})
</script>

<template>
  <div class="wrap">
    <div class="list">
      <div v-if="loading">加载中…</div>
      <div v-else-if="offline" class="offline">离线模式：只显示已下载内容</div>
      <div
        v-for="s in songs" :key="s.ssid" class="row"
        :class="{ on: cur?.ssid === s.ssid }" @click="play(s)"
      >
        <span class="t">{{ s.title }}</span>
        <span class="a">{{ s.artist }}</span>
      </div>
    </div>
    <div class="right">
      <div v-if="cur" class="cur">
        <div class="title">{{ cur.title }}</div>
        <div class="sub">{{ cur.artist }}</div>
      </div>
      <div v-if="status" class="pos">
        {{ Math.floor(status.positionMs / 1000) }}s / {{ Math.floor(status.durationMs / 1000) }}s
      </div>
    </div>
  </div>
</template>

<style scoped>
.wrap { display: flex; height: 100%; }
.list { flex: 1; overflow: auto; padding: 8px; }
.row { display: flex; gap: 12px; padding: 6px 8px; border-radius: 6px; cursor: pointer; }
.row:hover { background: var(--surface-hi); }
.row.on { color: var(--primary); }
.a { color: var(--muted); }
.right { width: 300px; border-left: 1px solid var(--surface-hi); padding: 16px; }
.offline { color: var(--muted); padding: 8px; }
.cur .title { font-size: 18px; font-weight: 600; }
.cur .sub { color: var(--muted); }
.pos { margin-top: 12px; color: var(--muted); }
</style>
