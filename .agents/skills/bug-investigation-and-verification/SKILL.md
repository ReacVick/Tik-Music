---
name: bug-investigation-and-verification
description: >-
  Use this skill whenever the user reports an issue, bug, crash, or unexpected behavior in the Android app.
  Provides a systematic 4-phase protocol to extract visual evidence, analyze root causes, check technical feasibility,
  and self-verify fixes before delivery.
---

# Quy Trình Tự Động Điều Tra & Thẩm Định Lỗi (Bug Investigation & Verification Protocol)

Mỗi khi người dùng báo lỗi, hãy kích hoạt ngay quy trình này theo các bước chuẩn:

## 1. Thu thập bằng chứng khách quan (Evidence Extraction)
- Đối với video: Dùng công cụ C#/PowerShell để trích xuất khung hình trực quan từ tệp video người dùng gửi:
  ```powershell
  # Trích xuất thumbnail hoặc frame từ video để quan sát chính xác màn hình người dùng
  [ShellThumb]::SaveThumb($videoPath, $outPath)
  ```
- Đối với hình ảnh: Đọc rõ thông báo lỗi, URL scheme, mã HTTP (400, 403, 404, 500) hoặc lỗi Chromium.

## 2. Kiểm tra tính khả thi & Ranh giới nền tảng (Reality Check)
- Đặt câu hỏi: Lỗi này do mã nguồn của chúng ta hay do chính sách máy chủ bên thứ ba (ByteDance/Google)?
- Nếu do máy chủ bên ngoài chặn (ví dụ: cấm bot, cấm WebView OAuth, mã hóa C++):
  -> Phải giải thích thẳng thắn cho người dùng ngay, không cố gắng tạo ra các bản build "vá víu" vô ích.

## 3. Thực hiện sửa đổi (Clean Patching)
- Không dùng vòng lặp can thiệp cưỡng ép vào DOM (MutationObserver click liên tục).
- Sử dụng chuẩn Android Jetpack, Media3 ExoPlayer, MediaSession, ForegroundService.

## 4. Bảng kiểm tra tự thẩm định (Pre-Delivery Verification Checklist)
Trước khi đưa ra kết luận "Đã sửa xong", bắt buộc phải tự đối chiếu checklist:
1. Luồng video có link phát hợp lệ và còn hạn sử dụng không?
2. Có hiện tượng đơ/chớp nháy giao diện khi chạm vào màn hình không?
3. Tính năng tự cuộn và số lượng video có bị giới hạn (ngắt sau 10 video) không?
4. Đảo Origin có nhận diện đúng Application ID `com.luna.music` không?
5. Tính năng tắt màn hình có giữ `WAKE_LOCK` không?
