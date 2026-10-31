import type { LrcLine } from "./types"

/**
 * 一行歌词（逻辑行）：原句 + 可选的译文。
 *
 * 译文有两种来源：
 * 1. 自带翻译 —— 原文与译文两句时间完全相同，第二句就是第一句的译文；
 * 2. 接口翻译 —— 整篇翻出来的结果，按逻辑行顺序对齐。
 *
 * 渲染时 text 跟着播放高亮，trans 永远不高亮；
 * 点 trans 与点 text 行为一致，都是跳到 timeMs。
 */
export interface LyricRow {
  /** 原句在原始歌词行里的下标：播放位置按原始行算，要靠它换算 */
  index: number
  /** 原句时间，< 0 表示这行没有时间轴 */
  timeMs: number
  /** 原句 */
  text: string
  /** 译文，null = 没有译文 */
  trans?: string | null
}

/**
 * 非中文书写系统的字符：拉丁、西里尔、日文假名、韩文、希腊、阿拉伯、泰文。
 *
 * 用来判断「这篇歌词是不是外文的」——只有外文歌词才需要翻译按钮。
 * CJK 统一表意文字（含汉字与日文汉字）不算。
 */
const NON_CHINESE =
  /[A-Za-zÀ-ɏͰ-ϿЀ-ӿ぀-ヿ㄰-㆏가-힯؀-ۿऀ-ॿ฀-๿]/

/** 这句歌词是否含有非中文部分 */
export function hasNonChinese(s: string): boolean {
  return NON_CHINESE.test(s)
}

/**
 * 作词 / 作曲 / 编曲这类标注行里的字（简体与繁体各三个）。
 *
 * 这些行几乎都带英文人名（「作曲 : John Doe」「编曲 : XXX」），
 * 会把整篇误判成外文歌词、白白多出一个翻译按钮。
 * 判断「要不要翻译」时先把它们排除掉。
 */
const CREDIT_RE = /[词詞曲麯编編]/

/** 是否是「词 / 曲 / 编」这类标注行 */
function isCreditLine(s: string): boolean {
  return CREDIT_RE.test(s)
}

/**
 * 歌词是否含非中文部分（含则提供翻译按钮）。
 *
 * 排除标注行，避免英文人名触发；全中文的歌词自然不会命中，不翻译。
 */
export function lyricNeedsTranslation(rows: LyricRow[]): boolean {
  return rows.some((r) => !isCreditLine(r.text) && hasNonChinese(r.text))
}

/**
 * 把原始歌词行整理成逻辑行。
 *
 * 自带翻译的歌词里，原句和译文两句的时间完全相同：
 * 第一句视为原句（会高亮），第二句视为它的译文（不高亮），两行合成一个逻辑行。
 *
 * 时间 < 0 的纯文本歌词不参与配对 —— 那种情况每行时间都一样，
 * 整篇会被误判成「一行原文一行译文」。
 *
 * trans 是接口翻译的结果，按逻辑行顺序对齐。
 */
export function buildLyricRows(lines: LrcLine[], trans?: string[] | null): LyricRow[] {
  const out: LyricRow[] = []
  let i = 0
  while (i < lines.length) {
    const a = lines[i]
    const b = lines[i + 1]
    if (a.timeMs >= 0 && b && b.timeMs === a.timeMs) {
      out.push({ index: i, timeMs: a.timeMs, text: a.text, trans: b.text.trim() || null })
      i += 2
    } else {
      out.push({ index: i, timeMs: a.timeMs, text: a.text, trans: null })
      i += 1
    }
  }
  if (!trans) return out
  return out.map((r, k) => (r.trans ? r : { ...r, trans: (trans[k] ?? "").trim() || null }))
}
