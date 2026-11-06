package com.godly.moonzoom

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun Title(text: String) {
    Text(text, color = MoonBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Blue,
                uncheckedThumbColor = Color.White.copy(alpha = 0.8f),
                uncheckedTrackColor = Color(0xFF14243E), uncheckedBorderColor = Blue.copy(alpha = 0.5f)
            )
        )
    }
}

@Composable
private fun Choice(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        for ((key, label) in options) {
            GlassButton(Modifier.height(36.dp).width(96.dp), selected = selected == key, onClick = { onSelect(key) }) {
                GlassLabel(label, selected == key, 12)
            }
        }
    }
}

/** Màn hình cài đặt: tuỳ chỉnh watermark (tên máy, logo, dòng thông tin), bản gốc AI, kiểm tra model, ghi công. */
@Composable
fun SettingsPanel(
    settings: AppSettings,
    onClose: () -> Unit,
    onPickLogo: () -> Unit,
    onSelfTest: () -> Unit,
    selfTestText: String,
    selfTestRunning: Boolean,
    retinexPresent: Boolean,
    swinPresent: Boolean
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE6030B18))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Cài đặt", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                GlassButton(Modifier.height(36.dp).width(84.dp), onClick = onClose) { GlassLabel("XONG", true, 12) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Title("ẢNH")
                SwitchRow("Giữ ảnh gốc khi bật AI (lưu thêm 1 file)", settings.saveOriginal) { settings.saveOriginal = it }

                Title("WATERMARK LIQUID GLASS")
                SwitchRow("Bật watermark", settings.wmOn) { settings.wmOn = it }
                SwitchRow("Tên máy tự động (lấy từ ảnh)", settings.wmDeviceAuto) { settings.wmDeviceAuto = it }
                OutlinedTextField(
                    value = settings.wmDeviceName,
                    onValueChange = { settings.wmDeviceName = it },
                    enabled = !settings.wmDeviceAuto,
                    singleLine = true,
                    label = { Text("Tên máy hiển thị") },
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        disabledTextColor = Color.White.copy(alpha = 0.4f),
                        focusedBorderColor = Blue, unfocusedBorderColor = Blue.copy(alpha = 0.5f),
                        disabledBorderColor = Blue.copy(alpha = 0.2f), cursorColor = Blue,
                        focusedLabelColor = MoonBlue, unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
                        disabledLabelColor = Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Logo", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    GlassButton(Modifier.height(36.dp).width(96.dp), selected = settings.wmLogo == "none", onClick = { settings.wmLogo = "none" }) {
                        GlassLabel("Không", settings.wmLogo == "none", 12)
                    }
                    GlassButton(Modifier.height(36.dp).width(96.dp), selected = settings.wmLogo == "leica", onClick = { settings.wmLogo = "leica" }) {
                        GlassLabel("Leica", settings.wmLogo == "leica", 12)
                    }
                    GlassButton(Modifier.height(36.dp).width(110.dp), selected = settings.wmLogo == "custom", onClick = onPickLogo) {
                        GlassLabel("Chọn ảnh…", settings.wmLogo == "custom", 12)
                    }
                }
                Text("Vị trí logo", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                Choice(listOf("pre" to "Trước tên", "post" to "Sau tên"), settings.wmLogoPos) { settings.wmLogoPos = it }
                SwitchRow("Hiện dòng thông tin (PHOTO • kích thước • mm)", settings.wmShowInfo) { settings.wmShowInfo = it }
                SwitchRow("Hiện ngày giờ", settings.wmShowDate) { settings.wmShowDate = it }

                Title("AI — 3 MODEL OFFLINE")
                Text(
                    "Retinexformer: ${if (retinexPresent) "có file ONNX" else "chưa có file ONNX"}\n" +
                        "SwinIR x4: ${if (swinPresent) "có file ONNX" else "chưa có file ONNX"}\n" +
                        "DeepLPF: chưa tích hợp (thiếu mã mô hình)",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp
                )
                GlassButton(Modifier.padding(top = 8.dp).height(38.dp).width(190.dp), enabled = !selfTestRunning, onClick = onSelfTest) {
                    GlassLabel(if (selfTestRunning) "Đang chạy thử…" else "Kiểm tra model bằng ảnh test", false, 12)
                }
                if (selfTestText.isNotEmpty()) {
                    Text(selfTestText, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
                Text("Báo cáo lần AI gần nhất", color = MoonBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                Text(settings.lastAiReport, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)

                Title("THÔNG TIN")
                Text("Camera — By GODLY", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Zoom 1x–100x · Liquid Glass · AI chạy offline trên máy",
                    color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(bottom = 24.dp)
                )
            }
        }
    }
}
