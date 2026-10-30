<script setup lang="ts">
/** MV 播放：独立 video 元素，与后台音频互不干扰 */
import { st } from "../store"
import Icon from "./Icon.vue"
</script>

<template>
  <div class="mv">
    <div class="top">
      <button class="iconbtn" title="关闭" @click="st.showVideo = false"><Icon name="close" :size="20" /></button>
      <span class="t ellip">{{ st.cur?.title }} · MV</span>
    </div>
    <video :src="st.videoUrl" controls autoplay class="v" />
  </div>
</template>

<style scoped>
.mv { position: fixed; inset: 0; z-index: 120; background: #000; display: flex; flex-direction: column; }
.top { display: flex; align-items: center; gap: 10px; padding: 10px 14px; }
.t { font-size: 15px; font-weight: 700; }
.v { flex: 1; width: 100%; background: #000; }
</style>
