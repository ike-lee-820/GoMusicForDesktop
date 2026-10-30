<script setup lang="ts">
import { computed, ref, watch } from "vue"
import Icon from "./Icon.vue"
import { historySongs, playSongs, st } from "../store"
import { api } from "../api"

const STEP = 100
const limit = ref(STEP)
const all = computed(() => historySongs.value)
const list = computed(() => all.value.slice(0, limit.value))
watch(all, () => { limit.value = STEP })

function onScroll(e: Event) {
  const el = e.target as HTMLElement
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 300 && limit.value < all.value.length) {
    limit.value += STEP
  }
}

async function remove(ssid: string) {
  st.history = await api.removeHistory(ssid).catch(() => st.history)
}
async function clearAll() {
  st.history = await api.clearHistory().catch(() => st.history)
}
</script>

<template>
  <div class="overlay" @click="st.showHistory = false">
    <div class="dialog box" @click.stop>
      <div class="head">
        <span class="tt">播放历史</span>
        <button v-if="list.length" class="btn ghost" @click="clearAll">清空</button>
        <button class="iconbtn" title="关闭" @click="st.showHistory = false"><Icon name="close" :size="20" /></button>
      </div>
      <div v-if="!list.length" class="empty muted">还没有播放记录</div>
      <div v-else class="body" @scroll="onScroll">
        <div v-for="(s, i) in list" :key="s.ssid + i" class="row" @click="playSongs(list, i)">
          <span class="idx">{{ i + 1 }}</span>
          <div class="cv" :style="s.coverUrl ? `background-image:url(${s.coverUrl})` : ''" />
          <div class="meta">
            <div class="t ellip">{{ s.title }}</div>
            <div class="a ellip muted">{{ s.artist }}</div>
          </div>
          <button class="iconbtn" title="删除这条记录" @click.stop="remove(s.ssid)"><Icon name="delete" :size="20" /></button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.box { width: 620px; height: 70vh; }
.head { display: flex; align-items: center; gap: 8px; padding: 12px 16px; }
.tt { flex: 1; font-size: 16px; font-weight: 700; }
.body { flex: 1; overflow: auto; padding: 0 8px 12px; }
.empty { flex: 1; display: flex; align-items: center; justify-content: center; }
.row { display: flex; align-items: center; gap: 12px; height: 60px; padding: 0 8px; border-radius: 10px; cursor: pointer; }
.row:hover { background: var(--surface-hi); }
.idx { width: 26px; color: var(--muted); font-size: 12px; }
.cv { width: 42px; height: 42px; border-radius: 8px; flex: none; background-size: cover; background-position: center; background-color: var(--bg); }
.meta { flex: 1; min-width: 0; }
.t { font-size: 14px; }
.a { font-size: 12px; }
</style>
