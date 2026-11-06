package com.godly.moonzoom

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

val DOCK_HEIGHT: Dp = 270.dp

fun formatZoom(z: Float): String = when {
    z >= 99.95f -> "100x"
    abs(z - z.roundToInt()) < 0.05f -> "${z.roundToInt()}x"
    z < 10f -> String.format(Locale.US, "%.1fx", z)
    else -> String.format(Locale.US, "%.0fx", z)
}

/** Trạng thái hiện/ẩn thước zoom. Chỉ `expanded` là state; thời điểm chạm lưu thường để không recomposition. */
class ZoomUi {
    var expanded by mutableStateOf(false)
    var lastTouch: Long = 0L

    fun touch() {
        lastTouch = SystemClock.elapsedRealtime()
        if (!expanded) expanded = true
    }
}

private const val SPAN_DEG = 300f
private val MAJORS = listOf(1f, 2f, 3f, 5f, 10f, 20f, 30f, 50f, 100f)

private class Tick(val frac: Float, val value: Float, val level: Int)

private fun buildTicks(): List<Tick> {
    val logMax = ln(SmoothZoomController.MAX)
    val out = ArrayList<Tick>()
    fun add(v: Float) {
        val major = MAJORS.any { abs(it - v) < 0.001f }
        val medium = !major && abs(v * 2f - (v * 2f).roundToInt()) < 0.001f
        val exact = MAJORS.firstOrNull { abs(it - v) < 0.001f } ?: v
        out.add(Tick(ln(exact) / logMax, exact, if (major) 2 else if (medium) 1 else 0))
    }
    var v = 1f
    while (v < 3f - 0.001f) { add(v); v += 0.1f }
    v = 3f
    while (v < 10f - 0.001f) { add(v); v += 0.5f }
    v = 10f
    while (v < 20f - 0.001f) { add(v); v += 1f }
    v = 20f
    while (v < 50f - 0.001f) { add(v); v += 2f }
    v = 50f
    while (v < 100f - 0.001f) { add(v); v += 5f }
    add(100f)
    return out
}

private fun nearestPreset(z: Float): Int =
    SmoothZoomController.PRESETS.indexOfFirst { abs(ln(z / it)) < 0.02f }

/**
 * Đế điều khiển hình vòm: một cung tròn rộng ĐÚNG bằng chiều ngang màn hình (bán kính tính từ bề rộng và
 * chiều cao đế nên không bị đứt ở hai bên). Thước zoom nằm ở viền vòm; các nút (album, AI, chụp, WM, đổi cam)
 * nằm trong vòm nên luôn khớp với vòng tròn.
 */
