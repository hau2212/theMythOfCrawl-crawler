package com.mythofcrawl.crawler.core.guardrail;

/**
 * Execution behavior for Stage-1 and Stage-2 Action Guardrail policies.
 */
public enum GuardrailMode {
    NONE("Mở (None)", "None", "Cho phép request đi qua bình thường không chặn."),
    HOLD_AT_PROXY("Chặn ảo (Hold 200)", "Hold 200", "Trả 200 OK ảo tại Proxy nội bộ để SPA không vỡ nhưng không đụng server thật."),
    LOCK_2XX("Khóa ngoài 2xx", "Lock 2xx", "Chặn retry / thao tác nguy hiểm nếu endpoint trước đó trả lỗi hoặc chưa xác thực 2xx."),
    LIVE_ID_POOL("Bơm Live ID", "Live ID", "Thay thế ID cũ bằng ID sống vừa thu hoạch từ GET/POST response."),
    PRODUCER_RECREATE("Tạo mới rồi sửa/xóa", "Recreate", "Chạy POST tạo dữ liệu test tạm trước khi thực thi PUT/DELETE."),
    INTERCEPT_WAIT("Chờ duyệt (Intercept)", "Intercept", "Giữ request tại Proxy Intercept Queue để người dùng Forward, Lưu nháp hoặc Drop.");

    public final String label;
    public final String shortLabel;
    public final String description;

    GuardrailMode(String label, String shortLabel, String description) {
        this.label = label;
        this.shortLabel = shortLabel;
        this.description = description;
    }

    public boolean isBlockingOrHolding() {
        return this == HOLD_AT_PROXY || this == INTERCEPT_WAIT || this == LOCK_2XX;
    }

    public static GuardrailMode fromString(String raw, GuardrailMode fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return GuardrailMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
