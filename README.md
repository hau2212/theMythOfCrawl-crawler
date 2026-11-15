# theMythOfCrawl Crawler (Burp Suite Java Extension)

Burp Suite Montoya API extension (Java `--release 17`, không phụ thuộc thư viện ngoài, không AI) hỗ trợ audit & crawl website đa công nghệ qua **Firefox / Chrome / Chromium / Burp Built-in Browser** đi qua Burp Proxy, gom nhóm API theo từng thao tác UI (`Action Group`), tự động đánh **NOTE**, lọc/tùy chỉnh HTTP Header, và đẩy các API sạch có kiểm soát sang **Logger++ (Extender) -> Elasticsearch** mà không sửa đổi byte gói tin.

## Điểm nổi bật bản nâng cấp

1. **Giao diện tối ưu màn hình 14 inch & dịu mắt khi audit lâu (`AuditTheme` + `CrawlerTabPanel`):**
   - **Thanh điều khiển chính 1 dòng gọn gàng:** chỉ giữ `Host(s)`, `Bắt đầu/Tạm dừng/Dừng`, nút gạt quyền **`🔒 Tự gửi Request`** và các chip thống kê bấm nhanh (`Nhóm`, `API`, `NOTE`, `Header`, `Excel`). Toàn bộ nút phụ (`Import Excel`, `Seed URL`, `Reset`, `Xuất báo cáo`, thanh tiến độ, checkbox chế độ) nằm trong ngăn kéo **`⚙ Công cụ & Excel ▼`** ẩn mặc định.
   - **Loại bỏ mũi tên `< >` / `^ v` nhỏ xíu của Swing SplitPane:** thay bằng dải nút rõ nét **`◧ Ẩn/Hiện danh sách Nhóm`**, **`⬓ Hiện HTTP Req/Resp`**, **`⛶ Phóng to Bảng`**; khung soi HTTP Request/Response mặc định thu gọn để vùng bảng chiếm ~85–90% chiều cao màn hình 14" (double-click dòng hoặc bấm nút để mở soi ngay).
   - **Bảng màu dịu mắt (`AuditTheme`):** Light Mode dùng nền cool-slate dịu (`#EEF2F7` / `#F5F7FA` / `#F7F9FC`) thay cho trắng gắt `#FFFFFF`, Dark Mode tối dịu cùng màu ngữ nghĩa (`ERROR`, `WARN`, `INFO`, `OK`) giúp nhìn lâu không mỏi mắt.

2. **Khóa an toàn "Tự gửi Request" (Mặc định ĐANG KHÓA):**
   - Nút **`🔒 Tự gửi Request: ĐANG KHÓA`** luôn bật khóa khi khởi động.
   - Khi đang khóa: extension **KHÔNG** tự fetch `/robots.txt` / `/sitemap.xml` từ Seed URL, **KHÔNG** tự click hay submit form trong trình duyệt, nhưng Browser Agent vẫn lắng nghe thao tác bấm tay thực tế của bạn (`event.isTrusted`) để gom nhóm API chuẩn xác theo từng nút/màn hình.
   - Chỉ khi bạn bấm mở khóa **`🔓 Tự gửi Request: ĐANG BẬT`**, chế độ tự động duyệt trang/form mới thực thi.

3. **Đẩy sạch qua `Extender -> Logger++ -> Elasticsearch` (`PushLedgerPanel` + `ExtenderPushManager`):**
   - Chuột phải trên **Nhóm**, **API**, **Bảng NOTE**, **Cây Phân cấp** hoặc bấm **`📤 + Hàng đẩy Logger++`** để đưa đúng các API cần thiết vào tab **Đẩy Logger++**.
   - **Giữ nguyên 100% gói tin HTTP:** Không tiêm header giả để đặt tên; Tên Nhóm (`toppage > ...`) được gắn vào **Burp Annotations (Comment)** thông qua `HttpHandler` và lưu đồng bộ tại **Bảng Đối Chiếu (Push Ledger)** (có cột `#`, `Giờ Đẩy`, `Tên Nhóm`, `Method`, `Endpoint`, `HTTP Gốc`, `HTTP Đẩy`, nút **Copy Tên Nhóm** và **Xuất CSV đối chiếu**).
   - **Cấu hình trên Logger++:** Tại mục **Log From**, bỏ tick **All Tools** và **Proxy**, chỉ tick **Extender** -> cấu hình **Elastic Exporter** (Host, Port, HTTPS, Index, Basic Auth Username/Password) rồi bật **Start Elastic Exporter** trong Logger++.

