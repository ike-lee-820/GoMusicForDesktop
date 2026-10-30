<script setup lang="ts">
/** 二级页：本地专辑 / 歌手 / 歌单 / 网易云公开歌单 */
import { computed, onMounted, ref, watch } from "vue"
import SongList from "./SongList.vue"
import { api } from "../api"
import {
  addToPlaylist, download, playSongs, songOf, st, toast,
} from "../store"
import type { Group, Page, Song } from "../types"

const props = defineProps<{ page: Page }>()
const emit = defineEmits<{ (e: "back"): void }>()

const songs = ref<Song[]>([])
const cover = ref<string | null>(null)
const sub = ref("")
const loading = ref(false)
const nmId = ref(0)
const nmKind = ref("")
const plId = ref("")

async function load() {
  const p = props.page
  loading.value = true
  songs.value = []
  cover.value = null
  nmId.value = 0
  plId.value = ""
  if (p.kind === "album") {
    songs.value = st.songs.filter((s) => s.album === p.name && (!p.artist || s.artist === p.artist))
    cover.value = songs.value[0]?.coverUrl ?? null
    sub.value = p.artist
  } else if (p.kind === "artist") {
    songs.value = st.songs.filter((s) => s.artist === p.name)
    cover.value = songs.value[0]?.coverUrl ?? null
    sub.value = `${songs.value.length} 首`
  } else if (p.kind === "playlist") {
    plId.value = p.id
    const pl = st.playlists.find((x) => x.id === p.id)
    songs.value = (pl?.songs ?? []).map((id) => songOf(id)).filter(Boolean) as Song[]
    cover.value = pl?.cover ?? songs.value[0]?.coverUrl ?? null
    sub.value = `${songs.value.length} 首`
  } else {
    nmId.value = p.id
    nmKind.value = p.kind2
    const list = await api.nmSongsOf(p.kind2, p.id, 200, 0).catch(() => [])
    songs.value = list
    cover.value = null
    sub.value = `${list.length} 首`
  }
  loading.value = false
}
onMounted(load)
watch(() => props.page, load)

const title = computed(() => {
  const p = props.page
  if (p.kind === "album") return p.name
  if (p.kind === "artist") return p.name
  if (p.kind === "playlist") return st.playlists.find((x) => x.id === p.id)?.name ?? "歌单"
  return p.title
})

async function downloadAll() {
  if (!songs.value.length) return
  await download(songs.value)
}

function addAllTo(id: string) {
  addToPlaylist(id, songs.value.map((s) => s.ssid))
  toast("已加入歌单")
}

const pickFor = ref<Song | null>(null)
</script>

<template>
  <div class="detail">
    <div class="head">
      <button class="iconbtn" @click="emit('back')">←</button>
      <div class="cv" :style="cover ? `background-image:url(${cover})` : ''" />
      <div class="meta">
        <div class="t ellip">{{ title }}</div>
        <div class="s muted">{{ sub }}</div>
        <div class="acts">
          <button class="btn primary" :disabled="!songs.length" @click="playSongs(songs, 0)">播放全部</button>
          <button class="btn" :disabled="!songs.length" @click="downloadAll">下载全部</button>
          <button class="btn" :disabled="!songs.length" @click="st.showHistory = false; pickFor = songs[0]">加入歌单</button>
        </div>
      </div>
    </div>
    <div v-if="loading" class="loading"><span class="spinner" /></div>
    <div v-else class="body">
      <SongList :list="songs" :on-pick="(s) => (pickFor = s)" />
    </div>
    <div v-if="pickFor" class="overlay" @click="pickFor = null">
      <div class="dialog pick" @click.stop>
        <div class="ph">加入歌单</div>
        <div v-if="!st.playlists.length" class="pi muted">还没有歌单</div>
        <div v-for="p in st.playlists" :key="p.id" class="pi"
             @click="addToPlaylist(p.id, pickFor ? [pickFor.ssid] : []); pickFor = null">{{ p.name }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.detail { height: 100%; display: flex; flex-direction: column; }
.head { display: flex; gap: 14px; padding: 4px 16px 12px; align-items: flex-end; }
.cv {
  width: 96px; height: 96px; border-radius: 12px; flex: none; background-size: cover;
  background-position: center; background-color: var(--surface-hi);
}
.meta { min-width: 0; flex: 1; }
.t { font-size: 20px; font-weight: 800; }
.s { font-size: 12px; margin: 2px 0 8px; }
.acts { display: flex; gap: 8px; flex-wrap: wrap; }
.body { flex: 1; min-height: 0; }
.loading { flex: 1; display: flex; align-items: center; justify-content: center; }
.pick { width: 320px; padding: 6px; }
.ph { padding: 10px 12px; font-weight: 700; }
.pi { padding: 10px 12px; border-radius: 8px; cursor: pointer; }
.pi:hover { background: var(--surface-hi); }
</style>
