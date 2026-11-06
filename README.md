# Camera (Moon Zoom) — By GODLY — v0.2

> CHƯA được biên dịch hay chạy thử trên máy thật (môi trường tạo code không có Android SDK/Gradle/mạng).

Xem chi tiết hướng dẫn trong tin nhắn bàn giao. Tóm tắt:
- Giao diện: đế vòm xanh Liquid Glass (không viền trắng), thước zoom 1x–100x ở viền vòm, nút album tròn có % lấp lánh,
  nút chụp có lấp lánh, thanh chỉnh sáng/tối mới, cài đặt watermark (tên máy, logo, dòng thông tin), flash 4 chế độ.
- AI: pipeline Retinexformer (nếu ảnh tối) -> SwinIR x4 (khi crop nhỏ) -> xử lý cổ điển. DeepLPF chưa tích hợp được
  (file .pt thiếu mã mô hình). Model chỉ được tính là "đã chạy" khi suy luận ONNX thật hoàn tất.
- Chuyển đổi model: tools/convert_models.py (workflow "Convert models") — kiểm tra từng model bằng ảnh test.
