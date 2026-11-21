# theMythOfCrawl Crawler — `V_1.0_beta` (Burp Suite Java Extension)

Burp Suite Montoya API extension (**Java `--release 17`**, **zero external runtime dependencies**, không AI, không FlatLaf) hỗ trợ audit & crawl website đa công nghệ (React/Vue/Angular/Next.js/Web Components/PWA) qua **Firefox / Chrome / Chromium / Burp Built-in Browser** đi qua Burp Proxy. Phiên bản **`V_1.0_beta`** tái kiến trúc theo **7 Luật Kiến trúc Cứng (7 Hard Engineering Invariants)**, lấy **Tree-First Workspace (`Cây làm việc`)** làm trung tâm, tích hợp **Action Guardrail 2 tầng (`Luật & Chặn` + `Intercept Queue`)**, **Quản lý đa Role & JWT TTL (`Roles & Auth`)**, và tuân thủ triệt để nguyên tắc **Crawl-First, Explicit Batch Push Later** sang **Logger++ (Extender) -> Elasticsearch**.

---

## Điểm nổi bật kiến trúc `V_1.0_beta`

### 1. Tree-First Workspace & Single-Row Top Bar (`<= 1100px`, Zero Hidden State)
- **Single-Row Top Bar (`CrawlerTabPanel`):** Thiết kế 1 dòng duy nhất (`preferredWidth <= 1100px`) tương thích màn hình laptop 14":
  - **Cụm trái:** Công tắc `ModernToggleSwitch` (Agent/Proxy ON/OFF), `SegmentedToggle` (`Dừng | Capture | Auto`), chế độ an toàn 1-click (`Chỉ đọc GET` / `Chặn duyệt POST/PUT/PATCH/DELETE` / `Giữ ở Proxy` / `Nguyên bản NONE`) và `Breadcrumb` nhánh hiện tại.
  - **Cụm phải:** Nút huy hiệu **`Chờ duyệt (N)`** (mở `InterceptQueueDialog`), nút 1-click **`Nhả NONE`**, tìm kiếm toàn cục **`Ctrl+K` (`SpotlightDialog`)**, và menu **`Thêm`** (Import Excel, Xuất báo cáo, Tách Cây `DetachedTreeDialog`, `UiDisplayMode`, chuyển nhanh **Light / Dark Mode**).
- **7 Tab làm việc chuyên biệt (Tree-First Default):**
  1. **`Cây làm việc` (`TreeWorkspacePanel` + `InlineRequestDock`):** Giao diện chính hợp nhất cây phân cấp đa hình (`ThemedTree` với `NodeWrapper`: `ROOT`, `BRANCH`, `ACTION`, `HELD_PENDING`, `API_GROUP`, `VISUAL_CLUSTER`, `GHOST`), chế độ **Focus Mode**, gom cụm trực quan (**Visual Clusters**), phả hệ API trùng lặp (**Duplicate Lineage**), bảng API kèm `MethodChipBar` (`Chỉ Action` / `Tất cả`), và **Inline Raw HTTP Dock (`InlineRequestDock`)** ở đáy để sửa/gửi lại Raw HTTP trực tiếp.
  2. **`Luật & Chặn` (`GuardrailLedgerTab`):** Sổ cái CRUD quản lý chính sách chặn/cho phép theo 5 phạm vi (`GLOBAL`, `METHOD_OR_ACTION`, `BRANCH`, `PATH_PATTERN`, `EXACT_API`) với **Progressive Disclosure**, luật ưu tiên tối thượng **`#1 ALLOW`**, thùng rác **Trash / Undo**, sắp xếp độ ưu tiên (`Up / Down / Top`), **4 cấp độ `Nhả NONE`**, bảng **Decision Trace** (`ActionGateAuditLog`), và **Policy Simulator** không làm tăng bộ đếm hit.
  3. **`Roles & Auth` (`RolesTab`):** Quản lý đa tài khoản/vai trò (`ADMIN`, `STAFF`, `USER`, `GUEST`), cấu hình Header/Cookie theo phạm vi nhánh/API, bộ giải mã **JWT Base64URL & đếm ngược TTL (`RoleProfile.JwtInfo`)**, và chạy kiểm thử phân quyền đa Role (`RequestComposerDialog`).
  4. **`Mục tiêu Excel` (`TargetExcelTab`):** Theo dõi độ phủ checklist `.xlsx`/`.csv`, trình sửa **`{placeholder}` tùy biến** (`ExcelSpecMatcher.replaceSegmentWithPlaceholder` & `addCustomPlaceholderSpec`, ví dụ `/api/a/{session}/c`, `/v1/{tenant}/orders/{orderId}`), và danh sách Extra APIs ngoài Excel.
  5. **`Đẩy Logger++` (`PushLedgerPanel` + `ExtenderPushManager`):** Tuân thủ nguyên tắc **Crawl-First, Explicit Batch Push Later** — không đẩy tự động ngầm trong lúc đang crawl; kiểm thử viên lọc, chọn từng nhóm/nhánh/API sạch và bấm đẩy chủ động sang **Logger++ (Extender) -> Elasticsearch** với byte gói tin nguyên bản 100%.
  6. **`Hướng dẫn & Playbooks` (`PlaybooksGuideTab`):** Tài liệu trực quan thuần Java2D (`WrappedText` + `StructuredAdviceCard`, không `<html>`) kèm **5 Playbook 1-Click**: (A) Crawl chỉ đọc GET 100% (`HOLD_AT_PROXY`), (B) Crawl full + Chặn duyệt `POST/PUT/PATCH/DELETE` (`INTERCEPT_WAIT`), (C) Crawl theo mục tiêu Excel `{placeholder}` + `LIVE_ID_POOL`, (D) Kiểm thử phân quyền đa Role + `LOCK_2XX`, (E) Nhả hết về Nguyên bản (`NONE`).
  7. **`Cài đặt` (`BurpSettingsPanel`):** Cấu hình Target Scope & Host, Browser Agent & SPA, Form Auto-Fill, HTTP Header Filter, Protocol & CDP Sidecar.

