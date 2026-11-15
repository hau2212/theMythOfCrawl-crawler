package com.mythofcrawl.crawler.model;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * User-configurable values the browser agent types into forms, date pickers, editable tables,
 * rich-text editors and file inputs. Defaults to the literal "TEXT" for every free-text field.
 *
 * Custom rules ("keyword = value" or "/regex/ = value") are matched against an input's
 * name / id / placeholder / aria-label / autocomplete / label text before type defaults apply.
 */
public final class FormFillProfile {

    public static final String DEFAULT_TEXT = "TEXT";

    public static final String DEFAULT_RULES =
        "# Mỗi dòng: từ_khóa = giá_trị  (so khớp name / id / placeholder / label / aria-label, không phân biệt hoa thường)\n"
        + "# Dùng /regex/ = giá_trị cho biểu thức chính quy. Dòng bắt đầu bằng # là ghi chú.\n"
        + "# Ví dụ:\n"
        + "# username = admin\n"
        + "# otp = 123456\n"
        + "# /^(dob|birth|ngay_?sinh)/ = 2000-01-01\n";

    public enum Field {
        TEXT("text", "Văn bản (text)", DEFAULT_TEXT),
        TEXTAREA("textarea", "Đoạn văn (textarea)", DEFAULT_TEXT),
        SEARCH("search", "Ô tìm kiếm / lọc", DEFAULT_TEXT),
        RICHTEXT("richtext", "Rich text / ô bảng sửa được (contenteditable)", DEFAULT_TEXT),
        EMAIL("email", "Email", "text@example.com"),
        PASSWORD("password", "Mật khẩu", "TEXT@123456"),
        NUMBER("number", "Số (number / range)", "1"),
        TEL("tel", "Điện thoại (tel)", "0900000000"),
        URL("url", "URL", "https://example.com"),
        DATE("date", "Ngày (date: YYYY-MM-DD)", "2026-01-01"),
        TIME("time", "Giờ (time: HH:mm)", "09:00"),
        DATETIME("datetime-local", "Ngày giờ (YYYY-MM-DDTHH:mm)", "2026-01-01T09:00"),
        MONTH("month", "Tháng (YYYY-MM)", "2026-01"),
        WEEK("week", "Tuần (YYYY-Www)", "2026-W01"),
        COLOR("color", "Màu (#RRGGBB)", "#000000"),
        FILE_NAME("fileName", "Tên file upload", "TEXT.txt"),
        FILE_CONTENT("fileContent", "Nội dung file upload", DEFAULT_TEXT);

        public final String key;
        public final String label;
        public final String defaultValue;

        Field(String key, String label, String defaultValue) {
            this.key = key;
            this.label = label;
            this.defaultValue = defaultValue;
        }

        /** Free-text style fields that the "apply one value to all text fields" shortcut updates. */
        public boolean isTextLike() {
            return this == TEXT || this == TEXTAREA || this == SEARCH || this == RICHTEXT || this == FILE_CONTENT;
        }

        public static Field byKey(String key) {
            for (Field f : values()) {
                if (f.key.equals(key)) {
                    return f;
                }
            }
            return null;
        }
    }

    private final EnumMap<Field, String> values = new EnumMap<>(Field.class);
    private volatile String rulesText = DEFAULT_RULES;
    private volatile boolean overwriteExisting = false;
    private volatile boolean uploadFiles = true;

    public FormFillProfile() {
        resetDefaults();
    }

    public synchronized void resetDefaults() {
        for (Field f : Field.values()) {
            values.put(f, f.defaultValue);
        }
        rulesText = DEFAULT_RULES;
        overwriteExisting = false;
        uploadFiles = true;
    }

    public synchronized String get(Field f) {
        String v = values.get(f);
        return v != null ? v : f.defaultValue;
    }

    public synchronized void set(Field f, String value) {
        values.put(f, value != null ? value : "");
    }

    public synchronized void applyToAllTextFields(String value) {
        for (Field f : Field.values()) {
            if (f.isTextLike()) {
                values.put(f, value != null ? value : "");
            }
        }
    }

    public String getRulesText() {
        return rulesText;
    }

    public void setRulesText(String rulesText) {
        this.rulesText = rulesText != null ? rulesText : "";
    }

    public boolean isOverwriteExisting() {
        return overwriteExisting;
    }

    public void setOverwriteExisting(boolean overwriteExisting) {
        this.overwriteExisting = overwriteExisting;
    }

    public boolean isUploadFiles() {
        return uploadFiles;
    }

    public void setUploadFiles(boolean uploadFiles) {
        this.uploadFiles = uploadFiles;
    }

    /** Serializable map consumed by crawler-agent.js through /__myth_crawl/state. */
    public synchronized Map<String, Object> toAgentMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Field f : Field.values()) {
            m.put(f.key, get(f));
        }
        m.put("rules", parseRules(rulesText));
        m.put("overwrite", overwriteExisting);
        m.put("uploadFiles", uploadFiles);
        return m;
    }

    /** Persistable map (values + rules + flags). */
    public synchronized Map<String, Object> toPersistMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> vals = new LinkedHashMap<>();
        for (Field f : Field.values()) {
            vals.put(f.key, get(f));
        }
        m.put("values", vals);
        m.put("rulesText", rulesText);
        m.put("overwrite", overwriteExisting);
        m.put("uploadFiles", uploadFiles);
        return m;
    }

    @SuppressWarnings("unchecked")
    public synchronized void applyPersistMap(Map<String, Object> m) {
        if (m == null) {
            return;
        }
        Object vals = m.get("values");
        if (vals instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) vals).entrySet()) {
                Field f = Field.byKey(String.valueOf(e.getKey()));
                if (f != null && e.getValue() != null) {
                    values.put(f, String.valueOf(e.getValue()));
                }
            }
        }
        if (m.get("rulesText") != null) {
            rulesText = String.valueOf(m.get("rulesText"));
        }
        if (m.get("overwrite") instanceof Boolean) {
            overwriteExisting = (Boolean) m.get("overwrite");
        }
        if (m.get("uploadFiles") instanceof Boolean) {
            uploadFiles = (Boolean) m.get("uploadFiles");
        }
    }

    /**
     * Parses "keyword = value" / "/regex/ = value" lines. Invalid regexes and comments are skipped.
     */
    public static List<Map<String, Object>> parseRules(String text) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return out;
        }
        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String pattern = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (pattern.isEmpty()) {
                continue;
            }
            boolean regex = false;
            if (pattern.length() >= 2 && pattern.startsWith("/") && pattern.endsWith("/")) {
                pattern = pattern.substring(1, pattern.length() - 1);
                regex = true;
                try {
                    Pattern.compile(pattern);
                } catch (Exception ex) {
                    continue;
                }
            }
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("pattern", pattern);
            rule.put("value", value);
            rule.put("regex", regex);
            out.add(rule);
        }
        return out;
    }
}
