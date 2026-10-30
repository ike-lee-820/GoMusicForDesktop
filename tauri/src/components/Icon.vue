<script setup lang="ts">
/**
 * 图标：与手机端同源的 Material Icons（outlined）。
 * SVG 内联在 assets/icons 下，打包进应用，离线也能显示。
 */
import { computed } from "vue"

const mods = import.meta.glob("../assets/icons/*.svg", {
  query: "?raw", import: "default", eager: true,
}) as Record<string, string>

const props = withDefaults(defineProps<{ name: string; size?: number | string }>(), { size: 20 })

const px = computed(() => (typeof props.size === "number" ? `${props.size}px` : props.size))

const svg = computed(() => {
  const key = Object.keys(mods).find((k) => k.endsWith(`/${props.name}.svg`))
  if (!key) return ""
  // 全部 path 都取出来（有的图标不止一条，比如 speed），尺寸交给 CSS
  const ds: string[] = []
  const re = /<path[^>]*\sd="([^"]+)"/g
  let m: RegExpExecArray | null
  while ((m = re.exec(mods[key])) !== null) ds.push(m[1])
  return ds
})
</script>

<template>
  <svg v-if="svg.length" class="ico" viewBox="0 0 24 24"
       :style="{ width: px, height: px }" aria-hidden="true">
    <path v-for="(d, i) in svg" :key="i" :d="d" />
  </svg>
  <span v-else class="ico-fb">{{ name.slice(0, 1) }}</span>
</template>

<style scoped>
.ico { display: block; fill: currentColor; flex: none; }
.ico-fb { font-size: 12px; color: var(--muted); }
</style>
