package com.godly.moonzoom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.PointF
import android.graphics.Rect
import android.media.FaceDetector
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * MoonAIEnhancer. Pipeline: gộp nhiều khung -> [Retinexformer nếu ảnh tối] -> [SwinIR x4 khi ảnh/crop nhỏ]
 * -> xử lý cổ điển (giảm nhiễu giữ cạnh, mặt, khử nhòe RL, tông, làm nét) -> lưu.
 * Model nơ-ron chỉ được tính là "đã chạy" khi suy luận ONNX thật sự hoàn tất; mọi bước đều ghi vào báo cáo.
 */
object MoonAIEnhancer {

    private const val MAX_WORK_LONG = 2600
    private const val ALIGN_LONG = 1100
    private const val MIN_OUT_LONG = 1600
    private const val RL_ITERS = 3
    private const val DARK_MEAN = 85f
    private const val SWIN_MAX_IN = 448
    private const val SWIN_BUDGET_MS = 120_000L

    class Result(val bitmap: Bitmap, val note: String)

    private class Region(val w: Int, val h: Int, val px: IntArray)

    suspend fun process(
        context: Context,
        frames: List<File>,
        extra: Float,
        aspect: Float,
        onProgress: (Float, String) -> Unit
    ): Result = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) throw IOException("Không có khung ảnh")
        val meta = ImageMeta.read(frames[0])
        val rot = meta.rotation
        val rect = CropMath.rawRect(meta.w, meta.h, rot, aspect, extra)
        val n = frames.size
        val check = { ensureActive() }
        val notes = ArrayList<String>()

        // ---------- 1. Độ nét + căn chỉnh trên ảnh luma nhỏ ----------
        val aS = CropMath.sampleFor(max(meta.w, meta.h), ALIGN_LONG)
        val lumas = ArrayList<ByteArray>()
        val sharp = ArrayList<Double>()
        var aw = 0
        var ah = 0
        for ((i, f) in frames.withIndex()) {
            ensureActive()
            onProgress(0.12f * i / n, "Đánh giá độ nét khung ${i + 1}/$n")
            val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = aS })
                ?: throw IOException("Không đọc được khung")
            if (aw == 0) { aw = bmp.width; ah = bmp.height }
            if (bmp.width != aw || bmp.height != ah) {
                bmp.recycle(); lumas.add(ByteArray(0)); sharp.add(-1.0); continue
            }
            val px = IntArray(aw * ah)
            bmp.getPixels(px, 0, aw, 0, 0, aw, ah)
            bmp.recycle()
            val l = ByteArray(aw * ah)
            for (k in px.indices) {
                val p = px[k]
                l[k] = ((((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8).toByte()
            }
            lumas.add(l)
            sharp.add(laplacianVar(l, aw, ah))
        }
        var refIdx = 0
        for (i in sharp.indices) if (sharp[i] > sharp[refIdx]) refIdx = i
        val bestSharp = max(sharp[refIdx], 1e-6)
        val keep = ArrayList<Int>()
        for (i in sharp.indices) if (sharp[i] >= bestSharp * 0.55 && lumas[i].isNotEmpty()) keep.add(i)
        if (keep.size < 2) {
            keep.clear()
            val order = sharp.indices.filter { lumas[it].isNotEmpty() }.sortedByDescending { sharp[it] }
            keep.addAll(order.take(min(2, order.size)))
        }
        onProgress(0.12f, "Căn chỉnh các khung dưới pixel")
        val aligner = Aligner(lumas[refIdx], aw, ah)
        val shifts = HashMap<Int, FloatArray>()
        for ((j, i) in keep.withIndex()) {
            ensureActive()
            shifts[i] = if (i == refIdx) floatArrayOf(0f, 0f) else aligner.estimate(lumas[i])
            onProgress(0.12f + 0.08f * (j + 1) / keep.size, "Căn chỉnh khung ${j + 1}/${keep.size}")
        }
        lumas.clear()

        // ---------- 2. Gộp khung vào lưới ----------
        val sR = CropMath.sampleFor(max(rect.width(), rect.height()), MAX_WORK_LONG)
        val first = decodeRegion(frames[keep[0]].path, rect, sR)
        val cwS = first.w
        val chS = first.h
        val l0 = max(cwS, chS)
        // Nếu SwinIR sẽ chạy thì giữ nguyên pixel gốc (u=1) để SR x4 thật làm việc đó, không phóng song tuyến trước.
        val swinPlanned = l0 <= SWIN_MAX_IN && OnnxEngine.has(context, OnnxEngine.SWINIR_FILE)
        val u = if (l0 >= 1100 || swinPlanned) 1 else (2200 / l0).coerceIn(1, 4)
        val swap = rot == 90 || rot == 270
        val gw = (if (swap) chS else cwS) * u
        val gh = (if (swap) cwS else chS) * u
        val gtotal = gw * gh
        val accR = FloatArray(gtotal)
        val accG = FloatArray(gtotal)
        val accB = FloatArray(gtotal)
        var sumW = 0f
        val scaleToRegion = aS.toFloat() / sR
        for ((j, i) in keep.withIndex()) {
            ensureActive()
            onProgress(0.20f + 0.24f * j / keep.size, "Gộp khung ${j + 1}/${keep.size}")
            val reg = if (j == 0) first else decodeRegion(frames[i].path, rect, sR)
            if (reg.w != cwS || reg.h != chS) continue
            val sh = shifts[i] ?: floatArrayOf(0f, 0f)
            val wf = (sharp[i] / bestSharp).toFloat().coerceIn(0.3f, 1f)
            accumulate(reg, rot, u, sh[0] * scaleToRegion, sh[1] * scaleToRegion, wf, gw, gh, accR, accG, accB)
            sumW += wf
        }
        if (sumW <= 0f) throw IOException("Không gộp được khung nào")
        val invW = 1f / sumW
        for (k in 0 until gtotal) { accR[k] *= invW; accG[k] *= invW; accB[k] *= invW }
        notes.add("Gộp ${keep.size}/$n khung" + (if (u > 1) " (×$u)" else ""))

        var pr = accR
        var pg = accG
        var pb = accB
        var w = gw
        var h = gh

        var sumY0 = 0.0
        var c0 = 0
        run {
            var k = 0
            while (k < gtotal) { sumY0 += 0.299f * pr[k] + 0.587f * pg[k] + 0.114f * pb[k]; c0++; k += 7 }
        }
        val meanY0 = (sumY0 / max(1, c0)).toFloat()

        // ---------- 3. Retinexformer (chỉ khi ảnh tối) ----------
        var ranRetinex = false
        if (meanY0 < DARK_MEAN) {
            onProgress(0.45f, "Retinexformer: nạp model")
            val m = OnnxEngine.load(context, OnnxEngine.RETINEX_FILE, 256, 1)
            if (m == null) {
                notes.add("Retinexformer: KHÔNG chạy — ${OnnxEngine.errors[OnnxEngine.RETINEX_FILE]}")
            } else try {
                val t0 = SystemClock.elapsedRealtime()
                val out = OnnxEngine.runTiled(
                    m, arrayOf(pr, pg, pb), w, h, 32,
                    onTile = { d, t -> onProgress(0.45f + 0.13f * d / t, "Retinexformer: làm sáng vùng tối $d/$t") },
                    check = check
                )
                pr = out[0]; pg = out[1]; pb = out[2]
                ranRetinex = true
                notes.add("Retinexformer: ĐÃ chạy suy luận thật (${(SystemClock.elapsedRealtime() - t0) / 1000}s)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                notes.add("Retinexformer: lỗi khi chạy — ${e.message}")
            }
        } else {
            notes.add("Retinexformer: bỏ qua (ảnh đủ sáng, độ sáng TB ${meanY0.toInt()})")
        }

        // ---------- 4. SwinIR x4 (khi ảnh/crop nhỏ) ----------
        var ranSwin = false
        if (max(w, h) <= SWIN_MAX_IN) {
            onProgress(0.58f, "SwinIR: nạp model")
            val m = OnnxEngine.load(context, OnnxEngine.SWINIR_FILE, 64, 4)
            if (m == null) {
                notes.add("SwinIR: KHÔNG chạy — ${OnnxEngine.errors[OnnxEngine.SWINIR_FILE]}")
            } else try {
                val t0 = SystemClock.elapsedRealtime()
                OnnxEngine.runTile(m, FloatArray(3 * 64 * 64))
                val tileMs = max(1L, SystemClock.elapsedRealtime() - t0)
                val maxTiles = max(4, (SWIN_BUDGET_MS / tileMs).toInt())
                var iw = w
                var ih = h
                var ir = pr
                var ig = pg
                var ib = pb
                var shrunk = false
                var guard = 0
                while (OnnxEngine.tileCount(iw, 64, 56) * OnnxEngine.tileCount(ih, 64, 56) > maxTiles && guard < 4) {
                    val f = 0.8f
                    val nw = max(64, (iw * f).toInt())
                    val nh = max(64, (ih * f).toInt())
                    val rs = resizePlanes(ir, ig, ib, iw, ih, nw, nh)
                    ir = rs[0]; ig = rs[1]; ib = rs[2]; iw = nw; ih = nh
                    shrunk = true
                    guard++
                }
                val out = OnnxEngine.runTiled(
                    m, arrayOf(ir, ig, ib), iw, ih, 8,
                    onTile = { d, t -> onProgress(0.58f + 0.22f * d / t, "SwinIR x4: siêu phân giải $d/$t") },
                    check = check
                )
                pr = out[0]; pg = out[1]; pb = out[2]
                w = iw * 4
                h = ih * 4
                ranSwin = true
                notes.add(
                    "SwinIR x4: ĐÃ chạy suy luận thật (${(SystemClock.elapsedRealtime() - t0) / 1000}s, ${tileMs}ms/tile)" +
                        (if (shrunk) " — đã thu nhỏ đầu vào để vừa ngân sách thời gian" else "")
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                notes.add("SwinIR: lỗi khi chạy — ${e.message}")
            }
        } else {
            notes.add("SwinIR: bỏ qua (ảnh ${max(w, h)}px đã đủ lớn; SR x4 chỉ dùng khi crop/zoom ≤ ${SWIN_MAX_IN}px)")
        }
        notes.add("DeepLPF: chưa tích hợp — file .pt chỉ có trọng số, thiếu mã mô hình để chuyển ONNX")

        // ---------- 5. Xử lý cổ điển ----------
        val hw = w
        val hh = h
        val total = hw * hh
        val y = pr
        val cb = pg
        val cr = pb
        for (k in 0 until total) {
            val r = pr[k]
            val g = pg[k]
            val b = pb[k]
            y[k] = 0.299f * r + 0.587f * g + 0.114f * b
            cb[k] = -0.168736f * r - 0.331264f * g + 0.5f * b
            cr[k] = 0.5f * r - 0.418688f * g - 0.081312f * b
        }

        ensureActive()
        onProgress(0.81f, "Phân tích nhiễu, độ nhòe, phơi sáng")
        val sigmaN = estimateNoise(y, hw, hh)
        val blurSigma = estimateBlurSigma(y, hw, hh)
        val hist = IntArray(256)
        var sumY = 0.0
        var cnt = 0
        run {
            var k = 0
            while (k < total) {
                hist[y[k].toInt().coerceIn(0, 255)]++
                sumY += y[k]; cnt++
                k += 2
            }
        }
        val meanY = (sumY / max(1, cnt)).toFloat()
        val p005 = percentile(hist, cnt, 0.005f)
        val p995 = percentile(hist, cnt, 0.995f)

        ensureActive()
        onProgress(0.83f, "Nhận diện khuôn mặt")
        val faceMask = detectFaces(y, cb, cr, hw, hh)

        onProgress(0.85f, "Giảm nhiễu giữ cạnh")
        val yD = FloatArray(total)
        bilateralLuma(y, yD, hw, hh, if (ranSwin) sigmaN * 0.6f else sigmaN, faceMask)
        System.arraycopy(yD, 0, y, 0, total)
        val tmp = FloatArray(total)
        chromaSmooth(cb, yD, tmp, hw, hh)
        chromaSmooth(cr, yD, tmp, hw, hh)

        if (!ranSwin) {
            val sigmaPsf = (blurSigma * 0.85f).coerceIn(0.6f, 1.8f)
            val obs = y.copyOf()
            val conv = yD
            val ratio = FloatArray(total)
            for (iter in 0 until RL_ITERS) {
                ensureActive()
                onProgress(0.88f + 0.05f * iter / RL_ITERS, "Khử nhòe Richardson–Lucy lần ${iter + 1}/$RL_ITERS")
                gaussBlur(y, conv, tmp, hw, hh, sigmaPsf)
                for (k in 0 until total) ratio[k] = (obs[k] / (conv[k] + 1e-3f)).coerceIn(0.25f, 4f)
                gaussBlur(ratio, conv, tmp, hw, hh, sigmaPsf)
                for (k in 0 until total) {
                    val v = y[k] * conv[k]
                    val lim = 30f * (1f - 0.6f * maskAt(faceMask, k))
                    y[k] = v.coerceIn(obs[k] - lim, obs[k] + lim)
                }
            }
            for (k in 0 until total) {
                val a = 0.85f - 0.35f * maskAt(faceMask, k)
                y[k] = obs[k] + (y[k] - obs[k]) * a
            }
            notes.add("Khử nhòe Richardson–Lucy (PSF σ=${"%.2f".format(sigmaPsf)}px)")
        }

        ensureActive()
        onProgress(0.94f, "Cân tông và màu theo phơi sáng")
        val black = (p005 - 2f).coerceIn(0f, 20f)
        val white = (p995 + 4f).coerceIn(200f, 255f)
        val gain = (255f / (white - black)).coerceIn(1f, 1.18f)
        val gamma = if (ranRetinex) 1f else when {
            meanY < 90f -> (0.78f + meanY / 90f * 0.22f).coerceIn(0.78f, 1f)
            meanY > 170f -> 1.06f
            else -> 1f
        }
        val lut = FloatArray(1024) { idx ->
            val v = (idx / 1023f * 255f)
            var t = ((v - black) * gain / 255f).coerceIn(0f, 1f)
            t = Math.pow(t.toDouble(), gamma.toDouble()).toFloat()
            val s = t * t * (3f - 2f * t)
            (t * 0.9f + s * 0.1f) * 255f
        }
        for (k in 0 until total) y[k] = lut[(y[k].coerceIn(0f, 255f) / 255f * 1023f).toInt()]
        for (k in 0 until total) {
            val chroma = sqrt(cb[k] * cb[k] + cr[k] * cr[k])
            var s = 1f + 0.16f * (1f - (chroma / 60f).coerceIn(0f, 1f))
            s = 1f + (s - 1f) * (1f - 0.6f * maskAt(faceMask, k))
            cb[k] *= s; cr[k] *= s
        }

        if (!ranSwin) {
            ensureActive()
            onProgress(0.96f, "Làm nét có kiểm soát")
            gaussBlur(y, yD, tmp, hw, hh, 0.9f)
            val thr = sigmaN * 0.7f
            for (k in 0 until total) {
                var d = y[k] - yD[k]
                if (abs(d) < thr) d *= 0.25f
                val amount = 0.55f * (1f - 0.65f * maskAt(faceMask, k))
                y[k] = (y[k] + amount * d).coerceIn(0f, 255f)
            }
        }

        onProgress(0.98f, "Hoàn thiện ảnh")
        val argb = IntArray(total)
        for (k in 0 until total) {
            val yy = y[k]
            val r = (yy + 1.402f * cr[k]).coerceIn(0f, 255f)
            val g = (yy - 0.344136f * cb[k] - 0.714136f * cr[k]).coerceIn(0f, 255f)
            val b = (yy + 1.772f * cb[k]).coerceIn(0f, 255f)
            argb[k] = (0xFF shl 24) or ((r + .5f).toInt() shl 16) or ((g + .5f).toInt() shl 8) or (b + .5f).toInt()
        }
        var outPx = argb
        var ow = hw
        var oh = hh
        if (max(hw, hh) < MIN_OUT_LONG) {
            val sc = MIN_OUT_LONG.toFloat() / max(hw, hh)
            ow = (hw * sc).toInt().coerceAtLeast(1)
            oh = (hh * sc).toInt().coerceAtLeast(1)
            outPx = bicubicResize(argb, hw, hh, ow, oh)
        }
        val bmp = Bitmap.createBitmap(outPx, ow, oh, Bitmap.Config.ARGB_8888)
        onProgress(1f, "Xong")
        notes.add("nhiễu≈${"%.1f".format(sigmaN)}, nhòe≈${"%.2f".format(blurSigma)}px, mặt=${if (faceMask != null) "có" else "không"}, ảnh ra ${ow}×${oh}")
        Result(bmp, notes.joinToString("\n"))
    }

    private fun resizePlanes(r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int, nw: Int, nh: Int): Array<FloatArray> {
        val px = IntArray(w * h)
        for (i in px.indices) {
            px[i] = (0xFF shl 24) or (r[i].toInt().coerceIn(0, 255) shl 16) or
                (g[i].toInt().coerceIn(0, 255) shl 8) or b[i].toInt().coerceIn(0, 255)
        }
        val o = bicubicResize(px, w, h, nw, nh)
        val or2 = FloatArray(nw * nh)
        val og = FloatArray(nw * nh)
        val ob = FloatArray(nw * nh)
        for (i in o.indices) {
            or2[i] = ((o[i] shr 16) and 0xFF).toFloat()
            og[i] = ((o[i] shr 8) and 0xFF).toFloat()
            ob[i] = (o[i] and 0xFF).toFloat()
        }
        return arrayOf(or2, og, ob)
    }

    // ===================== các bước con =====================

    private fun maskAt(mask: ByteArray?, k: Int): Float = if (mask == null) 0f else (mask[k].toInt() and 0xFF) / 255f

    private fun laplacianVar(l: ByteArray, w: Int, h: Int): Double {
        var s = 0.0
        var cnt = 0
        for (yy in 1 until h - 1 step 2) {
            for (xx in 1 until w - 1 step 2) {
                val i = yy * w + xx
                val v = 4 * (l[i].toInt() and 0xFF) - (l[i - 1].toInt() and 0xFF) - (l[i + 1].toInt() and 0xFF) -
                    (l[i - w].toInt() and 0xFF) - (l[i + w].toInt() and 0xFF)
                s += v.toDouble() * v; cnt++
            }
        }
        return if (cnt == 0) 0.0 else s / cnt
    }

    @Suppress("DEPRECATION")
    private fun openDecoder(path: String): BitmapRegionDecoder =
        if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(path)
        else BitmapRegionDecoder.newInstance(path, false)

    private fun decodeRegion(path: String, rect: Rect, sample: Int): Region {
        val dec = openDecoder(path)
        try {
            val bmp = dec.decodeRegion(rect, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: throw IOException("Không đọc được vùng ảnh")
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val r = Region(bmp.width, bmp.height, px)
            bmp.recycle()
            return r
        } finally {
            dec.recycle()
        }
    }

    /** Cộng khung vào lưới đứng (đã xoay) kích thước hw×hh, có dịch (dxR,dyR) theo toạ độ vùng gốc. */
    private fun accumulate(
        reg: Region, rot: Int, u: Int, dxR: Float, dyR: Float, wf: Float,
        hw: Int, hh: Int, accR: FloatArray, accG: FloatArray, accB: FloatArray
    ) {
        val rw = reg.w
        val rh = reg.h
        val px = reg.px
        // sx = cx + ax*uu + bx*vv ; sy = cy + ay*uu + by*vv
        var cx = 0f; var ax = 1f; var bx = 0f
        var cy = 0f; var ay = 0f; var by = 1f
        when (rot) {
            90 -> { cx = 0f; ax = 0f; bx = 1f; cy = (rh - 1).toFloat(); ay = -1f; by = 0f }
            180 -> { cx = (rw - 1).toFloat(); ax = -1f; bx = 0f; cy = (rh - 1).toFloat(); ay = 0f; by = -1f }
            270 -> { cx = (rw - 1).toFloat(); ax = 0f; bx = -1f; cy = 0f; ay = 1f; by = 0f }
        }
        val maxX = (rw - 1).toFloat()
        val maxY = (rh - 1).toFloat()
        val invU = 1f / u
        for (yy in 0 until hh) {
            val vv = (yy + 0.5f) * invU - 0.5f
            for (xx in 0 until hw) {
                val uu = (xx + 0.5f) * invU - 0.5f
                val sx = (cx + ax * uu + bx * vv + dxR).coerceIn(0f, maxX)
                val sy = (cy + ay * uu + by * vv + dyR).coerceIn(0f, maxY)
                val x0 = sx.toInt()
                val y0 = sy.toInt()
                val x1 = min(x0 + 1, rw - 1)
                val y1 = min(y0 + 1, rh - 1)
                val fx = sx - x0
                val fy = sy - y0
                val p00 = px[y0 * rw + x0]
                val p10 = px[y0 * rw + x1]
                val p01 = px[y1 * rw + x0]
                val p11 = px[y1 * rw + x1]
                val w00 = (1 - fx) * (1 - fy)
                val w10 = fx * (1 - fy)
                val w01 = (1 - fx) * fy
                val w11 = fx * fy
                val idx = yy * hw + xx
                accR[idx] += wf * (((p00 shr 16) and 0xFF) * w00 + ((p10 shr 16) and 0xFF) * w10 + ((p01 shr 16) and 0xFF) * w01 + ((p11 shr 16) and 0xFF) * w11)
                accG[idx] += wf * (((p00 shr 8) and 0xFF) * w00 + ((p10 shr 8) and 0xFF) * w10 + ((p01 shr 8) and 0xFF) * w01 + ((p11 shr 8) and 0xFF) * w11)
                accB[idx] += wf * ((p00 and 0xFF) * w00 + (p10 and 0xFF) * w10 + (p01 and 0xFF) * w01 + (p11 and 0xFF) * w11)
            }
        }
    }

    private fun percentile(hist: IntArray, cnt: Int, p: Float): Float {
        val target = (cnt * p).toLong()
        var acc = 0L
        for (b in 0 until 256) {
            acc += hist[b]
            if (acc >= target) return b.toFloat()
        }
        return 255f
    }

    private fun estimateNoise(y: FloatArray, w: Int, h: Int): Float {
        val hist = IntArray(256)
        var cnt = 0
        var yy = 2
        while (yy < h - 2) {
            var xx = 2
            while (xx < w - 2) {
                val i = yy * w + xx
                val m = (y[i - 1] + y[i + 1] + y[i - w] + y[i + w]) * 0.25f
                hist[min(255, (abs(y[i] - m) * 4f).toInt())]++
                cnt++
                xx += 3
            }
            yy += 3
        }
        if (cnt == 0) return 2f
        var acc = 0
        var med = 0
        for (b in 0 until 256) {
            acc += hist[b]
            if (acc >= cnt / 2) {
                med = b; break
            }
        }
        // độ lệch tuyệt đối trung vị -> sigma Gaussian (phần dư của trung bình 4 lân cận có std = 1.118σ)
        return ((med / 4f) / 0.6745f / 1.118f).coerceIn(0.8f, 25f)
    }

    /** Ước lượng σ của mô hình nhòe Gaussian qua độ rộng cạnh: σ ≈ ΔI / (gradient_đỉnh · √(2π)). */
    private fun estimateBlurSigma(y: FloatArray, w: Int, h: Int): Float {
        val list = ArrayList<Float>()
        var yy = 6
        while (yy < h - 6) {
            var xx = 6
            while (xx < w - 6) {
                val i = yy * w + xx
                val gx = (y[i + 1] - y[i - 1]) * 0.5f
                val gy = (y[i + w] - y[i - w]) * 0.5f
                val g = sqrt(gx * gx + gy * gy)
                if (g > 6f) {
                    var mn = 1e9f
                    var mx = -1e9f
                    for (dy in -3..3) for (dx in -3..3) {
                        val v = y[i + dy * w + dx]
                        if (v < mn) mn = v
                        if (v > mx) mx = v
                    }
                    val range = mx - mn
                    if (range > 40f) list.add(range / (g * 2.5066f))
                }
                xx += 5
            }
            yy += 5
        }
        if (list.size < 40) return 1.0f
        list.sort()
        return list[list.size * 3 / 10].coerceIn(0.6f, 2.2f)
    }

    private fun bilateralLuma(src: FloatArray, dst: FloatArray, w: Int, h: Int, sigmaN: Float, mask: ByteArray?) {
        val ws = FloatArray(25)
        for (dy in -2..2) for (dx in -2..2) ws[(dy + 2) * 5 + dx + 2] = exp(-(dx * dx + dy * dy) / (2f * 1.6f * 1.6f))
        fun makeLut(sr: Float) = FloatArray(512) { i -> val d = i * 0.5f; exp(-(d * d) / (2f * sr * sr)) }
        val lutN = makeLut(max(3f, 2.2f * sigmaN))
        val lutF = makeLut(max(3f, 2.8f * sigmaN))
        System.arraycopy(src, 0, dst, 0, src.size)
        for (yy in 2 until h - 2) {
            if (yy % 128 == 0) Thread.yield()
            for (xx in 2 until w - 2) {
                val i = yy * w + xx
                val c = src[i]
                val lut = if (mask != null && (mask[i].toInt() and 0xFF) > 127) lutF else lutN
                var sum = 0f
                var sw = 0f
                var k = 0
                for (dy in -2..2) {
                    val row = i + dy * w
                    for (dx in -2..2) {
                        val v = src[row + dx]
                        val wgt = ws[k++] * lut[min(511, (abs(v - c) * 2f).toInt())]
                        sum += wgt * v; sw += wgt
                    }
                }
                dst[i] = sum / sw
            }
        }
    }

    /** Làm mịn sắc độ (box 5x5 tách trục), giảm dần ở vùng có cạnh sáng để không lem màu. */
    private fun chromaSmooth(c: FloatArray, lumaRef: FloatArray, tmp: FloatArray, w: Int, h: Int) {
        val r = 2
        for (yy in 0 until h) {
            val row = yy * w
            for (xx in 0 until w) {
                var s = 0f
                for (d in -r..r) s += c[row + (xx + d).coerceIn(0, w - 1)]
                tmp[row + xx] = s / (2 * r + 1)
            }
        }
        for (yy in 0 until h) {
            for (xx in 0 until w) {
                var s = 0f
                for (d in -r..r) s += tmp[(yy + d).coerceIn(0, h - 1) * w + xx]
                val smooth = s / (2 * r + 1)
                val i = yy * w + xx
                val edge = if (xx in 1 until w - 1 && yy in 1 until h - 1)
                    abs(lumaRef[i + 1] - lumaRef[i - 1]) + abs(lumaRef[i + w] - lumaRef[i - w]) else 0f
                val keep = (edge / 14f).coerceIn(0f, 1f)
                c[i] = smooth + (c[i] - smooth) * keep
            }
        }
    }

    private fun gaussBlur(src: FloatArray, dst: FloatArray, tmp: FloatArray, w: Int, h: Int, sigma: Float) {
        val r = max(1, Math.ceil(3.0 * sigma).toInt())
        val k = FloatArray(2 * r + 1)
        var s = 0f
        for (i in -r..r) {
            k[i + r] = exp(-(i * i) / (2f * sigma * sigma)); s += k[i + r]
        }
        for (i in k.indices) k[i] /= s
        for (yy in 0 until h) {
            val row = yy * w
            for (xx in 0 until w) {
                var a = 0f
                for (d in -r..r) a += k[d + r] * src[row + (xx + d).coerceIn(0, w - 1)]
                tmp[row + xx] = a
            }
        }
        for (yy in 0 until h) {
            for (xx in 0 until w) {
                var a = 0f
                for (d in -r..r) a += k[d + r] * tmp[(yy + d).coerceIn(0, h - 1) * w + xx]
                dst[yy * w + xx] = a
            }
        }
    }

    /** Nhận diện mặt bằng android.media.FaceDetector trên bản thu nhỏ; trả mặt nạ mềm 0..255 hoặc null. */
    private fun detectFaces(y: FloatArray, cb: FloatArray, cr: FloatArray, w: Int, h: Int): ByteArray? {
        try {
            val stride = max(1, max(w, h) / 480)
            val sw = (w / stride) and 1.inv()
            val sh = h / stride
            if (sw < 32 || sh < 32) return null
            val px = IntArray(sw * sh)
            for (j in 0 until sh) for (i in 0 until sw) {
                val k = (j * stride) * w + i * stride
                val r = (y[k] + 1.402f * cr[k]).coerceIn(0f, 255f).toInt()
                val g = (y[k] - 0.344136f * cb[k] - 0.714136f * cr[k]).coerceIn(0f, 255f).toInt()
                val b = (y[k] + 1.772f * cb[k]).coerceIn(0f, 255f).toInt()
                px[j * sw + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            val argb = Bitmap.createBitmap(px, sw, sh, Bitmap.Config.ARGB_8888)
            val b565 = argb.copy(Bitmap.Config.RGB_565, false)
            argb.recycle()
            val faces = arrayOfNulls<FaceDetector.Face>(4)
            val found = FaceDetector(sw, sh, 4).findFaces(b565, faces)
            b565.recycle()
            if (found <= 0) return null
            val mask = ByteArray(w * h)
            for (f in 0 until found) {
                val face = faces[f] ?: continue
                val mid = PointF()
                face.getMidPoint(mid)
                val cx = mid.x * stride
                val cyy = mid.y * stride
                val rad = face.eyesDistance() * stride * 2.1f
                val x0 = max(0, (cx - rad * 1.3f).toInt())
                val x1 = min(w - 1, (cx + rad * 1.3f).toInt())
                val y0 = max(0, (cyy - rad * 1.3f).toInt())
                val y1 = min(h - 1, (cyy + rad * 1.3f).toInt())
                for (yy in y0..y1) for (xx in x0..x1) {
                    val d = sqrt((xx - cx) * (xx - cx) + (yy - cyy) * (yy - cyy)) / rad
                    val t = ((1.25f - d) / 0.5f).coerceIn(0f, 1f)
                    val m = (t * t * (3f - 2f * t) * 255f).toInt()
                    val idx = yy * w + xx
                    if (m > (mask[idx].toInt() and 0xFF)) mask[idx] = m.toByte()
                }
            }
            return mask
        } catch (t: Throwable) {
            return null
        }
    }

    /** Phóng bicubic Catmull-Rom. */
    private fun bicubicResize(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        fun wgt(t: Float): Float {
            val a = abs(t)
            return when {
                a < 1f -> 1.5f * a * a * a - 2.5f * a * a + 1f
                a < 2f -> -0.5f * a * a * a + 2.5f * a * a - 4f * a + 2f
                else -> 0f
            }
        }
        val out = IntArray(dw * dh)
        val xi = Array(dw) { IntArray(4) }
        val xw = Array(dw) { FloatArray(4) }
        for (x in 0 until dw) {
            val sx = (x + 0.5f) * sw / dw - 0.5f
            val x0 = Math.floor(sx.toDouble()).toInt()
            for (t in 0..3) {
                xi[x][t] = (x0 - 1 + t).coerceIn(0, sw - 1)
                xw[x][t] = wgt(sx - (x0 - 1 + t))
            }
        }
        val yi = IntArray(4)
        val yw = FloatArray(4)
        for (y in 0 until dh) {
            val sy = (y + 0.5f) * sh / dh - 0.5f
            val y0 = Math.floor(sy.toDouble()).toInt()
            for (t in 0..3) {
                yi[t] = (y0 - 1 + t).coerceIn(0, sh - 1)
                yw[t] = wgt(sy - (y0 - 1 + t))
            }
            for (x in 0 until dw) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (ty in 0..3) {
                    val row = yi[ty] * sw
                    for (tx in 0..3) {
                        val wt = yw[ty] * xw[x][tx]
                        val p = src[row + xi[x][tx]]
                        r += wt * ((p shr 16) and 0xFF)
                        g += wt * ((p shr 8) and 0xFF)
                        b += wt * (p and 0xFF)
                    }
                }
                out[y * dw + x] = (0xFF shl 24) or
                    (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
            }
        }
        return out
    }
}
