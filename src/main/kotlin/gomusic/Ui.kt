package gomusic

import kotlin.math.abs
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay

/** 圆周率，算圆弧用 */
private const val PI_F = 3.14159265f

/**
 * 相邻两行的纵向间距（dp）——这就是「行距」。
 * 与可见行数此消彼长：间距越大，一屏能放的歌词越少。
 */
private const val ARC_ROW_SPACING_DP = 63f

/**
 * 最外侧那行的最大倾斜角（度）。
 *
 * 圆心半径不再拍脑袋给倍数，而是由这个角度反推：
 *   r = 可见行数 × 行距 ÷ 最大倾斜角 × [ARC_RADIUS_BOOST]
 * 这样不管行距、屏幕怎么变，弧的弯曲程度始终稳定在能看清的一档，
 * 也不会因为半径过大把弧压成一条直线。
 */
private const val ARC_MAX_TILT_DEG = 20f

/**
 * 圆心再拉远的倍数：1.0 = 按倾角刚好算出的半径，越大圆心越远、弧越平缓。
 * 想让歌词摆得更开、更接近平铺，就调大它。
 */
// 圆心远近：< 1 把圆心拉近，弧更弯；> 1 拉远，弧更平。
// 桌面端屏幕高、可见行数多，按公式推出的半径会非常大（弧几乎成直线），
// 所以这里取 0.65 把圆心拉回来，让最外侧行的倾角约 30°，弧看得出来。
private const val ARC_RADIUS_BOOST = 0.65f

/**
 * 拖动灵敏度。行距越大，滑过一整行需要的手指距离就越长，
 * 这里放大一点让滑动跟手（1.0 = 手指移动多少、歌词就移动多少）。
 */
private const val ARC_DRAG_SENSITIVITY = 2.5f

val BgColor = Color(0xFF121014)
val SurfaceColor = Color(0xFF1C1A20)
val SurfaceHiColor = Color(0xFF2A2730)
val OnColor = Color(0xFFECE6F0)
val MutedColor = Color(0xFFA8A2B0)

@Composable
fun GoTheme(
    primary: Color,
    font: androidx.compose.ui.text.font.FontFamily? = null,
    content: @Composable () -> Unit,
) {
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
        typography = appTypography(font),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalAppFont provides font,
        ) { content() }
    }
}

/** 当前全局字体；null 表示用系统字体 */
val LocalAppFont = androidx.compose.runtime.compositionLocalOf<androidx.compose.ui.text.font.FontFamily?> { null }

/**
 * 把全局字体套到整套排版上。
 * [font] 为 null 时用系统字体，保持原本的样子。
 */
