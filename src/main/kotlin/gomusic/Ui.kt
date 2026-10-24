package gomusic

import kotlin.math.abs
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import kotlinx.coroutines.launch
import androidx.compose.material3.CircularProgressIndicator
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Text
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LocalDensity
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

/** 简繁转换模式，默认转成简体 */
val LocalZhMode = androidx.compose.runtime.compositionLocalOf { ZhConvert.TO_SIMPLE }

/** 按设置转换显示文本 */
@Composable
fun zhText(s: String): String {
    val m = LocalZhMode.current
    return if (m == ZhConvert.OFF || m == ZhConvert.SEARCH_ONLY) s else ZhConvert.display(s, m)
}

/**
 * 本站（本地曲库）搜索的匹配判断。
 * 「仅搜索统一」时把文本与关键词都归一成简/繁各比一次，
 * 这样用简体搜能出繁体结果、用繁体搜也能出简体结果，两种写法一起叠加回来。
 */
fun localMatch(text: String, q: String, mode: Int): Boolean {
    if (q.isEmpty()) return true
    if (text.contains(q, ignoreCase = true)) return true
    if (mode != ZhConvert.SEARCH_ONLY) return false
    val tq = ZhConvert.toSimple(q)
    if (tq != q) {
        if (ZhConvert.toSimple(text).contains(tq, ignoreCase = true)) return true
        if (ZhConvert.toTrad(text).contains(tq, ignoreCase = true)) return true
    }
    val hq = ZhConvert.toTrad(q)
    if (hq != q) {
        if (ZhConvert.toSimple(text).contains(hq, ignoreCase = true)) return true
        if (ZhConvert.toTrad(text).contains(hq, ignoreCase = true)) return true
    }
    return false
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
    // 界面文字统一按简繁设置转换（「仅搜索统一 / 不转换」时原样显示）
    val shown = zhText(text)
    androidx.compose.material3.Text(
        shown,
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

/** 单行输入框 */
@Composable
fun GoField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Txt(label, 12) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 来源前缀：网易云曲库的结果在歌名 / 专辑名前加「(网)」，
 * 与本站曲库区分（不用图标、不用 emoji）。
 */
// 来源不再靠名字前缀区分，改为封面右下角叠图标，标题原样显示
fun titleWithSource(title: String, source: String): String = title

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

/**
 * 中心旋转式歌词（滚轮）。
 *
 * 当前行固定在正中，其余行绕一个圆柱面分布在上下两侧：
 * 越远的行越小、越淡、绕 X 轴转得越多，形成滚轮的透视感。
 * 上下滑动可以旋转选行，点某一行直接跳到那一句。
 */
@Composable
fun WheelLyrics(
    lines: List<LrcLine>,
    idx: Int,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    onSeek: (Long) -> Unit,
) {
    if (lines.isEmpty()) {
        // 还在请求时显示「正在加载」，只有确认拿不到才说「暂无歌词」
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Txt("正在加载歌词…", 14, color = Color.White.copy(alpha = 0.7f))
                }
            } else {
                Txt("暂无歌词", 14, color = Color.White.copy(alpha = 0.55f))
            }
        }
        return
    }
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.unit.LocalDensity.current
    val rowH = with(density) { 52.dp.toPx() }
    // 每行占的圆心角：越小排列越平，越大透视越明显
    val angleDeg = 11f
    val angleRad = angleDeg * 0.017453292f
    val radius = rowH / sin(angleRad)
    val offset = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    var manualUntil by remember { mutableLongStateOf(0L) }
    val last = lines.lastIndex.coerceAtLeast(0).toFloat()

    LaunchedEffect(idx, lines.size) {
        if (idx >= 0 && !dragging && System.currentTimeMillis() > manualUntil) {
            offset.animateTo(idx.toFloat().coerceIn(0f, last), spring(stiffness = Spring.StiffnessLow))
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .pointerInput(lines) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            manualUntil = Long.MAX_VALUE
                            scope.launch { offset.stop() }
                        },
                        onDragEnd = {
                            dragging = false
                            // 松手后对齐到最近的一行，冷却 4 秒再恢复跟播
                            manualUntil = System.currentTimeMillis() + 4000
                            scope.launch {
                                offset.animateTo(
                                    offset.value.roundToInt().toFloat().coerceIn(0f, last),
                                    spring(stiffness = Spring.StiffnessLow),
                                )
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            manualUntil = System.currentTimeMillis() + 4000
                        },
                        onVerticalDrag = { _, dragAmount ->
                            val d = -dragAmount / rowH
                            scope.launch { offset.snapTo((offset.value + d).coerceIn(0f, last)) }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val cur = offset.value
            val span = 7
            val from = (cur - span).toInt().coerceAtLeast(0)
            val to = (cur + span).toInt().coerceAtMost(lines.lastIndex)
            for (i in from..to) {
                val d = i - cur
                val theta = d * angleRad
                val y = radius * sin(theta)
                val depth = cos(theta).coerceAtLeast(0f)
                val fade = (1f - abs(d) / (span + 1f)).coerceIn(0f, 1f)
                val isCur = abs(d) < 0.5f
                Text(
                    zhText(lines[i].text),
                    Modifier
                        .graphicsLayer {
                            translationY = y
                            rotationX = d * angleDeg
                            scaleX = 0.88f + 0.12f * depth
                            scaleY = 0.88f + 0.12f * depth
                            alpha = 0.16f + 0.84f * fade
                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                        }
                        .clickable(enabled = lines[i].timeMs >= 0) {
                            manualUntil = System.currentTimeMillis() + 6000
                            onSeek(lines[i].timeMs)
                        }
                        .padding(horizontal = 28.dp, vertical = 3.dp),
                    color = Color.White,
                    fontSize = if (isCur) 19.sp else 16.sp,
                    lineHeight = if (isCur) 30.sp else 26.sp,
                    fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 以中心为圆心的圆形滚动歌词。
 *
 * 歌词沿以封面中心为圆心、[radius] 为半径的圆周排列：
 * 当前行固定在正上方（12 点方向），其余行按角度分布在两侧并绕圆心旋转，
 * 字身沿切线方向对齐，越远越小越淡。
 * 上下滑动可以手动旋转圆盘，点某一行直接跳到那一句。
 */
@Composable
fun CircleLyrics(
    lines: List<LrcLine>,
    idx: Int,
    radius: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    onSeek: (Long) -> Unit,
) {
    if (lines.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Txt("正在加载歌词…", 13, color = Color.White.copy(alpha = 0.7f))
                }
            } else {
                Txt("暂无歌词", 13, color = Color.White.copy(alpha = 0.55f))
            }
        }
        return
    }
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.unit.LocalDensity.current
    val rPx = with(density) { radius.toPx() }
    val stepDeg = 13f
    val stepRad = stepDeg * 0.017453292f
    val arcPx = (rPx * stepRad).coerceAtLeast(1f)
    val offset = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    var manualUntil by remember { mutableLongStateOf(0L) }
    val last = lines.lastIndex.coerceAtLeast(0).toFloat()

    LaunchedEffect(idx, lines.size) {
        if (idx >= 0 && !dragging && System.currentTimeMillis() > manualUntil) {
            offset.animateTo(idx.toFloat().coerceIn(0f, last), spring(stiffness = Spring.StiffnessLow))
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val lineW = maxWidth * 0.62f
        Box(
            Modifier.fillMaxSize()
                .pointerInput(lines) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            manualUntil = Long.MAX_VALUE
                            scope.launch { offset.stop() }
                        },
                        onDragEnd = {
                            dragging = false
                            manualUntil = System.currentTimeMillis() + 4000
                            scope.launch {
                                offset.animateTo(
                                    offset.value.roundToInt().toFloat().coerceIn(0f, last),
                                    spring(stiffness = Spring.StiffnessLow),
                                )
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            manualUntil = System.currentTimeMillis() + 4000
                        },
                        onVerticalDrag = { _, dragAmount ->
                            val d = -dragAmount / arcPx
                            scope.launch { offset.snapTo((offset.value + d).coerceIn(0f, last)) }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val cur = offset.value
            val span = 8
            val from = (cur - span).toInt().coerceAtLeast(0)
            val to = (cur + span).toInt().coerceAtMost(lines.lastIndex)
            for (i in from..to) {
                val d = i - cur
                val theta = d * stepDeg
                val rad = theta * 0.017453292f
                val x = rPx * sin(rad)
                val y = -rPx * cos(rad)
                val fade = (1f - abs(d) / (span + 1f)).coerceIn(0f, 1f)
                val isCur = abs(d) < 0.5f
                Text(
                    zhText(lines[i].text),
                    Modifier
                        .width(lineW)
                        .graphicsLayer {
                            translationX = x
                            translationY = y
                            rotationZ = theta
                            alpha = 0.18f + 0.82f * fade
                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                        }
                        .clickable(enabled = lines[i].timeMs >= 0) {
                            manualUntil = System.currentTimeMillis() + 6000
                            onSeek(lines[i].timeMs)
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White,
                    fontSize = if (isCur) 16.sp else 13.sp,
                    lineHeight = if (isCur) 22.sp else 18.sp,
                    fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 竖排滚动歌词（与参考图一致）。
 *
 * 当前句居中高亮，前后几句依次淡显；可以上下滑动浏览，
 * 点某一句直接跳到那一句。跟播时自动把当前句滚回正中，
 * 手动滑动后暂停几秒再恢复。
 */
@Composable
fun VerticalLyrics(
    lines: List<LrcLine>,
    idx: Int,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    curColor: Color = Color.White,
    dimColor: Color = Color.White.copy(alpha = 0.42f),
    onSeek: (Long) -> Unit,
) {
    if (lines.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = curColor, modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Txt("正在加载歌词…", 14, color = dimColor)
                }
            } else {
                Txt("暂无歌词", 14, color = dimColor)
            }
        }
        return
    }
    val st = androidx.compose.foundation.lazy.rememberLazyListState()
    var manualUntil by remember { mutableLongStateOf(0L) }
    var auto by remember { mutableStateOf(false) }
    LaunchedEffect(st) {
        androidx.compose.runtime.snapshotFlow { st.isScrollInProgress }
            .collect { if (it && !auto) manualUntil = System.currentTimeMillis() + 4000 }
    }
    LaunchedEffect(idx, lines.size) {
        if (idx >= 0 && System.currentTimeMillis() > manualUntil) {
            auto = true
            try { st.animateScrollToItem(idx) } finally { auto = false }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = st,
            contentPadding = PaddingValues(vertical = maxHeight / 2 - 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(lines) { i, l ->
                val cur = i == idx
                // 微微旋转：以当前行为基准，越靠外的行倾斜一点点，形成很轻的扇形
                val d = if (idx >= 0) (i - idx).toFloat() else 0f
                val rot = (d * 2.2f).coerceIn(-9f, 9f)
                val fade = (1f - abs(d) / 9f).coerceIn(0.32f, 1f)
                Text(
                    zhText(l.text),
                    Modifier.fillMaxWidth()
                        .graphicsLayer {
                            rotationZ = rot
                            scaleX = 1f - 0.015f * abs(d)
                            scaleY = 1f - 0.015f * abs(d)
                            alpha = fade
                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                        }
                        .clickable(enabled = l.timeMs >= 0) {
                            manualUntil = System.currentTimeMillis() + 6000
                            onSeek(l.timeMs)
                        }
                        .padding(horizontal = 24.dp, vertical = 6.dp),
                    color = if (cur) curColor else dimColor,
                    fontSize = if (cur) 19.sp else 16.sp,
                    lineHeight = if (cur) 30.sp else 26.sp,
                    fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 简洁版单色进度条：细轨道 + 白色进度，可拖动 */
@Composable
fun SimpleSeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    var w by remember { mutableIntStateOf(1) }
    val frac = drag ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        Modifier.fillMaxWidth(0.92f).height(20.dp)
            .onSizeChanged { w = it.width.coerceAtLeast(1) }
            .pointerInput(duration) {
                detectDragGestures(
                    onDragStart = { o -> drag = (o.x / w).coerceIn(0f, 1f) },
                    onDrag = { change, _ -> drag = (change.position.x / w).coerceIn(0f, 1f) },
                    onDragEnd = {
                        drag?.let { f -> if (duration > 0) onSeek((f * duration).toLong()) }
                        drag = null
                    },
                    onDragCancel = { drag = null },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.22f), CircleShape))
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(3.dp).background(Color.White, CircleShape))
    }
}