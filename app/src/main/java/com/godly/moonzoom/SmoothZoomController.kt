package com.godly.moonzoom

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Zoom mượt, phản hồi tức thì:
 *  - setTarget(): theo ngón tay/chụm, bám rất sát (tau ≈ 25ms), đến đích là dừng hẳn.
 *  - animateTo(): chạy có thời lượng cố định (ease-out) rồi kết thúc đúng đích -> không còn "đuôi" chậm
 *    khi quay về 1x.
 * Đồng bộ theo khung hình bằng withFrameNanos. UI chỉ đọc [value] ở giai đoạn vẽ nên không recomposition.
 * [onFrame] nhận (zoom, dtGiây, đã tới đích).
 */
class SmoothZoomController(
    private val scope: CoroutineScope,
    private val onFrame: (Float, Float, Boolean) -> Unit
) {
    companion object {
        const val MIN = 1f
        const val MAX = 100f
        private const val TAU = 0.025f
        val PRESETS = listOf(1f, 2f, 5f, 10f, 30f, 50f, 100f)

        fun clamp(v: Float): Float = if (v.isNaN()) MIN else v.coerceIn(MIN, MAX)
    }

    var value by mutableFloatStateOf(MIN)
        private set

    var target: Float = MIN
        private set

    private var job: Job? = null
    private var animFrom = MIN
    private var animStartNs = 0L
    private var animDurNs = 0L
    private var animating = false

    /** Bám theo ngón tay. */
    fun setTarget(v: Float, immediate: Boolean = false) {
        target = clamp(v)
        animating = false
        if (immediate) {
            job?.cancel()
            value = target
            onFrame(value, 0.016f, true)
        } else {
            ensureLoop()
        }
    }

    /** Chạy tới [v] trong [durationMs] mili-giây (ease-out), kết thúc chính xác tại v. */
    fun animateTo(v: Float, durationMs: Int = 260) {
        target = clamp(v)
        animFrom = value
        animStartNs = 0L
        animDurNs = durationMs * 1_000_000L
        animating = true
        ensureLoop()
    }

    private fun ensureLoop() {
        if (job?.isActive == true) return
        job = scope.launch {
            var last = withFrameNanos { it }
            while (isActive) {
                val now = withFrameNanos { it }
                val dt = ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                last = now
                if (animating) {
                    if (animStartNs == 0L) animStartNs = now
                    val t = ((now - animStartNs).toFloat() / animDurNs).coerceIn(0f, 1f)
                    val e = 1f - (1f - t).pow(3)
                    if (t >= 1f) {
                        value = target; animating = false
                        onFrame(value, dt, true)
                        break
                    }
                    val a = ln(animFrom)
                    val b = ln(target)
                    value = clamp(exp(a + (b - a) * e))
                    onFrame(value, dt, false)
                    continue
                }
                val cur = value
                val t = target
                val d = ln(t / cur)
                if (abs(d) < 0.0006f) {
                    value = t
                    onFrame(t, dt, true)
                    break
                }
                val a = 1f - exp(-dt / TAU)
                value = clamp(cur * exp(d * a))
                onFrame(value, dt, false)
            }
        }
    }
}
