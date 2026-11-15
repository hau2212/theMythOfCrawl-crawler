# theMythOfCrawl Crawler (Burp Suite Java Extension)

Burp Suite Montoya API extension (Java `--release 17`, không phụ thuộc thư viện ngoài, không AI) hỗ trợ auto-crawl website nhiều công nghệ qua **Firefox / Chrome / Chromium / Burp Built-in Browser** đi qua Burp Proxy, nhóm toàn bộ API theo từng thao tác UI (`Action Group`), tự động đánh **NOTE** khi phát hiện sai khác/lỗi, đối chiếu checklist **Excel (`.xlsx` / `.csv`)** và hỗ trợ cấu hình giá trị điền form linh hoạt.

## Điểm nổi bật bản nâng cấp

1. **Nhóm chức năng & Cột/Bảng NOTE Audit (`ActionGroupStore` + `AuditNote`):**
   - Mỗi thao tác UI (nút bấm, link, tab, `<select>`, submit form, tìm kiếm, `page load`, scroll...) tạo thành một **Nhóm Chức Năng** với đường dẫn giữ nguyên hoa/thường theo cây màn hình (mặc định gốc `toppage > ...`, ví dụ: `toppage > contact > button 'Send'`; có thể chuyển sang `/` trong cài đặt).
   - Toàn bộ API phát sinh khi bấm nút/chức năng đó (ví dụ 5 API liên tiếp) được gom chung vào nhóm; từng API con đều giữ **Tên API đúng bằng Tên Nhóm** để dễ audit ngược luồng màn hình.
   - Tự động khử trùng các lần gọi giống hệt nhau trong cùng nhóm bằng bộ đếm `Hits`, đồng thời đánh **NOTE** và tổng hợp sang tab **Bảng NOTE** khi có:
     - `Khác tham số` (cùng endpoint trong nhóm hoặc khác nhóm trước đó gửi bộ key tham số khác)
     - `Khác HTTP status`, `HTTP 5xx`, `HTTP 4xx`, `401/403 Quyền/Phiên`
     - `Ngoài Excel` (khi tắt Strict Excel Mode) hoặc `Nâng cấp bản Excel` (khi tìm thấy bộ tham số đầy đủ hơn, ví dụ 2 -> 8 params).
   - Chuột phải trên nhóm hoặc API để **Send CẢ NHÓM sang Repeater** hoặc xem **Các nhóm cùng gọi API này**.

2. **Giao diện Audit dịu mắt (`AuditTheme` + `CrawlerTabPanel`):**
   - Tự thích ứng **Burp Dark Mode & Light Mode** với bảng màu ngữ nghĩa dịu mắt (`ERROR` đỏ dịu, `WARN` hổ phách, `INFO` xanh lam, `OK` xanh lá dịu), zebra striping và padding rộng rãi giúp audit lâu không mỏi mắt.
   - Bố cục 6 tab rõ ràng: **Nhóm & API**, **Bảng NOTE**, **Tiến độ Excel**, **Cây Phân cấp**, **Điền Form & Cài Đặt**, **Bị né, JS & Log** cùng HTTP Request/Response Inspector ở nửa dưới.

3. **Bao phủ đa công nghệ & Không bỏ sót luồng nghiệp vụ:**
   - Hỗ trợ REST JSON (lồng nhau + mảng), GraphQL (tách riêng theo `operationName`), XML/SOAP (`xml.<tag>`), Multipart FormData, `application/x-www-form-urlencoded`, `navigator.sendBeacon`, `fetch` & `XMLHttpRequest`.
   - Browser Agent (`crawler-agent.js`) chỉ chạy thực thi chủ động ở `top` window (chặn iframe quảng cáo/ngoài miền cướp phiên crawl), đồng thời hook & duyệt sâu vào **same-origin `iframe`**, **open Shadow DOM**, thay đổi `<select>`, hover menu/`aria-haspopup`, cuộn trang lazy-load, SPA `pushState`/hash routing và tương thích React `_valueTracker`.
   - **Danger Guard tối giản**: mặc định chỉ né các hành động đăng xuất (`logout,log out,sign out,signout,đăng xuất`) để không bỏ sót các nút nghiệp vụ (xóa, thanh toán, lưu, xuất dữ liệu...) trừ khi bạn tự thêm từ khóa.

