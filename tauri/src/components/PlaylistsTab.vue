<script setup lang="ts">
/** 歌单页：新建 / 同步网易云 / 列表 */
import { ref } from "vue"
import {
  createPlaylist, deletePlaylist, playSongs, songOf, st, syncNeteasePlaylists, toast,
} from "../store"

const emit = defineEmits<{ (e: "open", id: string): void }>()
const creating = ref(false)
const name = ref("")
const syncing = ref(false)

async function doCreate() {
  if (!name.value.trim()) return
  await createPlaylist(name.value.trim())
  name.value = ""
  creating.value = false
}

async function doSync() {
  syncing.value = true
  try { await syncNeteasePlaylists() } finally { syncing.value = false }
}

function songsOf(p: { songs: string[] }) {
  return p.songs.map((id) => songOf(id)).filter(Boolean) as any[]
}
</script>

<template>
  <div class="pl">
    <div class="bar">
      <button class="btn primary" @click="creating = true">新建歌单</button>
      <button class="btn" :disabled="syncing" @click="doSync">
        {{ syncing ? "同步中…" : "同步网易云" }}
      </button>
    </div>
    <div v-if="creating" class="newrow">
      <input v-model="name" class="field" placeholder="歌单名" @keyup.enter="doCreate" />
      <button class="btn primary" @click="doCreate">创建</button>
      <button class="btn ghost" @click="creating = false">取消</button>
    </div>
    <div v-if="!st.playlists.length" class="empty muted">还没有歌单</div>
    <div v-for="p in st.playlists" :key="p.id" class="card" @click="emit('open', p.id)">
      <div class="cv" :style="p.cover ? `background-image:url(${p.cover})` : ''" />
      <div class="meta">
        <div class="t ellip">{{ p.name }}</div>
        <div class="a muted">{{ p.songs.length }} 首</div>
      </div>
      <button class="iconbtn" title="播放全部" @click.stop="playSongs(songsOf(p), 0)">▶</button>
      <button class="iconbtn" title="删除歌单"
              @click.stop="deletePlaylist(p.id); toast('已删除歌单')">🗑</button>
    </div>
  </div>
</template>

<style scoped>
.pl { height: 100%; overflow: auto; padding: 4px 16px 20px; }
.bar { display: flex; gap: 8px; padding: 6px 0 12px; }
.newrow { display: flex; gap: 8px; align-items: center; padding-bottom: 12px; }
.newrow .field { max-width: 260px; }
.empty { padding: 30px; text-align: center; }
.card {
  display: flex; align-items: center; gap: 12px; padding: 8px; border-radius: 12px;
  cursor: pointer;
}
.card:hover { background: var(--surface-hi); }
.cv {
  width: 52px; height: 52px; border-radius: 10px; flex: none; background-size: cover;
  background-position: center; background-color: var(--bg);
}
.meta { flex: 1; min-width: 0; }
.t { font-size: 14px; font-weight: 600; }
.a { font-size: 12px; }
</style>
