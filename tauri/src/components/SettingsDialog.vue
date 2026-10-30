<script setup lang="ts">
/** 设置：字体 / 主题色 / 音质 / 倍速 / 简繁 / 网易云 / 更新 */
import { onMounted, ref } from "vue"
import Icon from "./Icon.vue"
import { api } from "../api"
import {
  checkUpdate, load, qualityLabel, setFontKind, setPref, setQuality, setSpeed,
  setThemeColor, setZhConvert, st, toast,
} from "../store"

const colors = [0xffd0bcff, 0xff9ad0ff, 0xffa8e6cf, 0xffffd6a5, 0xffffb3b3, 0xffb5c0ff]
const qualities = ["lossless", "high", "normal", "source"]
const nmQualities = ["lossless", "hires", "exhigh", "higher", "standard"]
const speeds = [0.5, 0.75, 1, 1.25, 1.5, 2]
const fonts = [{ v: 0, n: "系统字体" }, { v: 1, n: "思源宋体" }]
const zhs = [
  { v: 0, n: "简体" }, { v: 1, n: "繁体（香港）" }, { v: 2, n: "仅搜索统一" }, { v: 3, n: "不转换" },
]
const nmAccount = ref("")
const nmOn = ref(false)
const checking = ref(false)

onMounted(async () => {
  nmAccount.value = await api.nmAccountName().catch(() => "") || st.nmAccount
  nmOn.value = await api.nmLoggedIn().catch(() => false)
})

function hex(c: number) { return "#" + ((c >>> 0) & 0xffffff).toString(16).padStart(6, "0") }

async function logout() {
  await setPref("nmCookie", "")
  await setPref("nmAccount", "")
  st.nmAccount = ""
  nmAccount.value = ""
  nmOn.value = await api.nmLoggedIn().catch(() => false)
  toast("已退出网易云登录")
}

async function doCheck() {
  checking.value = true
  try { await checkUpdate(false) } finally { checking.value = false }
}
</script>

<template>
  <div class="overlay" @click="st.showSettings = false">
    <div class="dialog box" @click.stop>
      <div class="head">
        <span class="tt">设置</span>
        <button class="iconbtn" title="关闭" @click="st.showSettings = false"><Icon name="close" :size="20" /></button>
      </div>
      <div class="body">
        <div class="grp">
          <div class="gt">字体</div>
          <div class="opts">
            <button v-for="f in fonts" :key="f.v" class="btn" :class="{ primary: st.fontKind === f.v }"
                    @click="setFontKind(f.v)">{{ f.n }}</button>
          </div>
          <div class="hint muted">切换后立即生效，下次启动保持。</div>
        </div>

        <div class="grp">
          <div class="gt">主题色</div>
          <div class="colors">
            <button v-for="c in colors" :key="c" class="dot" :style="{ background: hex(c) }"
                    :class="{ on: st.themeColor === c }" @click="setThemeColor(c)" />
            <button class="btn ghost" @click="setThemeColor(null)">默认</button>
          </div>
        </div>

        <div class="grp">
          <div class="gt">音质（本站曲库）</div>
          <div class="opts">
            <button v-for="q in qualities" :key="q" class="btn" :class="{ primary: st.quality === q }"
                    @click="setQuality(q)">{{ qualityLabel(q) }}</button>
          </div>
          <div class="gt nm">音质（网易云，播放时可在播放器内切换）</div>
          <div class="opts">
            <button v-for="q in nmQualities" :key="q" class="btn" :class="{ primary: st.quality === q }"
                    @click="setQuality(q)">{{ qualityLabel(q) }}</button>
          </div>
        </div>

        <div class="grp">
          <div class="gt">倍速</div>
          <div class="opts">
            <button v-for="s in speeds" :key="s" class="btn" :class="{ primary: st.speed === s }"
                    @click="setSpeed(s)">{{ s }}×</button>
          </div>
        </div>

        <div class="grp">
          <div class="gt">简繁转换</div>
          <div class="opts">
            <button v-for="z in zhs" :key="z.v" class="btn" :class="{ primary: st.zhConvert === z.v }"
                    @click="setZhConvert(z.v)">{{ z.n }}</button>
          </div>
        </div>

        <div class="grp">
          <div class="gt">网易云</div>
          <div class="rowline">
            <span class="muted">{{ nmOn ? `已登录：${nmAccount || "未知"}` : "未登录（游客模式可用）" }}</span>
            <button class="btn" @click="st.showSettings = false; st.showLogin = true">
              {{ nmOn ? "重新登录" : "登录" }}
            </button>
            <button v-if="nmOn" class="btn ghost" @click="logout">退出</button>
          </div>
        </div>

        <div class="grp">
          <div class="gt">下载</div>
          <div class="rowline">
            <span class="muted">已下载 {{ st.downloads.length }} 首</span>
            <button class="btn" @click="api.openDownloadDir()"><Icon name="folder_open" :size="18" />打开下载目录</button>
          </div>
        </div>

        <div class="grp">
          <div class="gt">关于</div>
          <div class="rowline">
            <span class="muted">GoMusic 桌面版 {{ st.version }}</span>
            <button class="btn" :disabled="checking" @click="doCheck">
              <Icon name="update" :size="18" />{{ checking ? "检查中…" : "检查更新" }}
            </button>
            <button class="btn ghost" @click="load()">重新载入曲库</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.box { width: 560px; }
.head { display: flex; align-items: center; padding: 12px 16px; border-bottom: 1px solid var(--surface-hi); }
.tt { flex: 1; font-size: 16px; font-weight: 700; }
.body { overflow: auto; padding: 12px 16px 18px; }
.grp { padding: 8px 0 14px; }
.gt { font-size: 13px; font-weight: 700; margin-bottom: 8px; }
.gt.nm { margin-top: 10px; }
.opts { display: flex; flex-wrap: wrap; gap: 8px; }
.hint { font-size: 12px; margin-top: 6px; }
.colors { display: flex; align-items: center; gap: 10px; }
.dot {
  width: 26px; height: 26px; border-radius: 50%; border: 2px solid transparent; cursor: pointer;
}
.dot.on { border-color: #fff; }
.rowline { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
</style>
