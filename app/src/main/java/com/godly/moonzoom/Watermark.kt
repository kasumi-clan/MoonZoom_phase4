package com.godly.moonzoom

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Watermark Liquid Glass chuyển từ Godly-Watermark.html: cùng công thức bố cục (lay), cùng nội dung
 * (tên máy bên trái; "PHOTO • WxH • mm" và ngày giờ bên phải) và cùng shader kính khúc xạ
 * (bản gốc chạy WebGL, ở đây chạy trên CPU cho đúng từng pixel).
 */
object Watermark {

    class Layout(
        val x: Int, val y: Int, val w: Int, val h: Int, val r: Int,
        val pad: Float, val f1: Float, val f2: Float, val f3: Float,
        val lh: Float, val lw: Float, val gp: Float, val sw: Float,
        val deviceText: String, val rows: List<String>, val logo: Bitmap?, val logoPos: String
    )

    private fun paint(size: Float, weight: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.create(Typeface.DEFAULT, weight, false)
    }

    private fun mw(t: String, f: Float, weight: Int): Float = paint(f, weight).measureText(t)

    fun infoRows(
        w: Int, h: Int, focal35: Int, extra: Float, date: Date,
        showInfo: Boolean = true, showDate: Boolean = true
    ): List<String> {
        val out = ArrayList<String>()
        if (showInfo) {
            var s = "PHOTO \u2022 $w\u00d7$h"
            val mm = (focal35 * extra).roundToInt()
            if (mm > 0) s += " \u2022 ${mm}mm"
            out.add(s)
        }
        if (showDate) out.add(SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.US).format(date))
        return out
    }

    fun layout(W: Int, H: Int, device: String, rows: List<String>, logo: Bitmap?, logoPos: String = "pre"): Layout? {
        if (device.isEmpty() && logo == null && rows.isEmpty()) return null
        val b = min(W * .5625f, H.toFloat())
        val ra = W.toFloat() / H
        val tl = H.toFloat() / W
        val hk = if (tl > 1.1f) 1f + min(.7f, (tl - 1.1f) * 1.1f + .1f) else 1f
        val h0 = b * .135f
        val m = b * .045f * (1f + (hk - 1f) * .8f)
        val pad = h0 * .42f
        val mid = h0 * .45f
        var h = h0 * hk
        var f1 = h0 * .34f
        var f2 = h0 * .2f
        var f3 = h0 * .185f
        var lh = min(f1 * 1.8f, h * .66f)
        var gp = f1 * .5f
        var sw = max(1.5f, f1 * .06f)
        var lw = if (logo != null) lh * logo.width / logo.height else 0f
        var wl = if (device.isNotEmpty()) mw(device, f1, 600) else 0f
        var wr = 0f
        rows.forEachIndexed { i, t -> wr = max(wr, mw(t, if (i > 0) f3 else f2, 500)) }
        fun lef(): Float = (if (logo != null) lw else 0f) + (if (logo != null && wl > 0f) gp * 2 + sw else 0f) + wl
        val mm = if (lef() > 0f && wr > 0f) mid else 0f
        val need = 2 * pad + lef() + mm + wr
        val full = W - 2 * m
        val w = min(full, max(need, W * (if (ra > 1.5f) .68f else if (ra > 1.2f) .72f else .8f)))
        if (need > w) {
            val s = max(.22f, (w - 2 * pad - mm) / (lef() + wr) * .95f)
            f1 *= s; f2 *= s; f3 *= s; lh *= s; lw *= s; gp *= s; sw *= s; wl *= s; wr *= s
            h *= max(.72f, .55f + .45f * s)
        }
        var lt = device
        val ml = w - 2 * pad - mm - wr - (if (logo != null) lw + gp * 2 + sw else 0f)
        while (lt.length > 1 && mw(lt, f1, 600) > ml) lt = lt.dropLast(1)
        if (lt != device) lt = lt.trimEnd() + "\u2026"
        return Layout(
            ((W - w) / 2f).roundToInt(), (H - m - h - b * .04f).roundToInt(), w.roundToInt(), h.roundToInt(),
            (h * .46f).roundToInt(), pad, f1, f2, f3, lh, lw, gp, sw, lt, rows, logo, logoPos
        )
    }

    private fun drawMid(c: Canvas, t: String, x: Float, yMid: Float, p: Paint) {
        val fm = p.fontMetrics
        c.drawText(t, x, yMid - (fm.ascent + fm.descent) / 2f, p)
    }

    /** Lớp chữ/logo (bản gốc: hàm ovl). */
    private fun overlay(L: Layout): Bitmap {
        val o = Bitmap.createBitmap(L.w, L.h, Bitmap.Config.ARGB_8888)
        val c = Canvas(o)
        val h = L.h.toFloat()
        val shadow = Color.argb(115, 0, 0, 0)
        var tx = L.pad
        val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(128, 255, 255, 255) }
        fun sep() {
            c.drawRect(tx + L.gp, h / 2f - L.f1 * .55f, tx + L.gp + L.sw, h / 2f - L.f1 * .55f + L.f1 * 1.1f, sepPaint)
            tx += L.gp * 2 + L.sw
        }
        val lg = L.logo
        val pre = L.logoPos != "post"
        fun drawLogo() {
            if (lg != null) {
                c.drawBitmap(lg, null, RectF(tx, h / 2f - L.lh / 2f, tx + L.lw, h / 2f + L.lh / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
                tx += L.lw
            }
        }
        if (lg != null && pre) {
            drawLogo()
            if (L.deviceText.isNotEmpty()) sep()
        }
        if (L.deviceText.isNotEmpty()) {
            val p = paint(L.f1, 600).apply {
                color = Color.WHITE
                textAlign = Paint.Align.LEFT
                setShadowLayer(h * .06f, 0f, h * .02f, shadow)
            }
            drawMid(c, L.deviceText, tx, h / 2f + L.f1 * .04f, p)
            tx += p.measureText(L.deviceText)
        }
        if (lg != null && !pre) {
            if (L.deviceText.isNotEmpty()) sep()
            drawLogo()
        }
        var total = 0f
        L.rows.forEachIndexed { i, _ -> total += (if (i > 0) L.f3 else L.f2) * 1.3f }
        var cy = h / 2f - total / 2f
        L.rows.forEachIndexed { i, t ->
            val f = if (i > 0) L.f3 else L.f2
            val p = paint(f, 500).apply {
                color = if (i > 0) Color.argb(230, 255, 255, 255) else Color.WHITE
                textAlign = Paint.Align.RIGHT
                setShadowLayer(h * .06f, 0f, h * .02f, shadow)
            }
            drawMid(c, t, L.w - L.pad, cy + f * .65f, p)
            cy += f * 1.3f
        }
        return o
    }

    private fun sdf(qx: Float, qy: Float, hx: Float, hy: Float, r: Float): Float {
        val dx = abs(qx) - hx + r
        val dy = abs(qy) - hy + r
        return min(max(dx, dy), 0f) + sqrt(max(dx, 0f) * max(dx, 0f) + max(dy, 0f) * max(dy, 0f)) - r
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun tex(src: IntArray, w: Int, h: Int, sx: Float, sy: Float, o: FloatArray) {
        val x = sx.coerceIn(0.001f * w, 0.999f * w) - 0.5f
        val y = sy.coerceIn(0.001f * h, 0.999f * h) - 0.5f
        val fxl = floor(x)
        val fyl = floor(y)
        val x0 = fxl.toInt().coerceIn(0, w - 1)
        val y0 = fyl.toInt().coerceIn(0, h - 1)
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val fx = (x - fxl).coerceIn(0f, 1f)
        val fy = (y - fyl).coerceIn(0f, 1f)
        val p00 = src[y0 * w + x0]
        val p10 = src[y0 * w + x1]
        val p01 = src[y1 * w + x0]
        val p11 = src[y1 * w + x1]
        for (ch in 0..2) {
            val sh = 16 - 8 * ch
            val a = (p00 shr sh) and 0xFF
            val b = (p10 shr sh) and 0xFF
            val c = (p01 shr sh) and 0xFF
            val d = (p11 shr sh) and 0xFF
            val top = a + (b - a) * fx
            val bot = c + (d - c) * fx
            o[ch] = (top + (bot - top) * fy) / 255f
        }
    }

    /** Vẽ watermark kính vào [base] (phải là bitmap mutable, ARGB_8888). */
    fun apply(base: Bitmap, device: String, rows: List<String>, logo: Bitmap? = null, logoPos: String = "pre") {
        val L = layout(base.width, base.height, device, rows, logo, logoPos) ?: return
        val bw = L.w
        val bh = L.h
        if (bw < 4 || bh < 4 || L.x < 0 || L.y < 0 || L.x + bw > base.width || L.y + bh > base.height) return
        val src = IntArray(bw * bh)
        base.getPixels(src, 0, bw, L.x, L.y, bw, bh)
        val ovB = overlay(L)
        val ov = IntArray(bw * bh)
        ovB.getPixels(ov, 0, bw, 0, 0, bw, bh)
        ovB.recycle()
        val out = IntArray(bw * bh)

        val hx = bw / 2f
        val hy = bh / 2f
        val r = L.r.toFloat()
        val h = bh.toFloat()
        val k = 6.2832f / h
        val lx = -.5f
        val ly = -.85f
        val ln = sqrt(lx * lx + ly * ly)
        val lux = lx / ln
        val luy = ly / ln
        val lw2 = max(1.2f, h * .008f)
        val c1 = FloatArray(3)
        val t1 = FloatArray(3)
        val t2 = FloatArray(3)
        val t3 = FloatArray(3)
        val t4 = FloatArray(3)
        val e = 1.3f

        for (py in 0 until bh) {
            for (px in 0 until bw) {
                val i = py * bw + px
                val pxf = px + .5f
                val pyf = py + .5f
                val qx = pxf - hx
                val qy = pyf - hy
                val d = sdf(qx, qy, hx, hy, r)
                if (d > 1f) {
                    out[i] = src[i]; continue
                }
                var nx = sdf(qx + 1f, qy, hx, hy, r) - sdf(qx - 1f, qy, hx, hy, r)
                var ny = sdf(qx, qy + 1f, hx, hy, r) - sdf(qx, qy - 1f, hx, hy, r)
                nx += 1e-4f; ny += 1e-4f
                val nl = sqrt(nx * nx + ny * ny)
                nx /= nl; ny /= nl
                val t = (-d / (h * .5f)).coerceIn(0f, 1f)
                val rim = (1f - t).pow(1.7f)
                val mixk = .68f + (.5f - .68f) * rim
                var sx = hx + qx * mixk - nx * rim * h * 1.25f
                var sy = hy + qy * mixk - ny * .45f * rim * h * 1.25f
                val wob = smooth(0f, .6f, t) * h * .06f
                sx += sin(qy * k * 1.1f + qx * k * .28f) * wob
                sy += sin(qx * k * .42f - qy * k * .8f) * wob
                tex(src, bw, bh, sx, sy, c1)
                tex(src, bw, bh, sx + e, sy, t1)
                tex(src, bw, bh, sx - e, sy, t2)
                tex(src, bw, bh, sx, sy + e, t3)
                tex(src, bw, bh, sx, sy - e, t4)
                val sp = max(nx * lux + ny * luy, 0f).pow(2.5f) + .55f * max(-(nx * lux + ny * luy), 0f).pow(2.5f)
                val add = sp * (1f - t).pow(3f) * .22f + rim * .02f +
                    max(ny, 0f).pow(6f) * (1f - t).pow(2f) * .05f +
                    exp(-((d + lw2 * 1.2f) / lw2).pow(2f)) * .2f + .01f
                val ovc = ov[i]
                val oa = ((ovc ushr 24) and 0xFF) / 255f
                val a = smooth(.8f, -.8f, d)
                var res = 0
                for (ch in 0..2) {
                    val nb = (t1[ch] + t2[ch] + t3[ch] + t4[ch]) * .25f
                    var g = c1[ch] + (c1[ch] - nb) * 1.2f + add
                    val vr = (((ovc shr (16 - 8 * ch)) and 0xFF) / 255f) * oa
                    g = vr + g * (1f - oa)
                    val o0 = ((src[i] shr (16 - 8 * ch)) and 0xFF) / 255f
                    val v = (o0 + (g - o0) * a).coerceIn(0f, 1f)
                    res = res or ((v * 255f + .5f).toInt() shl (16 - 8 * ch))
                }
                out[i] = (0xFF shl 24) or res
            }
        }
        base.setPixels(out, 0, bw, L.x, L.y, bw, bh)
    }
}
