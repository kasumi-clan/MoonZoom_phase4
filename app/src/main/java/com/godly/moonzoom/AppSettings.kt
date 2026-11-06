package com.godly.moonzoom

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.reflect.KProperty

/** Giá trị cài đặt vừa là state của Compose vừa được lưu bền vào SharedPreferences. */
class PrefDelegate<T : Any>(
    private val prefs: SharedPreferences,
    private val key: String,
    default: T
) {
    @Suppress("UNCHECKED_CAST")
    private val state = mutableStateOf<T>(
        when (default) {
            is Boolean -> prefs.getBoolean(key, default) as T
            is Int -> prefs.getInt(key, default) as T
            is String -> (prefs.getString(key, default) ?: default) as T
            else -> default
        }
    )

    operator fun getValue(thisRef: Any?, p: KProperty<*>): T = state.value

    operator fun setValue(thisRef: Any?, p: KProperty<*>, v: T) {
        state.value = v
        val e = prefs.edit()
        when (v) {
            is Boolean -> e.putBoolean(key, v)
            is Int -> e.putInt(key, v)
            is String -> e.putString(key, v)
        }
        e.apply()
    }
}

class AppSettings(prefs: SharedPreferences) {
    var aiOn by PrefDelegate(prefs, "ai", false)
    var wmOn by PrefDelegate(prefs, "wm", false)
    var frame by PrefDelegate(prefs, "frame", 0)
    var saveOriginal by PrefDelegate(prefs, "saveOrig", true)

    // Watermark tuỳ chỉnh (giống bản Liquid Glass Watermark: tên máy, logo, dòng thông tin)
    var wmDeviceAuto by PrefDelegate(prefs, "wmDevAuto", true)
    var wmDeviceName by PrefDelegate(prefs, "wmDevName", "")
    var wmLogo by PrefDelegate(prefs, "wmLogo", "none")      // none | leica | custom
    var wmLogoPos by PrefDelegate(prefs, "wmLogoPos", "pre") // pre | post
    var wmShowInfo by PrefDelegate(prefs, "wmInfo", true)
    var wmShowDate by PrefDelegate(prefs, "wmDate", true)

    var lastAiReport by PrefDelegate(prefs, "aiReport", "Chưa có lần xử lý AI nào.")
}
