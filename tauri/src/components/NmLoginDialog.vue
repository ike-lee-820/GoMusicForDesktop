<script setup lang="ts">
/** 网易云登录：扫码 + 验证码 */
import { onBeforeUnmount, onMounted, ref } from "vue"
import Icon from "./Icon.vue"
import { api } from "../api"
import { setPref, st, toast } from "../store"

const tab = ref<"qr" | "sms">("qr")
const qr = ref("")
const key = ref("")
const phone = ref("")
const code = ref("")
const sending = ref(false)
let timer: any = null

async function newQr() {
  key.value = await api.nmQrKey().catch(() => "") || ""
  if (!key.value) { toast("获取二维码失败"); return }
  qr.value = await api.nmQrImage(key.value).catch(() => "") || ""
  startPoll()
}

function startPoll() {
  clearInterval(timer)
  timer = setInterval(async () => {
    if (!key.value) return
    const r = await api.nmQrCheck(key.value).catch(() => null)
    if (!r) return
    if (r.code === 803 || (r.cookie && r.cookie.length > 0)) {
      clearInterval(timer)
      await setPref("nmCookie", r.cookie ?? "")
      st.nmReady = true
      st.nmAccount = await api.nmAccountName().catch(() => "") || ""
      toast("登录成功")
      st.showLogin = false
    } else if (r.code === 800) {
      // 二维码过期，换一张
      await newQr()
    }
  }, 1500)
}

async function sendCode() {
  if (phone.value.length < 11) { toast("请输入手机号"); return }
  sending.value = true
  const ok = await api.nmSendCaptcha(phone.value).catch(() => false)
  sending.value = false
  toast(ok ? "验证码已发送" : "发送失败")
}

async function loginByCode() {
  const c = await api.nmLoginCaptcha(phone.value, code.value).catch(() => null)
  if (!c) { toast("登录失败"); return }
  await setPref("nmCookie", c)
  st.nmReady = true
  st.nmAccount = await api.nmAccountName().catch(() => "") || ""
  toast("登录成功")
  st.showLogin = false
}

onMounted(newQr)
onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <div class="overlay" @click="st.showLogin = false">
    <div class="dialog box" @click.stop>
      <div class="head">
        <span class="tt">登录网易云</span>
        <button class="iconbtn" title="关闭" @click="st.showLogin = false"><Icon name="close" :size="20" /></button>
      </div>
      <div class="tabs">
        <button class="btn" :class="{ primary: tab === 'qr' }" @click="tab = 'qr'">扫码登录</button>
        <button class="btn" :class="{ primary: tab === 'sms' }" @click="tab = 'sms'">验证码登录</button>
      </div>
      <div v-if="tab === 'qr'" class="qr">
        <img v-if="qr" :src="qr" alt="二维码" />
        <div v-else class="muted">二维码加载中…</div>
        <div class="muted tip">用网易云音乐 App 扫码</div>
        <button class="btn ghost" @click="newQr">刷新二维码</button>
      </div>
      <div v-else class="sms">
        <input v-model="phone" class="field" placeholder="手机号" />
        <div class="coderow">
          <input v-model="code" class="field" placeholder="验证码" />
          <button class="btn" :disabled="sending" @click="sendCode">发送</button>
        </div>
        <button class="btn primary" @click="loginByCode">登录</button>
      </div>
      <div class="foot muted">不登录也可以搜索和试听，登录后可同步歌单与使用更高音质。</div>
    </div>
  </div>
</template>

<style scoped>
.box { width: 380px; }
.head { display: flex; align-items: center; padding: 12px 16px; }
.tt { flex: 1; font-size: 16px; font-weight: 700; }
.tabs { display: flex; gap: 8px; padding: 0 16px 12px; }
.qr { display: flex; flex-direction: column; align-items: center; gap: 10px; padding-bottom: 8px; }
.qr img { width: 200px; height: 200px; background: #fff; border-radius: 10px; }
.tip { font-size: 12px; }
.sms { display: flex; flex-direction: column; gap: 10px; padding: 0 16px 10px; }
.coderow { display: flex; gap: 8px; }
.foot { padding: 0 16px 16px; font-size: 12px; }
</style>
