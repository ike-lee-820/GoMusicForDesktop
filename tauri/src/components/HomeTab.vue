<script setup lang="ts">
/** 首页：历史 + 热搜 + 推荐 + 新歌 + 榜单 + 热门歌手 + 新专辑 */
import { onMounted } from "vue"
import { historySongs, loadHome, playSongs, st } from "../store"
import type { Group, Song } from "../types"

const emit = defineEmits<{ (e: "openGroup", g: Group): void }>()
onMounted(loadHome)

function search(w: string) { st.tab = 1; st.query = w }
function row(list: Song[], title: string) {
  return { list, title }
}
const sections = () => ([
  row(st.home?.recommend ?? [], "每日推荐"),
  row(st.home?.newSongs ?? [], "新歌速递"),
])
</script>

<template>
  <div class="home">
    <template v-if="historySongs.length">
      <div class="head">历史</div>
      <div class="hlist">
        <div v-for="s in historySongs.slice(0, 20)" :key="s.ssid" class="hcard" @click="playSongs([s], 0)">
          <div class="cv" :style="s.coverUrl ? `background-image:url(${s.coverUrl})` : ''" />
          <div class="t ellip">{{ s.title }}</div>
          <div class="a ellip muted">{{ s.artist }}</div>
        </div>
      </div>
    </template>

    <template v-if="st.home?.hotWords?.length">
      <div class="head">热搜</div>
      <div class="words">
        <button v-for="w in st.home.hotWords.slice(0, 12)" :key="w" class="word" @click="search(w)">{{ w }}</button>
      </div>
    </template>

    <template v-if="st.home?.tops?.length">
      <div class="head">榜单</div>
      <div class="hlist">
        <div v-for="g in st.home.tops" :key="g.nmId || g.title" class="hcard" @click="emit('openGroup', g)">
          <div class="cv" :style="g.cover ? `background-image:url(${g.cover})` : ''" />
          <div class="t ellip">{{ g.title }}</div>
          <div class="a ellip muted">{{ g.subtitle }}</div>
        </div>
      </div>
    </template>

    <template v-for="sec in sections()" :key="sec.title">
      <template v-if="sec.list.length">
        <div class="head">{{ sec.title }}</div>
        <div class="hlist">
          <div v-for="s in sec.list.slice(0, 20)" :key="s.ssid" class="hcard" @click="playSongs(sec.list, sec.list.indexOf(s))">
            <div class="cv" :style="s.coverUrl ? `background-image:url(${s.coverUrl})` : ''" />
            <div class="t ellip">{{ s.title }}</div>
            <div class="a ellip muted">{{ s.artist }}</div>
          </div>
        </div>
      </template>
    </template>

    <template v-if="st.home?.topArtists?.length">
      <div class="head">热门歌手</div>
      <div class="hlist">
        <div v-for="g in st.home.topArtists" :key="g.nmId || g.title" class="hcard round" @click="emit('openGroup', g)">
          <div class="cv circle" :style="g.cover ? `background-image:url(${g.cover})` : ''" />
          <div class="t ellip">{{ g.title }}</div>
        </div>
      </div>
    </template>

    <template v-if="st.home?.newAlbums?.length">
      <div class="head">新专辑</div>
      <div class="hlist">
        <div v-for="g in st.home.newAlbums" :key="g.nmId || g.title" class="hcard" @click="emit('openGroup', g)">
          <div class="cv" :style="g.cover ? `background-image:url(${g.cover})` : ''" />
          <div class="t ellip">{{ g.title }}</div>
          <div class="a ellip muted">{{ g.artist }}</div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.home { height: 100%; overflow: auto; padding-bottom: 20px; }
.head { font-size: 15px; font-weight: 700; padding: 14px 16px 8px; }
.hlist { display: flex; gap: 12px; overflow-x: auto; padding: 0 16px 6px; }
.hcard { width: 92px; flex: none; cursor: pointer; }
.round { width: 84px; text-align: center; }
.cv {
  width: 100%; aspect-ratio: 1; border-radius: 10px; background-size: cover;
  background-position: center; background-color: var(--surface-hi);
}
.cv.circle { border-radius: 50%; }
.t { font-size: 12px; margin-top: 5px; }
.a { font-size: 11px; }
.words { display: flex; flex-wrap: wrap; gap: 8px; padding: 0 16px 10px; }
.word {
  border: none; background: var(--surface-hi); color: var(--on); border-radius: 14px;
  padding: 5px 12px; font-size: 12px; cursor: pointer;
}
.word:hover { background: var(--primary); color: var(--on-primary); }
</style>
