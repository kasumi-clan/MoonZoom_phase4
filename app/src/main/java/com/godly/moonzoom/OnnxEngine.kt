package com.godly.moonzoom

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Chạy model ONNX hoàn toàn offline bằng ONNX Runtime (CPU, ARM64). Model được chuyển đổi từ .pth/.pt bằng
 * tools/convert_models.py (GitHub Actions) và đóng vào assets/models. Không có file model thì [load]
 * trả null và ghi lý do vào [errors]: app KHÔNG bao giờ báo model chạy nếu chưa chạy suy luận thật.
 */
object OnnxEngine {
    const val RETINEX_FILE = "retinexformer_256.onnx"
    const val SWINIR_FILE = "swinir_x4_64.onnx"

    class Model(val file: String, val session: OrtSession, val inputName: String, val tile: Int, val scale: Int)

    private var env: OrtEnvironment? = null
    private val models = HashMap<String, Model>()
    val errors = HashMap<String, String>()

    private fun environment(): OrtEnvironment {
        val e = env ?: OrtEnvironment.getEnvironment()
        env = e
        return e
    }

    fun has(context: Context, file: String): Boolean {
        if (File(context.filesDir, "models/$file").exists()) return true
        return try {
            context.assets.list("models")?.contains(file) == true
        } catch (e: Exception) {
            false
        }
    }