### 2. Kiến trúc 2 Tầng Action Guardrail & Đồng bộ 0ms (`core/guardrail/*`, `core/sync/*`)
- **Tầng 1 (Pre-Flight & Proxy Gate):**
  - `HOLD_AT_PROXY`: Giữ an toàn tại Burp Proxy và trả về phản hồi ảo `HTTP/1.1 200 OK` (`X-Myth-Proxy-Hold: 1`, `HttpEncodingGuard.buildFullVirtual200OkHttpMessage`) giúp SPA không văng lỗi mạng hay hỏng state; `crawler-agent.js` nhận diện phản hồi ảo và hiển thị trạng thái trung tính.
  - `INTERCEPT_WAIT` (`ProxyInterceptQueue` + `InterceptQueueDialog`): Tạm giữ request trên hàng đợi `CompletableFuture` với **cơ chế Fail-Safe Timeout tự động `DROP`** (không bao giờ treo Burp Proxy), đẩy sự kiện **0ms (`InstantEventBus`)** tự động bung nhánh cây liên quan và gắn nhãn **`[CHỜ DUYỆT]`** mà không cướp focus hay bắt chuyển tab. Cho phép sửa Raw HTTP bảo toàn byte `ISO-8859-1` rồi chọn **`Gửi Server (FORWARD)`**, **`Lưu nháp (SAVE_DRAFT)`** hoặc **`Hủy Drop (DROP)`**.
- **Tầng 2 (Smart Replay & State Protection):**
  - `LOCK_2XX`: Khóa endpoint sau khi đã ghi nhận phản hồi `2xx` thành công đầu tiên.
  - `LIVE_ID_POOL` (`LiveIdPool`): Thu hoạch ID sống từ phản hồi JSON (`GET`/`POST`) và tự thế chỗ ID cũ trên đường dẫn khi replay.
  - `PRODUCER_RECREATE` (`ProducerRecreateEngine`): Ghép cặp API tạo (`POST`) với API tiêu thụ/xóa (`DELETE`/`PUT`).
- **Tương tác UI tường minh (`uiAction`):** `crawler-agent.js` (`resolveUiAction`) phân loại mọi thao tác thành `click | submit | delete | change | navigate` trên `/__myth_crawl/context` để `PolicyEvaluator` khớp chính xác cả Method lẫn hành vi giao diện.

### 3. 7 Luật Kiến trúc Cứng (7 Hard Engineering Invariants — Kiểm chứng tự động bằng `BlueprintGuardTests`)
1. **Law 1 — Color Sovereignty & WCAG AA (`>= 4.5:1`):** Chỉ duy nhất `ui/theme/Theme.java` được phép khởi tạo `new Color(...)`. Mọi `Theme.Token` (22 token x 7 biến thể Light & Dark) đều đạt độ tương phản chuẩn **WCAG AA `>= 4.5:1`** trên mọi bề mặt (`canvas`, `surface`, `surfaceAlt`, `fill`, `fillStrong`, `solidFill`).
2. **Law 2 — Zero FlatLaf:** Không sử dụng `com.formdev.flatlaf` ở bất kỳ đâu trong dự án.
3. **Law 3 — Zero Swing HTML:** Không dùng chuỗi `<html>` trong bất kỳ component Swing nào (`ui/**`); thay bằng `WrappedText` và `StructuredAdviceCard`.
4. **Law 4 — Vector Icons & Bundled OFL Fonts:** Không dùng ký tự trang trí Unicode/Emoji (`>= 0x2600` hoặc `0x2190..0x25FF`) trong `ui/**`; toàn bộ icon vẽ bằng Java2D vector (`VectorIcons.java`) kết hợp bộ font OFL đóng gói sẵn (`BeVietnamPro-*` & `JetBrainsMono-*` qua `FontManager.java`) hiển thị trọn vẹn dấu tiếng Việt.
5. **Law 5 — Single-Row Top Bar (`<= 1100px`):** Thanh điều khiển trên cùng luôn giữ 1 hàng với `preferredSize.width <= 1100px` ở cả Light & Dark Mode.
6. **Law 6 — Pure-Java HTTP Encoding Guard (`HttpEncodingGuard`):** Khử dấu ASCII an toàn (`asciiSafe`) và bảo toàn tuyệt đối byte `ISO-8859-1` khi đọc/sửa Raw HTTP (chống lỗi mojibake UTF-8).
7. **Law 7 — Pure-Java Core (`core/**`):** Toàn bộ gói `com.mythofcrawl.crawler.core.**` thuần Java 17 (`0` import `burp.api.montoya`, `javax.swing`, `java.awt`), giao tiếp với tầng UI qua `UiHost` (`BurpUiHost` / `HeadlessUiHost`).

