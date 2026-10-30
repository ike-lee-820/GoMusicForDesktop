<script setup lang="ts">
/** 专辑 / 歌手卡片网格：点击进入详情，长按右键或按钮直接播放 */
import type { Group } from "../types"
import Icon from "./Icon.vue"
import { playSongs } from "../store"

const props = withDefaults(defineProps<{ groups: Group[]; wide?: boolean }>(), { wide: false })
const emit = defineEmits<{ (e: "open", g: Group): void }>()
</script>

<template>
  <div class="wrap">
    <div v-for="(g, i) in props.groups" :key="(g.nmId || g.title) + i" class="cell"
         :style="{ width: props.wide ? '170px' : '150px' }">
      <div class="cv" :style="g.cover ? `background-image:url(${g.cover})` : ''"
           @click="emit('open', g)" />
      <div class="t ellip" @click="emit('open', g)">{{ g.title }}</div>
      <div class="s ellip muted">{{ g.subtitle || g.artist }}</div>
      <button v-if="g.songs.length" class="mini" title="播放全部"
              @click="playSongs(g.songs, 0)"><Icon name="play_arrow" :size="14" /></button>
    </div>
  </div>
</template>

<style scoped>
.wrap { display: flex; flex-wrap: wrap; gap: 14px; padding: 4px 16px 16px; }
.cell {
  position: relative;
  content-visibility: auto; contain-intrinsic-size: 150px 200px;
}
.cv {
  width: 100%; aspect-ratio: 1; border-radius: 10px; background-size: cover;
  background-position: center; background-color: var(--surface-hi); cursor: pointer;
}
.t { font-size: 13px; font-weight: 600; margin-top: 6px; cursor: pointer; }
.s { font-size: 12px; }
.mini {
  position: absolute; right: 8px; top: 8px; width: 28px; height: 28px; border-radius: 50%;
  border: none; background: rgba(0, 0, 0, .55); color: #fff; cursor: pointer; font-size: 12px;
}
.mini:hover { background: var(--primary); color: var(--on-primary); }
</style>