@Composable
fun ZoomDock(
    controller: SmoothZoomController,
    ui: ZoomUi,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val textMeasurer = rememberTextMeasurer()
    val haptic = LocalHapticFeedback.current
    val ticks = remember { buildTicks() }
    val logMax = remember { ln(SmoothZoomController.MAX) }
    val labelStyle = remember { TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
    val valueStyle = remember { TextStyle(color = ZoomYellow, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
    val labels = remember(textMeasurer) {
        MAJORS.associateWith { textMeasurer.measure(formatZoom(it).removeSuffix("x"), labelStyle) }
    }
    val tickAlpha = animateFloatAsState(if (ui.expanded) 1f else 0f, tween(180), label = "tickA")

    LaunchedEffect(Unit) {
        snapshotFlow { nearestPreset(controller.value) }
            .distinctUntilChanged()
            .collect { if (it >= 0 && ui.expanded) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }

    Box(modifier.fillMaxWidth().height(DOCK_HEIGHT)) {
        Canvas(Modifier.fillMaxSize().clipToBounds()) {
            val w = size.width
            val d = size.height - 12.dp.toPx()
            val r = ((w / 2f) * (w / 2f) + d * d) / (2f * d)
            val topY = 12.dp.toPx()
            val cx = w / 2f
            val cy = topY + r
            val center = Offset(cx, cy)

            // thân vòm kính "có như không" + viền xanh phát sáng
            drawCircle(
                Brush.verticalGradient(
                    listOf(Blue.copy(alpha = 0.16f), DeepBlue.copy(alpha = 0.50f)),
                    startY = topY, endY = size.height
                ),
                radius = r, center = center
            )
            drawCircle(Blue.copy(alpha = 0.14f), radius = r, center = center, style = Stroke(7.dp.toPx()))
            drawCircle(
                Brush.verticalGradient(listOf(Blue.copy(alpha = 0.95f), Blue.copy(alpha = 0.30f)), startY = topY, endY = size.height),
                radius = r, center = center, style = Stroke(1.4.dp.toPx())
            )

            val ta = tickAlpha.value
            if (ta > 0.01f) {
                val z = controller.value
                val frac = ln(z.coerceIn(1f, SmoothZoomController.MAX)) / logMax
                val limit = (asin(min(1f, (w / 2f) / r)) * 180f / PI.toFloat()) - 3f
                val rim = r - 3.dp.toPx()
                for (t in ticks) {
                    val theta = (t.frac - frac) * SPAN_DEG
                    if (abs(theta) > limit) continue
                    val rad = (theta * PI / 180.0).toFloat()
                    val s = sin(rad)
                    val c = cos(rad)
                    val len = (if (t.level == 2) 17.dp else if (t.level == 1) 12.dp else 7.dp).toPx()
                    val fade = (1f - (abs(theta) / limit).pow(2.4f)) * ta
                    val a = fade * (if (t.level == 2) 1f else 0.75f)
                    drawLine(
                        Color.White.copy(alpha = a),
                        Offset(cx + rim * s, cy - rim * c),
                        Offset(cx + (rim - len) * s, cy - (rim - len) * c),
                        strokeWidth = (if (t.level == 2) 2.2.dp else 1.2.dp).toPx(),
                        cap = StrokeCap.Round
                    )
                    if (t.level == 2) {
                        val lab = labels[t.value]
                        if (lab != null) {
                            val lr = rim - len - 13.dp.toPx()
                            val p = Offset(cx + lr * s, cy - lr * c)
                            rotate(theta, pivot = p) {
                                drawText(
                                    textLayoutResult = lab,
                                    topLeft = Offset(p.x - lab.size.width / 2f, p.y - lab.size.height / 2f),
                                    alpha = fade
                                )
                            }
                        }
                    }
                }
                val tri = Path().apply {
                    moveTo(cx - 6.dp.toPx(), topY + 2.dp.toPx())
                    lineTo(cx + 6.dp.toPx(), topY + 2.dp.toPx())
                    lineTo(cx, topY + 15.dp.toPx())
                    close()
                }
                drawPath(tri, ZoomYellow.copy(alpha = ta))
                val vt = textMeasurer.measure(formatZoom(z), valueStyle)
                drawText(vt, topLeft = Offset(cx - vt.size.width / 2f, topY + 36.dp.toPx()), alpha = ta)
            }
        }

        // vùng kéo để zoom (phần đỉnh vòm) + nút 1x
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(104.dp)
                .pointerInput(Unit) {
                    val topY = 12.dp.toPx()
                    val dd = DOCK_HEIGHT.toPx() - topY
                    val rr = ((size.width / 2f) * (size.width / 2f) + dd * dd) / (2f * dd)
                    val pxPerFull = rr * (SPAN_DEG * PI / 180.0).toFloat()
                    detectHorizontalDragGestures(onDragStart = { ui.touch() }) { change, dx ->
                        change.consume()
                        val f = (ln(controller.target) / logMax - dx / pxPerFull).coerceIn(0f, 1f)
                        controller.setTarget(if (f >= 0.9999f) SmoothZoomController.MAX else exp(f * logMax))
                        ui.touch()
                    }
                }
        ) {
            if (!ui.expanded) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 40.dp)) {
                    ResetZoomChip(controller)
                }
            }
        }
        content()
    }
}

/** Chỉ còn một nút: hiển thị zoom hiện tại, chạm để về 1x (có hoạt ảnh ngắn, không bị trễ). */
@Composable
private fun ResetZoomChip(controller: SmoothZoomController) {
    val z = controller.value
    val atOne = z < 1.05f
    GlassButton(Modifier.width(64.dp).height(36.dp), selected = atOne, onClick = {
        if (!atOne) controller.animateTo(1f, 260)
    }) {
        androidx.compose.material3.Text(
            formatZoom(if (atOne) 1f else z),
            color = if (atOne) Color.White else ZoomYellow,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun ZoomAutoCollapse(ui: ZoomUi) {
    LaunchedEffect(ui.expanded) {
        while (ui.expanded) {
            delay(250)
            if (SystemClock.elapsedRealtime() - ui.lastTouch > 2200) ui.expanded = false
        }
    }
}