### 4. Browser Agent thế hệ mới & Đa giao thức (`crawler-agent.js`, `proxy/*`, `excel/*`)
- **Visual Action Trail Replay & UI Spotlight Locator:** Ghi nhận `stepLocator` xuyên `open` & `closed` Shadow DOM và `iframe`, mở lại trình duyệt với hộp điều khiển nổi Shadow DOM (`#__myth_replay_host`) để chạy tự động hoặc khoanh vùng từng bước tới nút kích hoạt API.
- **SPA Routing, SimHash & Service Worker Bypass:** Không gọi `history.back()` phá state SPA; dùng **64-bit DOM SimHash**, `TreeWalker` 1 lượt, xử lý Modal/Portal, tự gỡ Service Worker và tự tạm dừng khi gặp CAPTCHA/WAF.
- **Giải mã `BROTLI` / `GZIP` / `DEFLATE`, WebSocket, GraphQL, Multipart & gRPC-Web:** Hỗ trợ đầy đủ qua `HtmlAgentInjector`, `WebSocketCaptureHandler`, `ParamExtractor`, `SimpleXlsxReader` (chống XXE/Zip-Bomb 40 MB) và `ReportExporter`.

---

## Cấu trúc thư mục

- `dist/theMythOfCrawl-crawler.jar` — File Extension `.jar` đã build sẵn để nạp vào Burp Suite.
- `lib/montoya-api.jar` — PortSwigger Burp Montoya API (`2025.12`) phục vụ biên dịch.
- `src/main/resources/` — `crawler-agent.js`, `version.properties` (`version=V_1.0_beta`), và `fonts/` (`BeVietnamPro-*.ttf`, `JetBrainsMono-*.ttf`).
- `src/main/java/com/mythofcrawl/crawler/`:
  - `core/` — Lõi thuần Java (`core/sync/`, `core/guardrail/`, `core/auth/`, `core/tree/`).
  - `bridge/` — Cầu nối `UiHost`, `BurpUiHost`, `HeadlessUiHost`.
  - `ui/` — Hệ thống giao diện (`ui/theme/`, `ui/kit/`, `ui/workspace/`, `ui/dialogs/`, `CrawlerTabPanel`, `PushLedgerPanel`, `BurpSettingsPanel`).
  - `excel/`, `model/`, `proxy/` — Bộ khớp Excel `{placeholder}`, mô hình dữ liệu, Proxy/Control Plane & Logger++ Extender Push.
- `src/test/java/com/mythofcrawl/crawler/`:
  - `BlueprintGuardTests.java` — Kiểm thử tự động 7 Luật Kiến trúc Cứng, WCAG AA Contrast Matrix, Guardrail Engine, 0ms Push Auto-Expand, và xuất ảnh chụp giao diện Headless Light/Dark PNG (`build/visual-snapshots/`).
  - `CrawlerUnitTests.java` (16 nhóm unit test lõi) & `DeepCrawlerTests.java` (15 nhóm test tích hợp Montoya API).
- `build.ps1` — Biên dịch `--release 17 -Xlint:unchecked,deprecation`, chạy đủ 3 bộ test (`CrawlerUnitTests`, `DeepCrawlerTests`, `BlueprintGuardTests`) trước khi đóng gói `dist/theMythOfCrawl-crawler.jar`.
- `scripts/release-guard.ps1` — Chốt chặn phát hành bất biến (**Immutable Release Guard**) đọc `version.properties`, từ chối ghi đè hoặc xóa git tag/release đã tồn tại.

---

## Build & Kiểm chứng từ mã nguồn (Windows PowerShell)

Yêu cầu **JDK 17 trở lên**:

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\release-guard.ps1 -Action check -RequireJar
```

Script `build.ps1` sẽ biên dịch toàn bộ mã nguồn và chạy `CrawlerUnitTests`, `DeepCrawlerTests`, `BlueprintGuardTests` (bao gồm kiểm tra 7 Luật và kết xuất ảnh chụp PNG Light/Dark tại `build\visual-snapshots\`); chỉ khi tất cả đều pass mới đóng gói `dist\theMythOfCrawl-crawler.jar`.
