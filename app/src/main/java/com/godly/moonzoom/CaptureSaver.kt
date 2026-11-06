package com.godly.moonzoom

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Tuỳ chỉnh watermark lấy từ màn hình Cài đặt. */
class WmOptions(
    val deviceAuto: Boolean = true,
    val deviceName: String = "",
    val logo: String = "none",       // none | leica | custom
    val logoPos: String = "pre",     // pre | post
    val showInfo: Boolean = true,
    val showDate: Boolean = true
)

object CaptureSaver {
    /** Ảnh crop số nhỏ hơn mức này sẽ được phóng bằng nội suy (KHÔNG thêm chi tiết thật). */
    const val MIN_LONG_SIDE = 1600
    private const val FOLDER = "Pictures/MoonZoom"

    /**
     * Ảnh thường (AI tắt): crop theo tỉ lệ khung + crop số, xoay đứng, (tuỳ chọn) watermark rồi lưu.
     * Nếu không cần chỉnh gì thì lưu nguyên file gốc của camera (giữ EXIF).
     */
    suspend fun saveNormal(
        context: Context, src: File, extra: Float, aspect: Float, watermark: Boolean,
        wm: WmOptions, tagPrefix: String = "",
        onProgress: (Float, String) -> Unit
    ): Uri = withContext(Dispatchers.IO) {
        val meta = ImageMeta.read(src)
        val nativeAspect = meta.displayW.toFloat() / meta.displayH
        val digital = extra > 1.01f
        val needs = digital || abs(nativeAspect - aspect) > 0.01f || watermark
        val tag = listOf(tagPrefix, if (digital) "digital" else "").filter { it.isNotEmpty() }.joinToString("_")
        if (!needs) return@withContext insert(context, src, fileName(tag))
        onProgress(0.15f, "Cắt khung hình")
        val rect = CropMath.rawRect(meta.w, meta.h, meta.rotation, aspect, extra)
        val sample = CropMath.sampleFor(max(rect.width(), rect.height()), 4096)
        val bmp = decodeRegion(src.path, rect, sample)
        var up = bmp
        if (meta.rotation != 0) {
            val m = Matrix().apply { postRotate(meta.rotation.toFloat()) }
            up = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (up !== bmp) bmp.recycle()
        }
        finishBitmap(context, up, meta, extra, watermark, tag, true, wm, onProgress)
    }

    /** Hoàn tất một bitmap đã đứng: (tuỳ chọn) phóng, watermark, ghi JPEG + EXIF, lưu vào thư viện. */
    suspend fun finishBitmap(
        context: Context, bitmap: Bitmap, meta: ImageMeta, extra: Float, watermark: Boolean, tag: String,
        upscale: Boolean, wm: WmOptions, onProgress: (Float, String) -> Unit
    ): Uri = withContext(Dispatchers.IO) {
        var b = bitmap
        if (upscale && max(b.width, b.height) < MIN_LONG_SIDE) {
            val s = MIN_LONG_SIDE.toFloat() / max(b.width, b.height)
            val scaled = Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
            if (scaled !== b) b.recycle()
            b = scaled
        }
        if (watermark) {
            onProgress(0.4f, "Vẽ watermark Liquid Glass")
            val m = if (b.isMutable && b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, true).also { b.recycle() }
            b = m
            val device = if (wm.deviceAuto || wm.deviceName.isBlank()) DeviceName.norm(meta.make, meta.model) else wm.deviceName.trim()
            val rows = Watermark.infoRows(b.width, b.height, meta.focal35, extra, meta.date, wm.showInfo, wm.showDate)
            Watermark.apply(b, device, rows, loadLogo(context, wm.logo), wm.logoPos)
        }
        onProgress(0.85f, "Lưu ảnh")
        val out = File.createTempFile("out_", ".jpg", context.cacheDir)
        try {
            FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            b.recycle()
            try {
                val e = ExifInterface(out.path)
                meta.make?.let { e.setAttribute(ExifInterface.TAG_MAKE, it) }
                meta.model?.let { e.setAttribute(ExifInterface.TAG_MODEL, it) }
                val ds = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(meta.date)
                e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, ds)
                e.setAttribute(ExifInterface.TAG_DATETIME, ds)
                e.saveAttributes()
            } catch (e: Exception) {
            }
            insert(context, out, fileName(tag))
        } finally {
            out.delete()
        }
    }

    fun loadLogo(context: Context, mode: String): Bitmap? = try {
        when (mode) {
            "leica" -> context.assets.open("watermark/leica.png").use { BitmapFactory.decodeStream(it) }
            "custom" -> File(context.filesDir, "wm_logo.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    private fun fileName(tag: String): String =
        "MoonZoom_" + (if (tag.isNotEmpty()) tag + "_" else "") +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"

    private fun insert(context: Context, file: File, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Không tạo được mục MediaStore")
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun openDecoder(path: String): BitmapRegionDecoder =
        if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(path)
        else BitmapRegionDecoder.newInstance(path, false)

    private fun decodeRegion(path: String, rect: android.graphics.Rect, sample: Int): Bitmap {
        val dec = openDecoder(path)
        try {
            return dec.decodeRegion(rect, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: throw IllegalStateException("Không đọc được vùng ảnh")
        } finally {
            dec.recycle()
        }
    }
}

object GalleryHelper {
    suspend fun latest(context: Context): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("Pictures/MoonZoom%"),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                if (c.moveToFirst())
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                else null
            }
        }.getOrNull()
    }

    suspend fun thumb(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.loadThumbnail(uri, Size(256, 256), null) }.getOrNull()
    }
}
