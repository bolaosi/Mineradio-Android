package com.mineradio.android;

import java.net.URI;

/** URL policy is independent of Android so credential routing can be unit tested. */
final class BackendPolicy {
    private BackendPolicy() { }
    static String normalizeBase(String input) throws Exception {
        String value = input.trim().replaceAll("/+$", "");
        if (value.isEmpty()) return "";
        URI u = new URI(value);
        String host = u.getHost();
        boolean local = host != null && (host.equals("localhost") || host.equals("127.0.0.1")
            || host.matches("10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}") || host.matches("192\\.168\\.\\d{1,3}\\.\\d{1,3}")
            || host.matches("172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}"));
        if (host == null || u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null
            || !("https".equals(u.getScheme()) || ("http".equals(u.getScheme()) && local))
            || (u.getRawPath() != null && !u.getRawPath().isEmpty()))
            throw new IllegalArgumentException("Use an HTTPS origin, or private HTTP for development");
        return value;
    }
    static String apiUrl(String base, String path) throws Exception {
        normalizeBase(base);
        URI u = new URI(path);
        if (base.isEmpty() || !path.startsWith("/api/") || u.isAbsolute() || u.getRawAuthority() != null
            || u.getRawFragment() != null || u.getPath().contains("..") || path.contains("\\")
            || !u.normalize().getPath().startsWith("/api/")) throw new IllegalArgumentException("Invalid API path");
        return base + path;
    }
}
