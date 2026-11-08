# Quy Trình Tự Động Kiểm Tra & Thẩm Định Lỗi (Bug Investigation & Verification Protocol)

Mỗi khi người dùng báo lỗi hoặc phản hồi về sự cố trong ứng dụng, trợ lý AI **BẮT BUỘC** phải tuân thủ nghiêm ngặt quy trình 4 bước dưới đây trước khi đưa ra câu trả lời hoặc phát hành bản sửa lỗi:

---

## 🔍 BƯỚC 1: Phân Tích Bằng Chứng Trực Quan & Nhật Ký (Không Đoán Mò)
1. **Phân tích tư liệu do người dùng cung cấp:**
   - Nếu người dùng gửi ảnh chụp màn hình: Đọc chính xác từng dòng chữ, mã lỗi hệ thống (`ERR_...`, HTTP code, dialog pop-up).
   - Nếu người dùng gửi video: Trích xuất các khung hình thực tế (dùng script PowerShell/C# trích xuất frame bằng `IShellItemImageFactory`) để quan sát chính xác hành vi: nút bấm có bị nhấp nháy không, video bị đơ ở giây thứ mấy, thanh tiến trình ra sao.
2. **Xác định nguyên nhân gốc rễ (Root Cause):**
   - Phải tìm ra cơ chế kỹ thuật thực sự gây ra lỗi (ví dụ: vòng lặp MutationObserver, chính sách Autoplay Audio Policy của Chromium, xung đột WebView CustomView, giới hạn API rate limit).
   - Tuyệt đối không chữa cháy triệu chứng bề mặt khiến phát sinh lỗi mới.

---

## ⚖️ BƯỚC 2: Thẩm Định Tính Khả Thi Kỹ Thuật (Feasibility & Reality Check)
1. **Đánh giá ranh giới nền tảng:**
   - Phân biệt rõ ràng giữa **Lỗi logic code sửa được** vs **Rào cản bảo mật độc quyền của nhà phát hành** (như máy chủ ByteDance chặn bot/scraper, Google cấm OAuth trên WebView, chữ ký C++ SecGuard).
2. **Minh bạch và trung thực tuyệt đối:**
   - Nếu một phương án kỹ thuật vấp phải rào cản nền tảng không thể vượt qua, phải báo ngay sự thật cho người dùng, giải thích lý do vì sao và đề xuất giải pháp thay thế đã được kiểm chứng.
   - Tuyệt đối không hứa hẹn những điều bất khả thi dẫn đến việc người dùng phải thử đi thử lại nhiều phiên bản thất bại.

---

## 🛠️ BƯỚC 3: Sửa Lỗi Có Phương Pháp & Sạch Sẽ (Methodical Fix)
1. Không lạm dụng các đoạn mã can thiệp thô bạo (như vòng lặp JS click tự động, ép kích thước phần tử `100vw/100vh` phá vỡ layout, ép un-mute vi phạm chính sách trình duyệt).
2. Tách biệt kiến trúc: Dùng Native Jetpack (ExoPlayer, ViewPager2, MediaSession) cho hiệu năng tối đa; nếu dùng WebView phải có đầy đủ cờ bảo vệ và cơ chế dọn dẹp bộ nhớ.

---

## ✅ BƯỚC 4: Tự Thẩm Định Chéo Trước Khi Bàn Giao (Self-Verification Protocol)
Trước khi gửi bản cập nhật hoặc link tải cho người dùng, AI phải tự rà soát bảng kiểm tra (**Checklist**) sau:
- [ ] **Kiểm tra phát Video:** Video có thực sự nạp được luồng stream không, hay link CDN đã hết hạn?
- [ ] **Kiểm tra tương tác UI:** Nút bấm/chạm vào màn hình có gây xung đột làm nhấp nháy (flicker) hay không?
- [ ] **Kiểm tra số lượng:** Tính năng cuộn có bị khựng lại sau 5-10 video không?
- [ ] **Kiểm tra Đảo Origin:** `MediaSession` có đang phát đúng `PlaybackState.STATE_PLAYING` với ID `com.luna.music` không?
- [ ] **Kiểm tra chạy nền:** Khi màn hình tắt (`ACTION_SCREEN_OFF`), âm thanh có được duy trì liên tục qua `WAKE_LOCK` không?

> **Chỉ khi vượt qua đầy đủ các tiêu chí thẩm định trên thì mới được thông báo hoàn thành và gửi link tải cho người dùng.**
