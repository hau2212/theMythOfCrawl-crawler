# theMythOfCrawl Crawler (Burp Suite Java Extension)

Burp Suite Montoya API extension (Java `--release 17`, không phụ thuộc thư viện ngoài, không AI) hỗ trợ auto-crawl toàn bộ website qua **Chrome / Chromium / Burp Browser / Firefox** đi qua Burp Proxy, đối chiếu danh sách API chuẩn từ **Excel (`.xlsx` / `.csv`)** và tự động gắn nhãn nguồn gốc theo cây thao tác UI (`Action Path`).

## Tính năng chính

1. **Điều khiển trực tiếp trình duyệt qua Burp Proxy (`/__myth_crawl/*` Loopback Control Plane):**
   - Hoạt động với **Chrome, Chromium (Open Browser của Burp), Edge và Firefox** mà không cần script/webdriver ngoài.
   - Bơm `crawler-agent.js` vào các trang HTML thuộc Target Host (tự nới lỏng CSP header & loại bỏ `304`/nén không phù hợp trên document HTML khi đang Armed/Running; bỏ qua `iframe` tránh nhiễu vòng lặp crawl).
   - Quét nút bấm, link, tab, menu, form (kể cả open Shadow DOM và same-origin iframe), tự điền dữ liệu mẫu an toàn và có **Danger Guard** né các hành động nhạy cảm (`logout`, `delete`, `xóa`, `thanh toán`...).
2. **Đặt tên cây phân cấp giữ nguyên hoa/thường (`PathNamer`):**
   - Ví dụ: `/ > contact > button 'Send'` hoặc `/ > admin > Users > {id} > link 'Edit User'`.
   - Giữ nguyên chữ hoa/thường của nhãn nút/link trên giao diện.
3. **Nạp checklist Excel (`.xlsx` / `.csv`) & xử lý bộ tham số thông minh:**
   - Tự đọc trực tiếp file `.xlsx` (OOXML) nhiều sheet và cho phép ánh xạ cột `STT`, `Method`, `Path API` qua hộp thoại xem trước.
   - Hỗ trợ path động `{id}`, `:id`, `<id>`, `*` và `API Base Prefix`.
   - Theo dõi từng `STT`: giữ bản đầu tiên khi trùng hoặc tập con tham số, **tự nâng cấp** khi tìm thấy lời gọi có tập tham số đầy đủ hơn (ví dụ 2 -> 8 params), và **lưu giữ cả các biến thể kèm ghi chú (`Param Diff Note`)** nếu hai trang gửi hai bộ tham số khác nhau.
   - Cho phép chọn giữa chế độ **Strict Excel Mode** (chỉ bắt API khớp file Excel) hoặc ghi thêm tab **Ngoài Excel**.
4. **Xuất báo cáo & gửi nhanh sang Repeater:**
   - Chuột phải gửi Request (hoặc tất cả biến thể tham số) sang **Burp Repeater**.
   - Xuất báo cáo **CSV (UTF-8 BOM)**, **JSON** hoặc **Markdown** phân nhóm theo cây UI.

## Cấu trúc thư mục

- `dist/theMythOfCrawl-crawler.jar` — File Extension `.jar` đã build sẵn để nạp vào Burp Suite.
- `lib/montoya-api.jar` — PortSwigger Burp Montoya API (`2025.12`) phục vụ biên dịch.
- `src/main/java/com/mythofcrawl/crawler/` — Mã nguồn Java của Extension (`CrawlerExtension`, `excel/`, `model/`, `proxy/`, `ui/`).
- `src/main/resources/crawler-agent.js` — Script Browser Agent chạy qua Burp Proxy.
- `src/test/java/com/mythofcrawl/crawler/CrawlerUnitTests.java` — Bộ unit test thuần Java.
- `build.ps1` — Script biên dịch (`--release 17`), đóng gói JAR và chạy kiểm thử.

## Build từ mã nguồn (Windows PowerShell)

Yêu cầu **JDK 17 trở lên** (trong `build.ps1` biên dịch với `--release 17`):

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

File đầu ra nằm tại `dist\theMythOfCrawl-crawler.jar`.

## Hướng dẫn sử dụng trong Burp Suite

1. Vào **Extensions > Installed > Add** -> chọn **Extension type: `Java`** -> chọn file `dist\theMythOfCrawl-crawler.jar`.
2. Mở tab **theMythOfCrawl Crawler**:
   - Bấm **Import Excel (.xlsx/.csv)** và xác nhận 3 cột `STT`, `Method`, `Path API`. (Nếu chỉ muốn test crawl tự do không dùng Excel, có thể bỏ tick **Strict Excel Mode** trong **Cài đặt...**).
   - Nhập domain đích vào ô **Host(s)** (ví dụ `target.example.com` hoặc `*.example.com`).
3. Trên trình duyệt đang trỏ qua Burp Proxy (Chrome, Burp Built-in Browser hoặc Firefox), mở trang đích (và đăng nhập sẵn nếu cần quyền).
4. Bấm **Bắt đầu Crawl** trong Burp rồi Reload tab trình duyệt (hoặc điền sẵn **Seed URL**).
5. Xem kết quả tại các tab **Tiến độ Excel**, **Cây Phân cấp**, **Ngoài Excel**, **Nút bị né & Gợi ý JS**, chuột phải chọn **Send to Repeater** hoặc bấm **Xuất báo cáo ▼**.
