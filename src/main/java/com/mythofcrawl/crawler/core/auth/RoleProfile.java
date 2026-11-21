package com.mythofcrawl.crawler.core.auth;

import com.mythofcrawl.crawler.core.guardrail.PolicyRule;
import com.mythofcrawl.crawler.model.MiniJson;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Pure-Java Role / Auth Profile supporting per-role headers, cookies, branch/endpoint scope,
 * and zero-dependency Base64URL JWT decoding with {@code exp} TTL computation.
 */
public final class RoleProfile {

    public static final class JwtInfo {
        public final boolean validFormat;
        public final String headerJson;
        public final String payloadJson;
        public final String subject;
        public final String roleClaim;
        public final String issuer;
        public final long issuedAtEpochSec;
        public final long expiresAtEpochSec;

        public JwtInfo(
            boolean validFormat,
            String headerJson,
            String payloadJson,
            String subject,
            String roleClaim,
            String issuer,
            long issuedAtEpochSec,
            long expiresAtEpochSec
        ) {
            this.validFormat = validFormat;
            this.headerJson = headerJson != null ? headerJson : "";
            this.payloadJson = payloadJson != null ? payloadJson : "";
            this.subject = subject != null ? subject : "";
            this.roleClaim = roleClaim != null ? roleClaim : "";
            this.issuer = issuer != null ? issuer : "";
            this.issuedAtEpochSec = issuedAtEpochSec;
            this.expiresAtEpochSec = expiresAtEpochSec;
        }

        public long remainingSeconds(long nowEpochSec) {
            if (expiresAtEpochSec <= 0L) {
                return -1L;
            }
            return expiresAtEpochSec - nowEpochSec;
        }

        public boolean isExpired(long nowEpochSec) {
            return expiresAtEpochSec > 0L && nowEpochSec >= expiresAtEpochSec;
        }
    }

    public String id;
    public String name;
    public boolean enabled;
    public String tier; // e.g. ADMIN, STAFF, USER, GUEST
    public final Map<String, String> headers = new LinkedHashMap<>();
    public final Map<String, String> cookies = new LinkedHashMap<>();
    public final List<String> branchPrefixes = new ArrayList<>();
    public final List<String> endpointPatterns = new ArrayList<>();
    public String notes;
    public final long createdAtMs;
    public volatile long updatedAtMs;

    public RoleProfile() {
        this(UUID.randomUUID().toString().substring(0, 8), "Default Role", true, "USER");
    }

    public RoleProfile(String id, String name, boolean enabled, String tier) {
        long now = System.currentTimeMillis();
        this.id = (id != null && !id.trim().isEmpty()) ? id.trim() : UUID.randomUUID().toString().substring(0, 8);
        this.name = name != null ? name.trim() : "Role";
        this.enabled = enabled;
        this.tier = (tier != null && !tier.trim().isEmpty()) ? tier.trim().toUpperCase(Locale.ROOT) : "USER";
        this.notes = "";
        this.createdAtMs = now;
        this.updatedAtMs = now;
    }

    public RoleProfile withHeader(String key, String value) {
        if (key != null && !key.trim().isEmpty()) {
            headers.put(key.trim(), value != null ? value : "");
        }
        return this;
    }

    public RoleProfile withCookie(String key, String value) {
        if (key != null && !key.trim().isEmpty()) {
            cookies.put(key.trim(), value != null ? value : "");
        }
        return this;
    }

    public RoleProfile withBranchPrefix(String branchPrefix) {
        if (branchPrefix != null && !branchPrefix.trim().isEmpty()) {
            branchPrefixes.add(branchPrefix.trim());
        }
        return this;
    }

    public RoleProfile withEndpointPattern(String pattern) {
        if (pattern != null && !pattern.trim().isEmpty()) {
            endpointPatterns.add(pattern.trim());
        }
        return this;
    }

    public boolean appliesTo(String branch, String endpoint) {
        if (!enabled) {
            return false;
        }
        if (!branchPrefixes.isEmpty()) {
            String nb = PolicyRule.normalizeBranch(branch).toLowerCase(Locale.ROOT);
            boolean matchedBranch = false;
            for (String p : branchPrefixes) {
                String np = PolicyRule.normalizeBranch(p).toLowerCase(Locale.ROOT);
                if (!np.isEmpty() && (nb.equals(np) || nb.startsWith(np + " >") || nb.startsWith(np + "/"))) {
                    matchedBranch = true;
                    break;
                }
            }
            if (!matchedBranch) {
                return false;
            }
        }
        if (!endpointPatterns.isEmpty()) {
            String ep = endpoint != null ? endpoint.trim().toLowerCase(Locale.ROOT) : "";
            boolean matchedEp = false;
            for (String pat : endpointPatterns) {
                String p = pat.trim().toLowerCase(Locale.ROOT);
                if (!p.isEmpty() && (ep.equals(p) || ep.contains(p))) {
                    matchedEp = true;
                    break;
                }
            }
            if (!matchedEp) {
                return false;
            }
        }
        return true;
    }