    private fun resolve(context: Context, file: String): File? {
        val out = File(context.filesDir, "models/$file")
        if (out.exists() && out.length() > 1000) return out
        return try {
            context.assets.open("models/$file").use { ins ->
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { ins.copyTo(it) }
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    fun load(context: Context, file: String, tile: Int, scale: Int): Model? {
        models[file]?.let { return it }
        val f = resolve(context, file)
        if (f == null) {
            errors[file] = "chưa có file $file trong APK (chưa chuyển đổi model sang ONNX)"
            return null
        }
        return try {
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(min(4, max(2, Runtime.getRuntime().availableProcessors() - 2)))
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            val s = environment().createSession(f.absolutePath, opts)
            val m = Model(file, s, s.inputNames.first(), tile, scale)
            models[file] = m
            errors.remove(file)
            m
        } catch (t: Throwable) {
            errors[file] = t.message ?: t.toString()
            null
        }
    }

    fun release() {
        for (m in models.values) {
            try { m.session.close() } catch (e: Throwable) { }
        }
        models.clear()
    }

    /** input: 3*tile*tile (CHW, 0..1). Trả về 3*(tile*scale)^2. */
    fun runTile(m: Model, input: FloatArray): FloatArray {
        val shape = longArrayOf(1, 3, m.tile.toLong(), m.tile.toLong())
        return OnnxTensor.createTensor(environment(), FloatBuffer.wrap(input), shape).use { t ->
            m.session.run(mapOf(m.inputName to t)).use { res ->
                val fb = (res[0] as OnnxTensor).floatBuffer
                val arr = FloatArray(fb.remaining())
                fb.get(arr)
                arr
            }
        }
    }

    private fun tilePositions(n: Int, t: Int, step: Int): IntArray {
        if (n <= t) return intArrayOf(0)
        val list = ArrayList<Int>()
        var p = 0
        while (true) {
            if (p + t >= n) { list.add(n - t); break }
            list.add(p)
            p += step
        }
        return list.toIntArray()
    }

    fun tileCount(n: Int, t: Int, step: Int): Int = tilePositions(n, t, step).size

    /**
     * Chạy model theo từng tile chồng lấn rồi trộn mượt. planes: R,G,B (0..255). Trả về planes mới
     * kích thước (w*scale)×(h*scale).
     */
    fun runTiled(
        m: Model, planes: Array<FloatArray>, w: Int, h: Int, overlap: Int,
        onTile: (Int, Int) -> Unit, check: () -> Unit
    ): Array<FloatArray> {
        val t = m.tile
        val s = m.scale
        val to = t * s
        val xs = tilePositions(w, t, t - overlap)
        val ys = tilePositions(h, t, t - overlap)
        val ow = w * s
        val oh = h * s
        val sum = Array(3) { FloatArray(ow * oh) }
        val wt = FloatArray(ow * oh)
        val input = FloatArray(3 * t * t)
        val ovS = max(1, overlap * s)
        val ramp = FloatArray(to) { i -> min(1f, min((i + 1f) / ovS, (to - i).toFloat() / ovS)) }
        var done = 0
        val total = xs.size * ys.size
        for (y0 in ys) for (x0 in xs) {
            check()
            for (ty in 0 until t) {
                val sy = (y0 + ty).coerceIn(0, h - 1) * w
                for (tx in 0 until t) {
                    val si = sy + (x0 + tx).coerceIn(0, w - 1)
                    val ti = ty * t + tx
                    input[ti] = planes[0][si] / 255f
                    input[t * t + ti] = planes[1][si] / 255f
                    input[2 * t * t + ti] = planes[2][si] / 255f
                }
            }
            val out = runTile(m, input)
            val oy = y0 * s
            val ox = x0 * s
            for (ty in 0 until to) {
                val dy = oy + ty
                if (dy >= oh) break
                for (tx in 0 until to) {
                    val dx = ox + tx
                    if (dx >= ow) break
                    val wg = ramp[ty] * ramp[tx]
                    val di = dy * ow + dx
                    val ti = ty * to + tx
                    sum[0][di] += out[ti] * wg
                    sum[1][di] += out[to * to + ti] * wg
                    sum[2][di] += out[2 * to * to + ti] * wg
                    wt[di] += wg
                }
            }
            done++
            onTile(done, total)
        }
        for (c in 0..2) for (i in 0 until ow * oh) {
            val v = if (wt[i] > 0f) sum[c][i] / wt[i] else 0f
            sum[c][i] = (v * 255f).coerceIn(0f, 255f)
        }
        return sum
    }

    /** Kiểm tra từng model bằng ảnh test thật (assets/test.jpg): chạy suy luận thật rồi báo kết quả. */
    fun selfTest(context: Context): String {
        val sb = StringBuilder()
        val bmp = try {
            context.assets.open("test.jpg").use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
        if (bmp == null) return "Không đọc được ảnh test."
        val specs = listOf(
            Triple(RETINEX_FILE, 256 to 1, "Retinexformer (LOL-v2-real)"),
            Triple(SWINIR_FILE, 64 to 4, "SwinIR-M x4 (BSRGAN GAN)")
        )
        for ((file, ts, label) in specs) {
            val (tile, scale) = ts
            val m = load(context, file, tile, scale)
            if (m == null) {
                sb.appendLine("✗ $label: ${errors[file]}")
                continue
            }
            try {
                val tb = Bitmap.createScaledBitmap(bmp, tile, tile, true)
                val px = IntArray(tile * tile)
                tb.getPixels(px, 0, tile, 0, 0, tile, tile)
                val dark = if (file == RETINEX_FILE) 0.25f else 1f
                val input = FloatArray(3 * tile * tile)
                var inMean = 0.0
                for (i in px.indices) {
                    val r = ((px[i] shr 16) and 0xFF) / 255f * dark
                    val g = ((px[i] shr 8) and 0xFF) / 255f * dark
                    val b = (px[i] and 0xFF) / 255f * dark
                    input[i] = r; input[tile * tile + i] = g; input[2 * tile * tile + i] = b
                    inMean += (r + g + b) / 3.0
                }
                inMean /= px.size
                val t0 = SystemClock.elapsedRealtime()
                val out = runTile(m, input)
                val ms = SystemClock.elapsedRealtime() - t0
                val expect = 3 * tile * scale * tile * scale
                var finite = true
                var outMean = 0.0
                for (v in out) { if (v.isNaN() || v.isInfinite()) finite = false; outMean += v }
                outMean /= out.size
                if (out.size != expect || !finite) {
                    sb.appendLine("✗ $label: đầu ra không hợp lệ (kích thước ${out.size}/$expect, hữu hạn=$finite)")
                } else {
                    sb.appendLine("✓ $label: suy luận thật OK, ${ms} ms/tile, độ sáng TB ${"%.3f".format(inMean)} → ${"%.3f".format(outMean)}")
                }
            } catch (t: Throwable) {
                sb.appendLine("✗ $label: lỗi khi chạy — ${t.message}")
            }
        }
        sb.appendLine("✗ DeepLPF: chưa tích hợp — deep_lpf_best.pt chỉ chứa trọng số (U-Net + tham số bộ lọc), thiếu mã mô hình nên không thể chuyển ONNX đúng; cần file .py định nghĩa mô hình.")
        return sb.toString().trim()
    }
}
