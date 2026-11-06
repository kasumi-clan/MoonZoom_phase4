package com.godly.moonzoom

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

val Blue = Color(0xFF3D9BFF)
val MoonBlue = Color(0xFF8CC8FF)
val DeepBlue = Color(0xFF061428)
val ZoomYellow = Color(0xFFFFD60A)
val ShutterAmber = Color(0xFFFFB000)

/**
 * Liquid Glass "có như không": nền trong suốt pha xanh rất nhẹ + viền xanh phát sáng, KHÔNG có viền trắng.
 * Không dùng blur thời gian thực nên không tốn GPU; brush được cache theo kích thước.
 */
fun Modifier.liquidGlass(
    corner: Dp = 999.dp,
    tint: Color = Blue,
    strength: Float = 1f,
    selected: Boolean = false
): Modifier = this.drawWithCache {
    val r = min(corner.toPx(), min(size.width, size.height) / 2f)
    val cr = CornerRadius(r, r)
    val fill = Brush.verticalGradient(
        listOf(
            tint.copy(alpha = ((if (selected) 0.32f else 0.14f) * strength).coerceAtMost(0.5f)),
            DeepBlue.copy(alpha = if (selected) 0.34f else 0.22f)
        )
    )
    val edge = Brush.linearGradient(
        listOf(
            Blue.copy(alpha = if (selected) 1f else 0.85f),
            Blue.copy(alpha = 0.22f),
            Blue.copy(alpha = if (selected) 0.9f else 0.55f)
        ),
        start = Offset.Zero, end = Offset(size.width, size.height)
    )
    val sw = 1.3.dp.toPx()
    val gw = 4.dp.toPx()
    onDrawBehind {
        drawRoundRect(brush = fill, cornerRadius = cr)
        if (selected) drawRoundRect(color = Blue.copy(alpha = 0.22f), cornerRadius = cr, style = Stroke(gw))
        drawRoundRect(brush = edge, cornerRadius = cr, style = Stroke(sw))
    }
}

/** Nhún lò xo khi nhấn; trạng thái chỉ đọc ở giai đoạn vẽ. */
fun Modifier.pressScale(source: InteractionSource, pressedScale: Float = 0.9f): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val s = animateFloatAsState(if (pressed) pressedScale else 1f, spring(dampingRatio = 0.55f, stiffness = 650f), label = "press")
    graphicsLayer {
        scaleX = s.value
        scaleY = s.value
    }
}

@Composable
fun GlassButton(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    corner: Dp = 999.dp,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val src = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(src)
            .liquidGlass(corner = corner, selected = selected)
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
fun GlassLabel(text: String, selected: Boolean = false, fontSize: Int = 12) {
    Text(
        text,
        color = if (selected) Color.White else Color.White.copy(alpha = 0.88f),
        fontSize = fontSize.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
        textAlign = TextAlign.Center
    )
}

/** Ngôi sao lấp lánh 4 cánh, vẽ bằng vài đường thẳng nên rất nhẹ. */
fun DrawScope.drawSparkle(c: Offset, size: Float, alpha: Float, color: Color = Color.White) {
    if (alpha <= 0.02f) return
    val col = color.copy(alpha = alpha.coerceIn(0f, 1f))
    val w = (size * 0.16f).coerceAtLeast(1f)
    drawLine(col, Offset(c.x - size, c.y), Offset(c.x + size, c.y), strokeWidth = w, cap = StrokeCap.Round)
    drawLine(col, Offset(c.x, c.y - size), Offset(c.x, c.y + size), strokeWidth = w, cap = StrokeCap.Round)
    val d = size * 0.55f
    drawLine(col.copy(alpha = col.alpha * 0.6f), Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), strokeWidth = w * 0.7f, cap = StrokeCap.Round)
    drawLine(col.copy(alpha = col.alpha * 0.6f), Offset(c.x - d, c.y + d), Offset(c.x + d, c.y - d), strokeWidth = w * 0.7f, cap = StrokeCap.Round)
    drawCircle(col, radius = w * 0.9f, center = c)
}

/** Nút chụp theo mẫu: lõi trắng, khe đen, viền vàng cam; có vài điểm lấp lánh nhẹ, nhấn thì lõi co lại kiểu lò xo. */
@Composable
fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val disc = animateFloatAsState(if (pressed) 0.84f else 1f, spring(dampingRatio = 0.5f, stiffness = 700f), label = "disc")
    val ring = animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.6f, stiffness = 700f), label = "ringS")
    val inf = rememberInfiniteTransition(label = "sparkle")
    val phase = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "ph")
    Box(
        Modifier
            .size(84.dp)
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val rs = ring.value
            drawCircle(Color(0xFF0A0A0A), radius = (r - 6.dp.toPx()) * rs)
            drawCircle(ShutterAmber, radius = (r - 3.dp.toPx()) * rs, style = Stroke(6.dp.toPx()))
            drawCircle(
                if (enabled) Color.White else Color.White.copy(alpha = 0.5f),
                radius = (r - 12.dp.toPx()) * disc.value
            )
            if (enabled) {
                val ph = phase.value
                for (k in 0 until 3) {
                    val t = (ph + k / 3f) % 1f
                    val a = (sin(t * PI.toFloat())).let { it * it }
                    val ang = (k * 120f + 40f + ph * 40f) * PI.toFloat() / 180f
                    val rr = r - 3.dp.toPx()
                    val c = Offset(center.x + rr * cos(ang), center.y + rr * sin(ang))
                    drawSparkle(c, (3f + 5f * a).dp.toPx(), a * 0.95f, Color(0xFFFFE9A8))
                }
                val t2 = (ph * 1.7f) % 1f
                val a2 = sin(t2 * PI.toFloat()).let { it * it }
                drawSparkle(
                    Offset(center.x - r * 0.28f, center.y - r * 0.3f),
                    (2f + 4f * a2).dp.toPx(), a2 * 0.8f, Color(0xFFBFE0FF)
                )
            }
        }
    }
}

