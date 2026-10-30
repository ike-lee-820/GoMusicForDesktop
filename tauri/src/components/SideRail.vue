<script setup lang="ts">
import { st } from "../store"

const items = [
  { t: 0, name: "首页", icon: "⌂" },
  { t: 1, name: "歌曲", icon: "♫" },
  { t: 2, name: "专辑", icon: "▤" },
  { t: 3, name: "歌手", icon: "☺" },
  { t: 4, name: "歌单", icon: "≡" },
]
function choose(t: number) { st.tab = t; st.page = null; st.query = "" }
</script>

<template>
  <div class="rail">
    <div class="brand">GoMusic</div>
    <button v-for="i in items" :key="i.t" class="item" :class="{ on: st.tab === i.t && !st.page }"
            @click="choose(i.t)">
      <span class="ic">{{ i.icon }}</span><span>{{ i.name }}</span>
    </button>
    <div class="spacer" />
    <button class="item" @click="st.showHistory = true"><span class="ic">↺</span><span>历史</span></button>
    <button class="item" @click="st.showSettings = true"><span class="ic">⚙</span><span>设置</span></button>
    <div class="state">{{ st.offline ? "离线模式" : "已连接" }}</div>
  </div>
</template>

<style scoped>
.rail {
  width: 168px; flex: none; background: var(--surface); height: 100%;
  padding: 14px 0; display: flex; flex-direction: column;
}
.brand { font-size: 17px; font-weight: 800; padding: 6px 18px 12px; }
.item {
  display: flex; align-items: center; gap: 12px; height: 42px; margin: 2px 10px;
  padding: 0 12px; border-radius: 12px; cursor: pointer; border: none;
  background: transparent; color: var(--on); font-size: 14px; text-align: left;
}
.item:hover { background: rgba(255, 255, 255, .05); }
.item.on { background: rgba(208, 188, 255, .16); color: var(--primary); font-weight: 600; }
.ic { width: 18px; text-align: center; opacity: .9; }
.spacer { flex: 1; }
.state { font-size: 11px; color: var(--muted); padding: 6px 20px; }
</style>
