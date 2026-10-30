/**
 * 简繁转换：用 index.html 引入的 opencc-js（UMD）。
 * 脚本没加载完或加载失败时原样返回，绝不显示空白。
 */

declare global {
  interface Window {
    OpenCC?: {
      Converter: (from: string, to: string) => (s: string) => string
      CustomConverter?: (rules: any[]) => (s: string) => string
    }
  }
}

let convTw: ((s: string) => string) | null = null
let convHk: ((s: string) => string) | null = null
let convCn: ((s: string) => string) | null = null

function make() {
  const O = typeof window !== "undefined" ? window.OpenCC : undefined
  if (!O) return false
  try {
    convCn = O.Converter("t", "cn")   // 繁 -> 简（含大陆用语）
    convTw = O.Converter("s", "tw")   // 简 -> 台湾繁体
    convHk = O.Converter("s", "hk")   // 简 -> 香港繁体
    return true
  } catch { return false }
}

function ensure(): boolean {
  if (convCn) return true
  return make()
}

/** 转成简体 */
export function toSimple(s: string): string {
  if (!s) return s
  if (!ensure()) return s
  try { return convCn!(s) } catch { return s }
}

/** 转成繁体（台湾） */
export function toTrad(s: string): string {
  if (!s) return s
  if (!ensure()) return s
  try { return convTw!(s) } catch { return s }
}

/**
 * 界面显示用文本。
 * mode: 0 简体 / 1 香港繁体 / 2 仅搜索统一（显示原样）/ 3 不转换
 */
export function zhText(s: string, mode = 0): string {
  if (!s) return s
  if (mode === 2 || mode === 3) return s
  if (mode === 1) {
    if (!ensure()) return s
    try { return convHk!(s) } catch { return s }
  }
  return toSimple(s)
}
