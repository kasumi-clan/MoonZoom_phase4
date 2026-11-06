package com.godly.moonzoom

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Thông tin cơ bản của ảnh gốc do camera chụp. [rotation]: độ cần xoay thuận chiều để ảnh đứng. */
class ImageMeta(
    val w: Int,
    val h: Int,
    val rotation: Int,
    val make: String?,
    val model: String?,
    val date: Date,
    val focal35: Int
) {
    val displayW: Int get() = if (rotation == 90 || rotation == 270) h else w
    val displayH: Int get() = if (rotation == 90 || rotation == 270) w else h

    companion object {
        fun read(f: File): ImageMeta {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var rot = 0
            var make: String? = null
            var model: String? = null
            var date: Date? = null
            var focal = 0
            try {
                val e = ExifInterface(f.path)
                rot = e.rotationDegrees
                make = e.getAttribute(ExifInterface.TAG_MAKE)
                model = e.getAttribute(ExifInterface.TAG_MODEL)
                focal = e.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
                val ds = e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: e.getAttribute(ExifInterface.TAG_DATETIME)
                if (ds != null) date = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(ds)
            } catch (e: Exception) {
            }
            if (make.isNullOrBlank() && model.isNullOrBlank()) {
                make = Build.MANUFACTURER
                model = Build.MODEL
            }
            return ImageMeta(o.outWidth, o.outHeight, rot, make, model, date ?: Date(), focal)
        }
    }
}

object CropMath {
    /**
     * Vùng crop (toạ độ ảnh gốc, chưa xoay) để ảnh ra có tỉ lệ [aspect] (rộng/cao khi nhìn đứng) và
     * đã crop số thêm [extra] lần, lấy chính giữa.
     */
    fun rawRect(rawW: Int, rawH: Int, rot: Int, aspect: Float, extra: Float): Rect {
        val swap = rot == 90 || rot == 270
        val dw = if (swap) rawH else rawW
        val dh = if (swap) rawW else rawH
        var cw: Float
        var ch: Float
        if (dw.toFloat() / dh > aspect) {
            ch = dh.toFloat(); cw = ch * aspect
        } else {
            cw = dw.toFloat(); ch = cw / aspect
        }
        cw = max(8f, cw / extra)
        ch = max(8f, ch / extra)
        val rw = if (swap) ch else cw
        val rh = if (swap) cw else ch
        val w = min(rawW, rw.roundToInt()).coerceAtLeast(8)
        val h = min(rawH, rh.roundToInt()).coerceAtLeast(8)
        val left = (rawW - w) / 2
        val top = (rawH - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    fun sampleFor(longSide: Int, maxLong: Int): Int {
        var s = 1
        while (longSide / s > maxLong) s *= 2
        return s
    }
}

/** Chuẩn hoá tên máy giống Godly-Watermark (có bảng tên Samsung). */
object DeviceName {
    private val SAM = mapOf(
        "SM-A576" to "A57 5G", "SM-A566" to "A56 5G", "SM-A556" to "A55 5G", "SM-A546" to "A54 5G",
        "SM-A536" to "A53 5G", "SM-A526" to "A52 5G", "SM-A516" to "A51 5G", "SM-A515" to "A51",
        "SM-A366" to "A36 5G", "SM-A356" to "A35 5G", "SM-A346" to "A34 5G", "SM-A266" to "A26 5G",
        "SM-A256" to "A25 5G", "SM-A166" to "A16 5G", "SM-A156" to "A15 5G", "SM-S938" to "S25 Ultra",
        "SM-S936" to "S25+", "SM-S931" to "S25", "SM-S928" to "S24 Ultra", "SM-S926" to "S24+",
        "SM-S921" to "S24", "SM-S918" to "S23 Ultra", "SM-S916" to "S23+", "SM-S911" to "S23",
        "SM-S908" to "S22 Ultra", "SM-S906" to "S22+", "SM-S901" to "S22", "SM-G998" to "S21 Ultra 5G",
        "SM-G996" to "S21+ 5G", "SM-G991" to "S21 5G", "SM-F966" to "Z Fold7", "SM-F766" to "Z Flip7",
        "SM-F956" to "Z Fold6", "SM-F741" to "Z Flip6", "SM-F946" to "Z Fold5", "SM-F731" to "Z Flip5"
    )

    private fun samName(c: String?): String {
        val m = Regex("SM-[A-Z]\\d{3}", RegexOption.IGNORE_CASE).find(c ?: "") ?: return ""
        val n = SAM[m.value.uppercase(Locale.US)] ?: return ""
        return "Samsung Galaxy $n"
    }

    fun norm(make: String?, model: String?): String {
        val sn = samName(model)
        if (sn.isNotEmpty()) return sn
        var mk = (make ?: "").replace(Regex("\\s+"), " ").trim()
        val md = (model ?: "").replace(Regex("\\s+"), " ").trim()
        if (mk.length > 4 && mk == mk.uppercase(Locale.US)) mk = mk.substring(0, 1) + mk.substring(1).lowercase(Locale.US)
        if (mk.isNotEmpty() && md.lowercase(Locale.US).startsWith(mk.lowercase(Locale.US))) mk = ""
        return "$mk $md".trim()
    }
}
