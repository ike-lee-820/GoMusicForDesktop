package gomusic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font as PlatformFont

/** 系统字体 */
const val FONT_SYSTEM = 0

/** 思源宋体（可变字体，内置） */
const val FONT_SONG = 1

/** 可变字体 wght 轴范围 */
const val WGHT_MIN = 250

const val WGHT_MAX = 900

/** 默认字重 */
const val WGHT_DEFAULT = 450

/** 字体名称，设置界面直接用 */
val FONT_NAMES = listOf("系统字体", "思源宋体")

/** 思源宋体在 resources 里的路径 */
private const val SONG_RES = "fonts/song.ttf"

/**
 * 按当前选择构造全局字体。
 *
 * 桌面端必须用 androidx.compose.ui.text.platform.Font（它按 resources
 * 路径加载），androidx.compose.ui.text.font.Font 只认 Android 的资源 id。
 *
 * 可变字体的 wght 轴在这里没法直接指定（桌面端 Font 不支持
 * variationSettings），所以用 FontWeight 注册两个实例：
 * 常规用设置的字重，另一个用 Bold，界面里的粗体仍有层次。
 */
@Composable
fun rememberAppFont(kind: Int, wght: Int): FontFamily? =
    remember(kind, wght) { buildFontFamily(kind, wght) }

private fun buildFontFamily(kind: Int, wght: Int): FontFamily? {
    if (kind != FONT_SONG) return null
    val w = wght.coerceIn(WGHT_MIN, WGHT_MAX)
    return runCatching {
        FontFamily(
            PlatformFont(SONG_RES, weight = FontWeight(w)),
            PlatformFont(SONG_RES, weight = FontWeight.Bold),
        )
    }.onFailure {
        NmLog.note("思源宋体加载失败，退回系统字体：${it.message}")
    }.getOrNull()
}