4. **Tự điền Form, Ngày tháng & Bảng sửa được tùy biến (`FormFillProfile`):**
   - Mặc định điền chuỗi **`TEXT`** vào toàn bộ ô văn bản, textarea, ô tìm kiếm, rich-text / `contenteditable` và ô bảng sửa được (`2026-01-01` cho trường ngày, v.v.).
   - Tab **Điền Form & Cài Đặt** cho phép đổi nhanh toàn bộ trường text bằng 1 bấm, chỉnh từng loại trường riêng, hoặc viết quy tắc khớp theo `từ_khóa = giá_trị` và `/regex/ = giá_trị`.
   - Cấu hình và Profile điền form tự động lưu qua **Burp Preferences** giữa các lần mở lại Burp.

5. **Nạp checklist Excel (`.xlsx` / `.csv`) & Xuất báo cáo:**
   - Đọc trực tiếp file `.xlsx` nhiều sheet và ánh xạ 3 cột `STT`, `Method`, `Path API` qua hộp thoại xem trước.
   - Xuất báo cáo **CSV (UTF-8 BOM)**, **JSON**, **Markdown** bao gồm cả danh sách **Nhóm & API**, **Bảng NOTE Audit** và **Tiến độ Excel**.

## Cấu trúc thư mục

- `dist/theMythOfCrawl-crawler.jar` — File Extension `.jar` đã build sẵn để nạp vào Burp Suite.
- `lib/montoya-api.jar` — PortSwigger Burp Montoya API (`2025.12`) phục vụ biên dịch.
- `src/main/java/com/mythofcrawl/crawler/` — Mã nguồn Java (`CrawlerExtension`, `excel/`, `model/`, `proxy/`, `ui/`).
- `src/main/resources/crawler-agent.js` — Browser Agent chạy qua Burp Proxy.
- `src/test/java/com/mythofcrawl/crawler/CrawlerUnitTests.java` — Bộ unit test thuần Java.
- `build.ps1` — Script biên dịch (`--release 17`), đóng gói `.jar` và chạy kiểm thử.

## Build từ mã nguồn (Windows PowerShell)

Yêu cầu **JDK 17 trở lên**:

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

File đầu ra nằm tại `dist\theMythOfCrawl-crawler.jar`.

## Hướng dẫn sử dụng trong Burp Suite

1. Vào **Extensions > Installed > Add** -> chọn **Extension type: `Java`** -> chọn `dist\theMythOfCrawl-crawler.jar`.
2. Mở tab **theMythOfCrawl Crawler**:
   - (Tùy chọn) Bấm **Import Excel (.xlsx/.csv)** và ánh xạ 3 cột `STT`, `Method`, `Path API`. Có thể bỏ tick **Chỉ giữ Excel** ngay thanh công cụ nếu muốn thu thập & nhóm toàn bộ API kể cả ngoài Excel.
   - Vào tab **Điền Form & Cài Đặt** nếu muốn đổi giá trị mặc định `"TEXT"`, quy tắc điền theo tên trường hoặc đổi gốc cây (`toppage` / `/`).
   - Nhập domain đích vào **Host(s)** (ví dụ `target.example.com` hoặc `*`).
3. Trên trình duyệt đang trỏ qua Burp Proxy (Firefox, Chrome hoặc Burp Built-in Browser), mở trang đích và đăng nhập trước nếu cần.
4. Bấm **Bắt đầu Crawl** trong Burp rồi Reload tab trình duyệt (hoặc điền sẵn **Seed URL**).
5. Theo dõi **Nhóm & API**, **Bảng NOTE**, **Tiến độ Excel**, **Cây Phân cấp**; chuột phải gửi 1 API hoặc **cả nhóm API** sang Repeater, hoặc bấm **Xuất báo cáo ▼**.
