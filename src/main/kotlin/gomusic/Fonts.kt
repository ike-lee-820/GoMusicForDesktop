package gomusic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

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

/**
 * 按当前选择构造全局字体。
 *
 * 用可变字体的 wght 轴直接指定字重，而不是让系统按 FontWeight 猜：
 * 同一份字体文件注册两个实例——常规用基准字重，粗体在基准上再加一档，
 * 这样界面里的 Bold 仍然有层次，不会被统一成一个粗细。
 */
@Composable
fun rememberAppFont(kind: Int, wght: Int): FontFamily? =
    remember(kind, wght) { buildFontFamily(kind, wght) }

/**
 * 字体缓存文件。
 *
 * 字体打包在 resources 里（jar 内部），而 Compose 桌面端的
 * androidx.compose.ui.text.font.Font 需要真实文件路径，
 * 所以启动时把它落到缓存目录一次，之后直接复用。
 */
private var songFile: java.io.File? = null

private fun resolveSongFile(): java.io.File? {
    songFile?.let { if (it.exists() && it.length() > 0) return it }
    val dir = java.io.File(
        System.getProperty("java.io.tmpdir") ?: ".", "gomusic-font",
    )
    val out = java.io.File(dir, "song.ttf")
    val ok = runCatching {
        if (out.exists() && out.length() > 0) return@runCatching true
        dir.mkdirs()
        javaClass.getResourceAsStream("/fonts/song.ttf")?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: return@runCatching false
        true
    }.getOrNull() ?: false
    if (ok) songFile = out else NmLog.note("思源宋体资源读取失败，退回系统字体")
    return if (ok) out else null
}

private fun buildFontFamily(kind: Int, wght: Int): FontFamily? {
    if (kind != FONT_SONG) return null
    // 读不到就退回系统字体，不影响启动
    val f = resolveSongFile() ?: return null
    val w = wght.coerceIn(WGHT_MIN, WGHT_MAX)
    val bw = (w + 250).coerceAtMost(WGHT_MAX)
    return FontFamily(
        Font(f, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(w))),
        Font(f, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(bw))),
    )
}
