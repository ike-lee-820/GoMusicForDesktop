package gomusic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowDraggableArea

val FONT_NAMES = listOf("默认", "无衬线", "衬线", "等宽", "粗体")

fun floatFontFamily(idx: Int): FontFamily = when (idx) {
    1 -> FontFamily.SansSerif
    2 -> FontFamily.Serif
    3 -> FontFamily.Monospace
    else -> FontFamily.Default
}

/** 悬浮歌词窗内容：透明底、可拖动，显示当前行与下一行 */
@Composable
fun FloatLyricContent(state: AppState) {
    val (cur, next) = state.floatLines()
    val fam = floatFontFamily(state.floatFont)
    val weight = if (state.floatFont == 4) FontWeight.Bold else FontWeight.Normal
    val base = remember(state.floatSize, fam, weight) {
        TextStyle(
            fontSize = state.floatSize.sp,
            fontFamily = fam,
            fontWeight = weight,
            textAlign = TextAlign.Center,
        )
    }
    WindowDraggableArea {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                OutlineText(cur, base, state.floatColor, state.floatStrokeColor, state.floatStroke)
                if (next.isNotBlank()) {
                    OutlineText(
                        next, base.copy(fontSize = state.floatSize.sp * 0.82f),
                        state.floatColor.copy(alpha = 0.65f), state.floatStrokeColor, state.floatStroke * 0.7f,
                    )
                }
            }
        }
    }
}

/** 先描边再填充，两层叠加得到描边文字 */
@Composable
private fun OutlineText(
    text: String,
    style: TextStyle,
    color: Color,
    strokeColor: Color,
    strokeW: Float,
) {
    if (text.isBlank()) return
    if (strokeW > 0) {
        Text(
            text,
            style = style.copy(color = strokeColor, drawStyle = Stroke(width = strokeW)),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text(text, style = style.copy(color = color), modifier = Modifier.fillMaxWidth())
}