    public String findBearerToken() {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if ("authorization".equalsIgnoreCase(e.getKey()) && e.getValue() != null) {
                String v = e.getValue().trim();
                if (v.regionMatches(true, 0, "Bearer ", 0, 7)) {
                    return v.substring(7).trim();
                }
                if (looksLikeJwt(v)) {
                    return v;
                }
            }
        }
        for (String cv : cookies.values()) {
            if (looksLikeJwt(cv)) {
                return cv.trim();
            }
        }
        return "";
    }

    public JwtInfo getJwtInfo() {
        String tok = findBearerToken();
        return tok.isEmpty() ? new JwtInfo(false, "", "", "", "", "", 0L, 0L) : decodeJwt(tok);
    }

    public static JwtInfo decodeJwt(String rawTokenOrHeader) {
        if (rawTokenOrHeader == null || rawTokenOrHeader.trim().isEmpty()) {
            return new JwtInfo(false, "", "", "", "", "", 0L, 0L);
        }
        String token = rawTokenOrHeader.trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).trim();
        }
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return new JwtInfo(false, "", "", "", "", "", 0L, 0L);
        }
        try {
            String headerJson = decodeBase64Url(parts[0]);
            String payloadJson = decodeBase64Url(parts[1]);
            if (!payloadJson.trim().startsWith("{")) {
                return new JwtInfo(false, headerJson, payloadJson, "", "", "", 0L, 0L);
            }
            Map<String, Object> claims = MiniJson.parseObject(payloadJson);
            String sub = MiniJson.asString(claims, "sub", "");
            String iss = MiniJson.asString(claims, "iss", "");
            String role = MiniJson.asString(claims, "role", "");
            if (role.isEmpty()) {
                role = MiniJson.asString(claims, "roles", "");
            }
            if (role.isEmpty()) {
                role = MiniJson.asString(claims, "scope", "");
            }
            long iat = asLong(claims.get("iat"));
            long exp = asLong(claims.get("exp"));
            return new JwtInfo(true, headerJson, payloadJson, sub, role, iss, iat, exp);
        } catch (Exception ignored) {
            return new JwtInfo(false, "", "", "", "", "", 0L, 0L);
        }
    }

    private static String decodeBase64Url(String seg) {
        String padded = seg.trim().replace('-', '+').replace('_', '/');
        int mod = padded.length() % 4;
        if (mod == 2) {
            padded += "==";
        } else if (mod == 3) {
            padded += "=";
        }
        byte[] decoded = Base64.getDecoder().decode(padded);
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private static boolean looksLikeJwt(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        int first = t.indexOf('.');
        int second = first > 0 ? t.indexOf('.', first + 1) : -1;
        return first > 0 && second > first + 1;
    }

    private static long asLong(Object o) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        if (o != null) {
            try {
                return Long.parseLong(String.valueOf(o).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("enabled", Boolean.valueOf(enabled));
        m.put("tier", tier);
        m.put("headers", new LinkedHashMap<>(headers));
        m.put("cookies", new LinkedHashMap<>(cookies));
        m.put("branchPrefixes", new ArrayList<>(branchPrefixes));
        m.put("endpointPatterns", new ArrayList<>(endpointPatterns));
        m.put("notes", notes != null ? notes : "");
        m.put("createdAtMs", Long.valueOf(createdAtMs));
        m.put("updatedAtMs", Long.valueOf(updatedAtMs));
        return m;
    }

    public static RoleProfile fromMap(Map<?, ?> rawMap) {
        if (rawMap == null) {
            return new RoleProfile();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : rawMap.entrySet()) {
            m.put(String.valueOf(e.getKey()), e.getValue());
        }
        RoleProfile rp = new RoleProfile(
            MiniJson.asString(m, "id", ""),
            MiniJson.asString(m, "name", "Role"),
            MiniJson.asBoolean(m, "enabled", true),
            MiniJson.asString(m, "tier", "USER")
        );
        Object hObj = m.get("headers");
        if (hObj instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) hObj).entrySet()) {
                rp.withHeader(String.valueOf(e.getKey()), e.getValue() != null ? String.valueOf(e.getValue()) : "");
            }
        }
        Object cObj = m.get("cookies");
        if (cObj instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) cObj).entrySet()) {
                rp.withCookie(String.valueOf(e.getKey()), e.getValue() != null ? String.valueOf(e.getValue()) : "");
            }
        }
        for (String bp : MiniJson.asStringList(m, "branchPrefixes")) {
            rp.withBranchPrefix(bp);
        }
        for (String ep : MiniJson.asStringList(m, "endpointPatterns")) {
            rp.withEndpointPattern(ep);
        }
        rp.notes = MiniJson.asString(m, "notes", "");
        rp.updatedAtMs = asLong(m.get("updatedAtMs"));
        return rp;
    }
}
