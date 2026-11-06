package com.godly.moonzoom

import androidx.camera.core.Camera
import androidx.camera.view.PreviewView
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Áp zoom lên camera.
 *  - Phần 1x..nativeMax: do camera xử lý (setZoomRatio) -> ảnh nét thật.
 *  - Phần vượt nativeMax: phóng số trên preview. Hệ số phóng chỉ phụ thuộc zoom (= zoom/nativeMax) chứ không
 *    phụ thuộc độ trễ của camera nên preview không bị "rung".
 *  - [onFx] báo (extra, vận tốc zoom 0..1) để bật hiệu ứng nét/nhoè chuyển động (Android 13+).
 */
class ZoomApplier(private val previewView: PreviewView) {
    var camera: Camera? = null
    var nativeMax: Float = 1f
    var onFx: ((Float, Float) -> Unit)? = null

    private var lastNative = -1f
    private var smoothVel = 0f
    private var lastZoom = 1f

    var extra: Float = 1f
        private set

    fun reset() {
        lastNative = -1f
        extra = 1f
        lastZoom = 1f
        smoothVel = 0f
        previewView.scaleX = 1f
        previewView.scaleY = 1f
        onFx?.invoke(1f, 0f)
    }

    /** Dùng sau khi camera mở lại (quay lại từ album...) vì camera có thể đã về zoom mặc định. */
    fun forceReapply(zoom: Float) {
        lastNative = -1f
        apply(zoom, 0.016f, true)
    }

    fun apply(zoom: Float, dt: Float, done: Boolean) {
        val native = min(zoom, nativeMax).coerceAtLeast(1f)
        val cam = camera
        if (cam != null) {
            val changed = lastNative <= 0f || abs(native - lastNative) / lastNative > 0.0015f
            if (changed || (done && native != lastNative)) {
                cam.cameraControl.setZoomRatio(native)
                lastNative = native
            }
        }
        extra = max(1f, zoom / nativeMax)
        previewView.scaleX = extra
        previewView.scaleY = extra

        val vel = abs(ln(zoom / lastZoom)) / max(dt, 0.001f)   // đơn vị: ln-zoom / giây
        lastZoom = zoom
        smoothVel += (vel - smoothVel) * 0.35f
        val v = if (done) 0f else (smoothVel / 3f).coerceIn(0f, 1f)
        onFx?.invoke(extra, v)
    }
}
