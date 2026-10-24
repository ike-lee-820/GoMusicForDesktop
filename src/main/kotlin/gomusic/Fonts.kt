package gomusic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font as PlatformFont

/** 系统字体 */
const val FONT_SYSTEM = 0

/** 思源宋体（内置完整字库，未子集化） */
const val FONT_SONG = 1

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
 * 不提供字重调节：桌面端 Font 不支持可变字轴（variationSettings），
 * 字重由字体文件本身决定，注册常规与 Bold 两个实例让界面粗体仍有层次。
 */
@Composable
fun rememberAppFont(kind: Int): FontFamily? = remember(kind) { buildFontFamily(kind) }

private fun buildFontFamily(kind: Int): FontFamily? {
    if (kind != FONT_SONG) return null
    return runCatching {
        FontFamily(
            PlatformFont(SONG_RES, weight = FontWeight.Normal),
            PlatformFont(SONG_RES, weight = FontWeight.Bold),
        )
    }.onFailure {
        NmLog.note("思源宋体加载失败，退回系统字体：${it.message}")
    }.getOrNull()
}
