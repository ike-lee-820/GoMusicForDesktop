<script setup lang="ts">
/** 底部悬浮正在播放栏：点整条进入横版全屏播放器 */
import { computed } from "vue"
import Icon from "./Icon.vue"
import { fmt, nextSong, prevSong, st, toggle } from "../store"

const cur = computed(() => st.cur)
const ratio = computed(() => st.durationMs ? Math.min(1, st.positionMs / st.durationMs) : 0)
</script>

<template>
  <div v-if="cur" class="np" @click="st.showFull = true">
    <div class="cover" :style="cur.coverUrl ? `background-image:url(${cur.coverUrl})` : ''" />
    <div class="info">
      <div class="t ellip">{{ cur.title }}</div>
      <div class="a ellip muted">{{ cur.artist }} · {{ cur.album }}</div>
    </div>
    <div class="ctl" @click.stop>
      <button class="iconbtn" title="上一首" @click="prevSong"><Icon name="skip_previous" :size="20" /></button>
      <button class="iconbtn play" :title="st.playing ? '暂停' : '播放'" @click="toggle">
        <Icon :name="st.playing ? 'pause' : 'play_arrow'" :size="20" />
      </button>
      <button class="iconbtn" title="下一首" @click="nextSong(true)"><Icon name="skip_next" :size="20" /></button>
    </div>
    <div class="prog">
      <span class="time">{{ fmt(st.positionMs) }}</span>
      <div class="track"><i :style="{ width: ratio * 100 + '%' }" /></div>
      <span class="time">{{ fmt(st.durationMs) }}</span>
    </div>
  </div>
</template>

<style scoped>
.np {
  flex: none; display: flex; align-items: center; gap: 12px; padding: 8px 16px;
  background: var(--surface); border-top: 1px solid var(--surface-hi); cursor: pointer;
}
.np:hover { background: var(--surface-hi); }
.cover {
  width: 44px; height: 44px; border-radius: 8px; flex: none;
  background-size: cover; background-position: center; background-color: var(--bg);
}
.info { width: 240px; min-width: 0; flex: none; }
.t { font-size: 14px; font-weight: 600; }
.a { font-size: 12px; }
.ctl { display: flex; align-items: center; gap: 2px; flex: none; }
.play { background: var(--primary); color: var(--on-primary); }
.prog { flex: 1; display: flex; align-items: center; gap: 10px; min-width: 0; }
.track { flex: 1; height: 4px; border-radius: 2px; background: var(--surface-hi); }
.track > i { display: block; height: 100%; background: var(--primary); border-radius: 2px; }
.time { font-size: 12px; color: var(--muted); font-variant-numeric: tabular-nums; }
</style>
