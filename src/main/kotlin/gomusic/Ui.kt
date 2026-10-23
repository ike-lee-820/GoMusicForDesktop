package gomusic

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val BgColor = Color(0xFF121014)
val SurfaceColor = Color(0xFF1C1A20)
val SurfaceHiColor = Color(0xFF2A2730)
val OnColor = Color(0xFFECE6F0)
val MutedColor = Color(0xFFA8A2B0)

@Composable
fun GoTheme(primary: Color, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = primary,
            background = BgColor,
            surface = SurfaceColor,
            surfaceVariant = SurfaceHiColor,
            onSurface = OnColor,
            onBackground = OnColor,
            onPrimary = Color(0xFF1A1423),
        ),
        content = content,
    )
}

@Composable
fun Txt(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    color: Color = MaterialTheme.colorScheme.onSurface,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
) {
    androidx.compose.material3.Text(
        text,
        modifier,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
    )
}

@Composable
fun IconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    size: Dp = 36.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
    }
}

@Composable
fun GoButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val bg = if (primary) cs.primary else cs.surfaceVariant
    val fg = if (primary) cs.onPrimary else cs.onSurface
    Box(
        modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(text, 13, weight = FontWeight.SemiBold, color = fg) }
}

/** 封面：异步加载 + 缓存，按显示尺寸解码 */
@Composable
fun Cover(
    ref: String?,
    modifier: Modifier,
    size: Int = 128,
    radius: Dp = 8.dp,
    circle: Boolean = false,
) {
    val src = coverRef(ref)
    var bmp by remember(src, size) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(src, size) {
        if (src.isNullOrBlank()) {
            bmp = null
            return@LaunchedEffect
        }
        bmp = withContext(Dispatchers.IO) { Images.get(src, size)?.bmp }
    }
    val shape = if (circle) CircleShape else RoundedCornerShape(radius)
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bmp!!, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Outlined.MusicNote, null, tint = MutedColor.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun SearchField(value: String, onValue: (String) -> Unit, hint: String) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp))
            .background(cs.surface).padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Txt(hint, 14, color = MutedColor)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = cs.onSurface, fontSize = 14.sp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 进度条：点击定位，拖动显示气泡 */
@Composable
fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = drag ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column {
        Box(
            Modifier.fillMaxWidth().height(20.dp)
                .pointerInput(duration) {
                    val w = size.width.coerceAtLeast(1)
                    detectTapGestures { o ->
                        if (duration > 0) onSeek((o.x / w * duration).toLong().coerceIn(0, duration))
                    }
                }
                .pointerInput(duration) {
                    val w = size.width.coerceAtLeast(1)
                    detectDragGestures(
                        onDragStart = { o -> drag = (o.x / w).coerceIn(0f, 1f) },
                        onDragEnd = { drag = null },
                        onDragCancel = { drag = null },
                    ) { change, _ ->
                        change.consume()
                        drag = (change.position.x / w).coerceIn(0f, 1f)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                val track = RoundedCornerShape(2.dp)
                Box(Modifier.fillMaxWidth().height(4.dp).clip(track).background(Color.White.copy(alpha = 0.2f)))
            }
            Row(Modifier.fillMaxWidth().height(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (frac > 0.01f) Spacer(Modifier.weight(frac))
                Box(Modifier.size(12.dp).background(cs.primary, CircleShape))
                if (frac < 0.99f) Spacer(Modifier.weight((1f - frac).coerceAtLeast(0.001f)))
            }
            // 已播放部分（画在轨道之上）
            Box(
                Modifier.fillMaxWidth(frac).height(4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(cs.primary)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Txt(fmt(if (drag != null) (frac * duration).toLong() else position), 12, color = MutedColor)
            Txt(if (duration > 0) fmt(duration) else "--:--", 12, color = MutedColor)
        }
    }
}

/** 鼠标悬停状态 + 对应 Modifier（桌面端列表行高亮用） */
@Composable
fun hoverState(): Pair<Boolean, Modifier> {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    return hovered to Modifier.hoverable(src)
}

/** 悬浮歌词用的取色行 */
@Composable
fun FloatColorRow(current: Color, onPick: (Color) -> Unit) {
    val presets = listOf(
        0xFFFFFFFFL, 0xFF000000L, 0xFFD0BCFFL, 0xFF64B5F6L,
        0xFF81C784L, 0xFFFFF176L, 0xFFFF8A80L, 0xFFF48FB1L,
    ).map { Color(it.toULong()) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        presets.forEach { c ->
            val on = c == current
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(c)
                    .clickable { onPick(c) },
                contentAlignment = Alignment.Center,
            ) {
                if (on) {
                    Box(
                        Modifier.size(12.dp).clip(CircleShape)
                            .background(if (c.luminance() > 0.5f) Color.Black else Color.White),
                    )
                }
            }
        }
    }
}

/** 自带进度条，避免依赖 material3 不同版本的 API 差异 */
@Composable
fun Bar(progress: Float?, modifier: Modifier = Modifier, color: Color) {
    val track = RoundedCornerShape(2.dp)
    if (progress == null) {
        // 不确定进度：整条呼吸
        val t = rememberInfiniteTransition(label = "ind")
        val f by t.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
            label = "f",
        )
        Box(modifier.height(4.dp).clip(track).background(color.copy(alpha = 0.3f + f * 0.7f)))
    } else {
        Box(modifier.height(4.dp).clip(track).background(SurfaceHiColor)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).clip(track).background(color))
        }
    }
}

/** 加载指示：一圈旋转的弧 */
@Composable
fun Spinner(modifier: Modifier = Modifier, color: Color, size: Dp = 28.dp) {
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
    Canvas(modifier.size(size)) {
        drawArc(
            color = color,
            startAngle = a,
            sweepAngle = 300f,
            useCenter = false,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
fun ToastHost(msg: String?) {
    if (msg == null) return
    Box(Modifier.fillMaxSize().padding(bottom = 32.dp), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.clip(RoundedCornerShape(12.dp))
                .background(SurfaceHiColor).padding(horizontal = 16.dp, vertical = 10.dp),
        ) { Txt(msg, 14) }
    }
}