fun appTypography(font: androidx.compose.ui.text.font.FontFamily?): androidx.compose.material3.Typography {
    if (font == null) return androidx.compose.material3.Typography()
    val b = androidx.compose.material3.Typography()
    return androidx.compose.material3.Typography(
        displayLarge = b.displayLarge.copy(fontFamily = font),
        displayMedium = b.displayMedium.copy(fontFamily = font),
        displaySmall = b.displaySmall.copy(fontFamily = font),
        headlineLarge = b.headlineLarge.copy(fontFamily = font),
        headlineMedium = b.headlineMedium.copy(fontFamily = font),
        headlineSmall = b.headlineSmall.copy(fontFamily = font),
        titleLarge = b.titleLarge.copy(fontFamily = font),
        titleMedium = b.titleMedium.copy(fontFamily = font),
        titleSmall = b.titleSmall.copy(fontFamily = font),
        bodyLarge = b.bodyLarge.copy(fontFamily = font),
        bodyMedium = b.bodyMedium.copy(fontFamily = font),
        bodySmall = b.bodySmall.copy(fontFamily = font),
        labelLarge = b.labelLarge.copy(fontFamily = font),
        labelMedium = b.labelMedium.copy(fontFamily = font),
        labelSmall = b.labelSmall.copy(fontFamily = font),
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
        // 从 LocalAppFont 取，保证所有 Txt 都跟着全局字体走
        fontFamily = LocalAppFont.current,
        maxLines = maxLines,
        // 不限制行数时用 Clip：Ellipsis 需要按「最多几行」去内部的行缓冲里取行，
        // maxLines 是 Int.MAX_VALUE 时那个缓冲长度与实际行数对不上，
        // 长文本（比如设置页里的路径）会抛 IndexOutOfBounds。
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
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
 * 圆弧歌词：歌词排在一个真正的圆上。
 *
 * 圆心在左侧，歌词沿圆右侧的弧排列，
 * 每行的位置（沿弧的横纵偏移）与倾斜角（沿半径方向）都由同一个圆心角算出，
 * 所以行与行之间的相对关系是真实的圆周几何，不是各自硬歪一个角度。
 *
 * 定位按「句」走：一句唱完立刻滚到下一句，不做跟播放进度的匀速旋转。
 * 可以上下滑动手动翻看；停手 3 秒后自动回到当前句，中途再滑会重新计时。
 * 当前句用主题色高亮。
 */
@Composable
fun ArcLyrics(
    lines: List<LrcLine>,
    idx: Int,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    curColor: Color = Color.White,
    onSeek: (Long) -> Unit,
    rowSpacingDp: Float = ARC_ROW_SPACING_DP,
    radiusBoost: Float = ARC_RADIUS_BOOST,
    minSpan: Int = 1,
    curSp: Int = 19,
    otherSp: Int = 15,
) {
    if (lines.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = curColor, modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
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
    ArcContent(
        lines = lines, idx = idx, modifier = modifier,
        curColor = curColor, onSeek = onSeek,
        rowSpacingDp = rowSpacingDp, radiusBoost = radiusBoost,
        minSpan = minSpan, curSp = curSp, otherSp = otherSp,
    )
}

@Composable
private fun ArcContent(
    lines: List<LrcLine>,
    idx: Int,
    modifier: Modifier,
    curColor: Color,
    onSeek: (Long) -> Unit,
    rowSpacingDp: Float,
    radiusBoost: Float,
    minSpan: Int,
    curSp: Int,
    otherSp: Int,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val rowPxDrag = with(density) { (rowSpacingDp / ARC_DRAG_SENSITIVITY).dp.toPx() }.coerceAtLeast(1f)
    // 按句定位：当前句索引变了多少，圆盘就转多少，不做匀速旋转
    val base by animateFloatAsState(
        targetValue = idx.coerceAtLeast(0).toFloat(),
        animationSpec = tween(400),
        label = "arcBase",
    )
    // 手动滑动偏移：直接改状态（不用协程 snapTo），每个拖动事件立即生效，滑动才跟手
    var dragOff by remember { mutableFloatStateOf(0f) }
    // 停手后回位用的动画偏移
    val animOff = remember { Animatable(0f) }
    var manualAt by remember { mutableLongStateOf(0L) }
    // 位置在这里统一裁剪，边界只依赖歌词行数，不会在拖动中跳变
    val cur = (base + animOff.value + dragOff)
        .coerceIn(-0.6f, lines.lastIndex.toFloat() + 0.6f)
    // 每滑一次就更新 manualAt，这个协程会重启并重新计时 3 秒
    LaunchedEffect(manualAt) {
        if (manualAt == 0L) return@LaunchedEffect
        delay(3000)
        // 把手动偏移交给动画，再平滑回到跟播位置
        animOff.snapTo(animOff.value + dragOff)
        dragOff = 0f
        animOff.animateTo(0f, tween(500))
    }

    BoxWithConstraints(
        modifier.fillMaxSize()
            .pointerInput(lines) {
                detectVerticalDragGestures(
                    onDragStart = { manualAt = System.currentTimeMillis() },
                    onDragEnd = { manualAt = System.currentTimeMillis() },
                    onDragCancel = { manualAt = System.currentTimeMillis() },
                    onVerticalDrag = { _, dragAmount ->
                        // 拖动过程只累加，不做裁剪：
                        // 之前按 base 算上下限，而播放时 base 一直在动，
                        // 边界随之跳变会把 dragOff 反复夹回去，看着就不跟手。
                        // 裁剪改到渲染时统一做
                        dragOff -= dragAmount / rowPxDrag
                        manualAt = System.currentTimeMillis()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // 行距固定，可见行数由屏幕高度决定（至少 3 行，最多 15 行）
        val rowSpan = rowSpacingDp
        val rows = (maxHeight.value / rowSpan).toInt().coerceIn(3, 15)
        val span = (rows / 2).coerceAtLeast(minSpan)
        // 圆心半径由「最外侧行的倾斜角」反推，弧的弯曲程度保持稳定
        val tiltRad = ARC_MAX_TILT_DEG * PI_F / 180f
        val rDp = (span * rowSpan / tiltRad * radiusBoost).coerceAtLeast(maxHeight.value * 0.5f)
        // 圆心角由间距 / 半径推出（弧长 s = r·θ），几何自洽
        val stepRad = rowSpan / rDp
        // 半径换算成像素：offset 用的是像素，之前直接拿 dp 数值当像素用，
        // 在高密度屏上实际半径只有设计的 1/density，弧会比预期弯得多
        val rPx = with(density) { rDp.dp.toPx() }
        val shift = (rPx * (1 - cos(span * stepRad))) / 2
        val lineW = maxWidth * 1.0f
        // 多渲染一行作缓冲：让最外侧的行在透明度已经衰减到 0 之后
        // 才被移出渲染范围，进出就不会「啪」地出现或消失
        val edge = span + 1
        val from = (cur - edge).toInt().coerceAtLeast(0)
        val to = (cur + edge).toInt().coerceAtMost(lines.lastIndex)
        for (i in from..to) {
            val d = i - cur
            val th = d * stepRad
            val x = -rPx * (1 - cos(th)) + shift
            val y = rPx * sin(th)
            // 边缘平滑淡出：用 smoothstep，最外侧处的值与斜率都是 0，
            // 行在边缘进出时不会有突跳（原来是线性再加 0.25 下限，
            // 一行从「不渲染」直接跳到 0.25，所以看着很生硬）
            val t = (abs(d) / span.toFloat()).coerceIn(0f, 1f)
            val fade = 1f - (t * t * (3f - 2f * t))
            val isCur = i == idx
            // 点击区域收紧到文字实际占用的宽度：两侧留白不响应点击
            Box(
                Modifier
                    // 宽度贴合文字本身，不再固定成整行宽：
                    // 之前点右侧空白也会命中这一行，看着就像「点别处却跳转了」
                    .widthIn(max = lineW)
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .graphicsLayer {
                        // 外框跟着文字一起转，两者的边始终平行，不会被斜切
                        rotationZ = th * 180f / PI_F
                        alpha = fade
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    zhText(lines[i].text),
                    Modifier
                        // clickable 贴着文字宽度：命中框不再铺满整行，
                        // 行与行之间按行距排开，不会互相重叠跳错行
                        .clickable(enabled = lines[i].timeMs >= 0 && fade > 0.12f) {
                            // 点了就立刻回到跟播：清掉手动偏移，
                            // 位置交给播放进度重新算，避免停在手动位置上跳错行
                            dragOff = 0f
                            scope.launch { animOff.snapTo(0f) }
                            manualAt = 0L
                            onSeek(lines[i].timeMs)
                        }
                        .widthIn(max = lineW)
                        .graphicsLayer {
                            // 圆心角即切线角：不加截断，文字方向才始终贴合圆。
                            // 放在最内层，只影响绘制，不影响上面的命中框尺寸
                            rotationZ = 0f
                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    // 当前句用主题色
                    color = if (isCur) curColor else Color.White.copy(alpha = 0.5f),
                    fontSize = if (isCur) curSp.sp else otherSp.sp,
                    lineHeight = if (isCur) (curSp * 1.5f).sp else (otherSp * 1.5f).sp,
                    fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                    // 左侧对齐
                    textAlign = TextAlign.Start,
                    // 最多两行：长句折行后行高会超过行距，
                    // 相邻两行的命中框就会上下重叠，点上一行可能跳到下一行
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 简洁版单色进度条：细轨道 + 白色进度，可点可拖。
 *
 * 触摸区比轨道本身高不少（32dp），并且加了圆点滑块，
 * 手指容易按准；不拖动时圆点淡出，保持原本的简洁感。
 */
@Composable
fun SimpleSeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    var w by remember { mutableIntStateOf(1) }
    val frac = drag ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        Modifier.fillMaxWidth().height(32.dp)
            .onSizeChanged { w = it.width.coerceAtLeast(1) }
            .pointerInput(duration) {
                // 单击也能跳：不用非得按住拖
                detectTapGestures { o ->
                    if (duration > 0) onSeek(((o.x / w).coerceIn(0f, 1f) * duration).toLong())
                }
            }
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
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier.size(if (drag != null) 12.dp else 8.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (drag != null) 1f else 0.75f)),
            )
        }
    }
}