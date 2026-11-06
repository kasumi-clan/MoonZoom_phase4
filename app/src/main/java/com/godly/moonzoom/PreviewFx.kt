package com.godly.moonzoom

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.view.View
import androidx.annotation.RequiresApi
import kotlin.math.abs
import kotlin.math.ln

/**
 * Hiệu ứng GPU cho preview (Android 13+): làm nét khi phóng số và nhoè chuyển động theo hướng zoom (radial).
 * Shader AGSL chỉ vài lần lấy mẫu nên rất nhẹ; chỉ cập nhật khi giá trị đổi đáng kể.
 */
@RequiresApi(33)
class PreviewFx(private val view: View) {
    private val shader = RuntimeShader(
        """
        uniform shader content;
        uniform float2 center;
        uniform float blur;
        uniform float sharp;
        half4 main(float2 p) {
            float2 d = center - p;
            half4 acc = content.eval(p);
            if (blur > 0.0) {
                acc += content.eval(p + d * blur * 0.015);
                acc += content.eval(p + d * blur * 0.030);
                acc += content.eval(p + d * blur * 0.045);
                acc += content.eval(p + d * blur * 0.060);
                acc = acc / 5.0;
            }
            if (sharp > 0.0) {
                half4 n = content.eval(p + float2(1.0, 0.0)) + content.eval(p - float2(1.0, 0.0))
                        + content.eval(p + float2(0.0, 1.0)) + content.eval(p - float2(0.0, 1.0));
                acc = acc + (acc - n * 0.25) * sharp;
            }
            return acc;
        }
        """.trimIndent()
    )
    private var lastBlur = -1f
    private var lastSharp = -1f
    private var active = false

    fun update(extra: Float, velocity: Float) {
        val sharp = if (extra > 1.001f) (0.35f + 0.12f * ln(extra)).coerceAtMost(1.1f) else 0f
        val blur = velocity.coerceIn(0f, 1f)
        if (abs(blur - lastBlur) < 0.02f && abs(sharp - lastSharp) < 0.02f) return
        lastBlur = blur
        lastSharp = sharp
        try {
            if (blur < 0.02f && sharp < 0.02f) {
                if (active) view.setRenderEffect(null)
                active = false
                return
            }
            shader.setFloatUniform("center", view.width / 2f, view.height / 2f)
            shader.setFloatUniform("blur", if (blur < 0.02f) 0f else blur)
            shader.setFloatUniform("sharp", sharp)
            view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
            active = true
        } catch (e: Throwable) {
            active = false
        }
    }
}
