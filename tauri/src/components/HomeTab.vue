<script setup lang="ts">
/** 首页：历史 + 热搜 + 推荐 + 新歌 + 榜单 + 热门歌手 + 新专辑 */
import { computed, onMounted } from "vue"
import { historySongs, loadHome, playSongs, st } from "../store"
import type { Group, Song } from "../types"

const emit = defineEmits<{ (e: "openGroup", g: Group): void }>()
onMounted(loadHome)

function search(w: string) { st.tab = 1; st.query = w }

// 只取前 20 张卡：首页一次铺太多封面会拖慢首屏
const recommend = computed(() => (st.home?.recommend ?? []).slice(0, 20))
const newSongs = computed(() => (st.home?.newSongs ?? []).slice(0, 20))
const tops = computed(() => (st.home?.tops ?? []).slice(0, 20))
const topArtists = computed(() => (st.home?.topArtists ?? []).slice(0, 20))
const newAlbums = computed(() => (st.home?.newAlbums ?? []).slice(0, 20))
const hotWords = computed(() => (st.home?.hotWords ?? []).slice(0, 12))
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

    <template v-if="hotWords.length">
      <div class="head">热搜</div>
      <div class="words">
        <button v-for="w in hotWords" :key="w" class="word" @click="search(w)">{{ w }}</button>
      </div>
    </template>

    <template v-if="tops.length">
      <div class="head">榜单</div>
      <div class="hlist">
        <div v-for="g in tops" :key="g.nmId || g.title" class="hcard" @click="emit('openGroup', g)">
          <div class="cv" :style="g.cover ? `background-image:url(${g.cover})` : ''" />
          <div class="t ellip">{{ g.title }}</div>
          <div class="a ellip muted">{{ g.subtitle }}</div>
        </div>
      </div>
    </template>

    <template v-if="recommend.length">
      <div class="head">每日推荐</div>
      <div class="hlist">
        <div v-for="s in recommend" :key="s.ssid" class="hcard" @click="playSongs(recommend, recommend.indexOf(s))">
          <div class="cv" :style="s.coverUrl ? `background-image:url(${s.coverUrl})` : ''" />
          <div class="t ellip">{{ s.title }}</div>
          <div class="a ellip muted">{{ s.artist }}</div>
        </div>
      </div>
    </template>
    <template v-if="newSongs.length">
      <div class="head">新歌速递</div>
      <div class="hlist">
        <div v-for="s in newSongs" :key="s.ssid" class="hcard" @click="playSongs(newSongs, newSongs.indexOf(s))">
          <div class="cv" :style="s.coverUrl ? `background-image:url(${s.coverUrl})` : ''" />
          <div class="t ellip">{{ s.title }}</div>
          <div class="a ellip muted">{{ s.artist }}</div>
        </div>
      </div>
    </template>

    <template v-if="topArtists.length">
      <div class="head">热门歌手</div>
      <div class="hlist">
        <div v-for="g in topArtists" :key="g.nmId || g.title" class="hcard round" @click="emit('openGroup', g)">
          <div class="cv circle" :style="g.cover ? `background-image:url(${g.cover})` : ''" />
          <div class="t ellip">{{ g.title }}</div>
        </div>
      </div>
    </template>

    <template v-if="newAlbums.length">
      <div class="head">新专辑</div>
      <div class="hlist">
        <div v-for="g in newAlbums" :key="g.nmId || g.title" class="hcard" @click="emit('openGroup', g)">
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
.hcard {
  width: 92px; flex: none; cursor: pointer;
  content-visibility: auto; contain-intrinsic-size: 92px 130px;
}
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
