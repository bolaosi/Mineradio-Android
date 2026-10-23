package com.mineradio.android.music;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Only the dedicated QQ WebView uses this policy. No provider page gets an app bridge. */
public final class QqLoginPolicy {
    public static final String ENTRY_URL = "https://y.qq.com/n/ryqq/profile";
    public static final List<String> COOKIE_URLS = Collections.unmodifiableList(Arrays.asList(
            "https://y.qq.com/n/ryqq/profile", "https://y.qq.com/n/ryqq_v2/profile",
            "https://y.qq.com/n/ryqq/player", "https://y.qq.com/",
            "https://u.y.qq.com/cgi-bin/musicu.fcg", "https://c.y.qq.com/"));
    private static final Set<String> PAGE_HOSTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "y.qq.com", "graph.qq.com", "connect.qq.com", "openmobile.qq.com",
            "ssl.ptlogin2.qq.com", "xui.ptlogin2.qq.com", "ui.ptlogin2.qq.com", "ptlogin2.qq.com",
            "ptlogin2.tencent.com", "ssl.ptlogin2.tencent.com", "open.weixin.qq.com",
            "ssl.captcha.qq.com", "captcha.qq.com", "turing.captcha.qcloud.com")));
    private static final String[] RESOURCE_DOMAINS = {"qq.com", "tencent.com", "qqmusic.com", "gtimg.com", "gtimg.cn", "qpic.cn", "qcloud.com", "cdn-go.cn"};
    private QqLoginPolicy() {}
    public static boolean validRequestId(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }
    private static URI secure(String value) {
        try {
            if (value == null || value.length() > 16384 || value.indexOf('\\') >= 0) return null;
            URI uri = new URI(value); String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443) return null;
            return uri;
        } catch (Exception ignored) { return null; }
    }
    public static boolean allowPage(String value) { URI uri = secure(value); return uri != null && PAGE_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT)); }
    public static boolean allowResource(String value) {
        URI uri = secure(value); if (uri == null) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        for (String domain : RESOURCE_DOMAINS) if (host.equals(domain) || host.endsWith("." + domain)) return true;
        return false;
    }
    /** An official page may ask to open installed QQ. Never parse arbitrary intent:// extras. */
    public static boolean allowQqApp(String value) {
        try {
            if (value == null || value.length() > 16384 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) return false;
            URI uri = new URI(value); String scheme = uri.getScheme();
            return uri.getRawUserInfo() == null && ("mqq".equals(scheme) || "mqqapi".equals(scheme) || "mqqopensdkapi".equals(scheme));
        } catch (Exception ignored) { return false; }
    }
    /** Music origins first. Generic QQ OAuth host-only cookies are deliberately not collected. */
    public static String mergeMusicCookies(List<String> headers) {
        Map<String, String> selected = new LinkedHashMap<>();
        for (String header : headers) {
            if (header == null) continue;
            if (header.length() > 32768 || header.indexOf('\r') >= 0 || header.indexOf('\n') >= 0) throw new IllegalArgumentException("INVALID_QQ_COOKIE");
            for (String pair : header.split(";")) {
                int eq = pair.indexOf('='); if (eq < 1) continue;
                String name = pair.substring(0, eq).trim(), value = pair.substring(eq + 1).trim();
                if (!name.matches("[A-Za-z0-9_.$-]+") || name.matches("(?i)path|domain|expires|max-age|samesite|secure|httponly") || value.isEmpty()) continue;
                if (!selected.containsKey(name)) selected.put(name, value);
            }
        }
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> pair : selected.entrySet()) { if (result.length() > 0) result.append("; "); result.append(pair.getKey()).append('=').append(pair.getValue()); }
        if (result.length() > 32768) throw new IllegalArgumentException("INVALID_QQ_COOKIE");
        return result.toString();
    }
    public static boolean hasCandidate(String cookie) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String part : cookie.split(";")) { int eq = part.indexOf('='); if (eq > 0) values.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim()); }
        String uin = pick(values, "2".equals(values.get("login_type")) ? new String[]{"wxuin", "uin", "p_uin"} : new String[]{"uin", "qqmusic_uin", "wxuin", "p_uin"});
        uin = uin.replaceFirst("^o", "");
        return uin.matches("[0-9]{1,20}") && !uin.matches("0+") && !pick(values, new String[]{"qm_keyst", "qqmusic_key", "music_key", "p_skey", "skey", "psrf_qqaccess_token", "psrf_qqrefresh_token", "wxrefresh_token", "wxskey"}).isEmpty();
    }
    private static String pick(Map<String, String> values, String[] keys) { for (String key : keys) { String value = values.get(key); if (value != null && !value.isEmpty()) return value; } return ""; }
}