4. **Bộ lọc & Tùy chỉnh HTTP Header (`HeaderFilterPanel` + `HeaderFilterProfile`):**
   - Hỗ trợ **Gắn / Ghi đè (`SET`)**, **Thêm nếu chưa có (`ADD_IF_MISSING`)** và **Xóa Header (`REMOVE`)** theo phạm vi tất cả Target Hosts (`*`), chuỗi con hoặc `/regex/` (ví dụ `X-Account: a`, `Authorization`, `Cookie`).
   - Có nút mẫu nhanh (`+ Mẫu X-Account: a`, `+ Mẫu Authorization`), lưu qua **Burp Preferences**, mặc định áp dụng khi **Browser Crawl qua Proxy** và tắt khi **Đẩy qua Extender** để bảo toàn nguyên vẹn gói tin đẩy lên Elastic.

5. **Nhóm chức năng & Cột/Bảng NOTE Audit (`ActionGroupStore` + `AuditNote`):**
   - Gom chung toàn bộ API phát sinh khi bấm nút/chức năng vào một **Nhóm Chức Năng** giữ nguyên hoa/thường (`toppage > contact > button 'Send'` hoặc `/`), khử trùng theo `Hits` và đánh **NOTE** (`Khác tham số`, `Khác HTTP status`, `HTTP 5xx`, `HTTP 401/403`, `Ngoài Excel`, `Nâng cấp bản Excel`).

6. **Bao phủ đa công nghệ, Tự điền Form (`TEXT`) & Checklist Excel:**
   - Hỗ trợ REST JSON lồng nhau, GraphQL (`operationName`), XML/SOAP, Multipart, URLEncoded, `sendBeacon`, same-origin `iframe`, open Shadow DOM, SPA routing và tự điền Form mặc định `TEXT` có thể tùy biến quy tắc.

## Cấu trúc thư mục

- `dist/theMythOfCrawl-crawler.jar` — File Extension `.jar` đã build sẵn để nạp vào Burp Suite.
- `lib/montoya-api.jar` — PortSwigger Burp Montoya API (`2025.12`) phục vụ biên dịch.
- `src/main/java/com/mythofcrawl/crawler/` — Mã nguồn Java (`CrawlerExtension`, `excel/`, `model/`, `proxy/`, `ui/`).
- `src/main/resources/crawler-agent.js` — Browser Agent chạy qua Burp Proxy.
- `src/test/java/com/mythofcrawl/crawler/CrawlerUnitTests.java` — Bộ unit test thuần Java (11 nhóm kiểm thử).
- `build.ps1` — Script biên dịch (`--release 17`), đóng gói `.jar` và chạy kiểm thử.

## Build từ mã nguồn (Windows PowerShell)

Yêu cầu **JDK 17 trở lên**:

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

File đầu ra nằm tại `dist\theMythOfCrawl-crawler.jar`.

## Quy trình sử dụng nhanh trên Burp Suite

1. Vào **Extensions > Installed > Add** -> chọn **Extension type: `Java`** -> chọn `dist\theMythOfCrawl-crawler.jar`.
2. Mở tab **theMythOfCrawl Crawler**:
   - (Tùy chọn) Mở tab **Bộ lọc Header HTTP** nếu cần gắn header như `X-Account: a` khi duyệt web.
   - (Tùy chọn) Bấm **`⚙ Công cụ & Excel ▼`** để **Import Excel (.xlsx/.csv)** hoặc nhập **Seed URL**.
   - Nhập domain đích vào **Host(s)** (ví dụ `target.example.com` hoặc `*`).
3. Để nguyên **`🔒 Tự gửi Request: ĐANG KHÓA`** nếu bạn muốn tự tay bấm các nút trên trình duyệt để gom nhóm API an toàn; hoặc bấm để chuyển sang **`🔓 ĐANG BẬT`** nếu muốn Agent tự duyệt.
4. Bấm **Bắt đầu Crawl**, thao tác trên trình duyệt trỏ qua Burp Proxy.
5. Audit tại tab **Nhóm & API**, **Bảng NOTE**, rồi chuột phải đưa các nhóm/API sạch sang tab **Đẩy Logger++** -> tick **Chỉ `Extender`** trong Logger++ -> bấm **Đẩy đã chọn sang Logger++** và dùng **Bảng Đối Chiếu (hoặc Export CSV)** để map 1-to-1 tên chức năng khi đẩy nhiều API lên Elastic.
