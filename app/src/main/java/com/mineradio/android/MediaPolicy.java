package com.mineradio.android;

import java.net.URI;
import java.net.HttpURLConnection;
import java.util.Locale;

/** Only public music CDN resources are readable. Never attach account credentials. */
public final class MediaPolicy {
    private MediaPolicy() {}
    public static URI validate(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme(), host = uri.getHost();
            if (host == null || uri.getRawUserInfo() != null || uri.getFragment() != null
                    || !("https".equals(scheme) || "http".equals(scheme))
                    || uri.getPort() != -1 && uri.getPort() != ("https".equals(scheme) ? 443 : 80))
                throw new IllegalArgumentException();
            host = host.toLowerCase(Locale.ROOT);
            // Exact CDN returned by QQ's official GetVkeyServer sip; do not allow all qq.com.
            if (host.equals("aqqmusic.tc.qq.com")) return uri;
            if (host.equals("y.qq.com") && uri.getPath() != null && uri.getPath().startsWith("/music/photo_new/")) return uri;
            for (String domain : new String[]{"music.126.net", "music.163.com", "qqmusic.qq.com",
                    "stream.qq.com", "gtimg.cn", "gtimg.com", "qpic.cn", "qlogo.cn"})
                if (host.equals(domain) || host.endsWith("." + domain)) return uri;
        } catch (Exception ignored) { }
        throw new IllegalArgumentException("Unsupported media host");
    }
    public static HttpURLConnection open(String value, String range) throws Exception {
        URI uri = secureUri(value);
        if (range != null && !range.matches("bytes=\\d*-\\d*")) throw new IllegalArgumentException();
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Referer", uri.getHost().contains("126.net") || uri.getHost().contains("163.com")
                    ? "https://music.163.com/" : "https://y.qq.com/");
            if (range != null) connection.setRequestProperty("Range", range);
            try {
                int code = connection.getResponseCode();
                if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) return connection;
                String location = connection.getHeaderField("Location");
                if (location == null) throw new IllegalArgumentException();
                uri = secureUri(uri.resolve(location).toString());
            } catch (Exception failure) { connection.disconnect(); throw failure; }
            connection.disconnect();
        }
        throw new IllegalArgumentException("Too many media redirects");
    }
    /** Official APIs still return HTTP URLs. Preserve the signed path/query while using verified TLS CDNs. */
    public static URI secureUri(String value) {
        URI uri=validate(value);
        if("https".equals(uri.getScheme()))return uri;
        return validate("https://"+uri.getHost()+(uri.getRawPath()==null?"":uri.getRawPath())
                +(uri.getRawQuery()==null?"":"?"+uri.getRawQuery()));
    }
}