/**
 * Nút album hình tròn. Khi đang xử lý ảnh: hiện vòng 0..100% chạy quanh thumbnail kèm các điểm lấp lánh.
 * Chạm = mở album; nhấn giữ khi đang xử lý = hủy.
 */
@Composable
fun AlbumButton(thumb: ImageBitmap?, progress: Float?, onTap: () -> Unit, onLongPress: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val inf = rememberInfiniteTransition(label = "albumFx")
    val phase = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "aph")
    val p = animateFloatAsState(progress ?: 0f, tween(220), label = "aprog")
    Box(
        Modifier
            .size(58.dp)
            .pressScale(src)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { off ->
                        val press = PressInteraction.Press(off)
                        src.emit(press)
                        tryAwaitRelease()
                        src.emit(PressInteraction.Release(press))
                    },
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(50.dp)
                .liquidGlass(corner = 999.dp, selected = progress != null)
        ) {
            if (thumb != null) {
                Image(
                    thumb, contentDescription = "Album", contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            }
        }
        if (progress != null) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f - 2.dp.toPx()
                drawCircle(Color.Black.copy(alpha = 0.45f), radius = size.minDimension / 2f - 5.dp.toPx())
                val tl = Offset(center.x - r, center.y - r)
                val sz = androidx.compose.ui.geometry.Size(r * 2, r * 2)
                drawArc(Blue.copy(alpha = 0.25f), -90f, 360f, false, tl, sz, style = Stroke(3.dp.toPx()))
                drawArc(Blue.copy(alpha = 0.35f), -90f, 360f * p.value, false, tl, sz, style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
                drawArc(Color.White, -90f, 360f * p.value, false, tl, sz, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round))
                val ph = phase.value
                for (k in 0 until 3) {
                    val ang = (ph + k / 3f) * 2f * PI.toFloat()
                    val a = 0.5f + 0.5f * sin((ph * 3f + k) * PI.toFloat())
                    drawSparkle(
                        Offset(center.x + r * cos(ang), center.y + r * sin(ang)),
                        (2.5f + 3.5f * a).dp.toPx(), 0.55f + 0.45f * a, Color(0xFFCFE8FF)
                    )
                }
            }
            Text(
                "${(p.value * 100).toInt()}%",
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Thanh chỉnh sáng/tối dạng viên kính dọc: mặt trời ở trên, núm sáng ở giữa, kéo lên = sáng hơn. */
@Composable
fun ExposureControl(
    index: Float, lower: Int, upper: Int,
    onChange: (Float) -> Unit, onTouch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .size(44.dp, 168.dp)
            .liquidGlass(corner = 22.dp, strength = 0.8f)
            .pointerInput(lower, upper) {
                detectVerticalDragGestures(onDragStart = { onTouch() }) { change, _ ->
                    change.consume()
                    onTouch()
                    val pad = 30.dp.toPx()
                    val f = ((change.position.y - pad) / (size.height - 2 * pad)).coerceIn(0f, 1f)
                    onChange(upper - f * (upper - lower))
                }
            }
            .pointerInput(lower, upper) {
                detectTapGestures { off ->
                    onTouch()
                    val pad = 30.dp.toPx()
                    val f = ((off.y - pad) / (size.height - 2 * pad)).coerceIn(0f, 1f)
                    onChange(upper - f * (upper - lower))
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pad = 30.dp.toPx()
            val cx = size.width / 2f
            val f = if (upper > lower) ((upper - index) / (upper - lower)).coerceIn(0f, 1f) else 0.5f
            val ty = pad + f * (size.height - 2 * pad)
            // thân thanh
            drawLine(Blue.copy(alpha = 0.35f), Offset(cx, pad), Offset(cx, size.height - pad), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(MoonBlue, Offset(cx, size.height / 2f), Offset(cx, ty), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            // mặt trời nhỏ ở trên
            val sy = 15.dp.toPx()
            drawCircle(Color(0xFFFFE08A), radius = 3.6.dp.toPx(), center = Offset(cx, sy))
            for (k in 0 until 8) {
                val a = k * (PI.toFloat() / 4f)
                drawLine(
                    Color(0xFFFFE08A).copy(alpha = 0.9f),
                    Offset(cx + 6.dp.toPx() * cos(a), sy + 6.dp.toPx() * sin(a)),
                    Offset(cx + 8.6.dp.toPx() * cos(a), sy + 8.6.dp.toPx() * sin(a)),
                    strokeWidth = 1.3.dp.toPx(), cap = StrokeCap.Round
                )
            }
            // vầng trăng nhỏ ở dưới
            drawCircle(MoonBlue.copy(alpha = 0.85f), radius = 4.dp.toPx(), center = Offset(cx, size.height - 15.dp.toPx()))
            drawCircle(DeepBlue.copy(alpha = 0.95f), radius = 3.4.dp.toPx(), center = Offset(cx + 1.6.dp.toPx(), size.height - 16.4.dp.toPx()))
            // núm
            drawCircle(Blue.copy(alpha = 0.30f), radius = 15.dp.toPx(), center = Offset(cx, ty))
            drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(cx, ty))
            drawCircle(Blue, radius = 9.dp.toPx(), center = Offset(cx, ty), style = Stroke(2.dp.toPx()))
        }
    }
}
