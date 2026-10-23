package com.mineradio.android.music;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** JSON requests to official provider API hosts only. No shared cookie jar or redirects. */
public class HttpTransport {
    private static final Set<String> API_HOSTS = Collections.unmodifiableSet(
        new java.util.HashSet<>(Arrays.asList("music.163.com", "interface.music.163.com",
            "interface3.music.163.com", "c.y.qq.com", "u.y.qq.com", "y.qq.com")));
    private static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    private static final String DEFAULT_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
    private final ConcurrentHashMap<Thread, Set<HttpURLConnection>> active = new ConcurrentHashMap<>();

    public static class Reply {
        public final int status;
        public final JSONObject body;
        public final List<String> cookies;

        public Reply(int status, JSONObject body, List<String> cookies) {
            this.status = status;
            this.body = body;
            this.cookies = Collections.unmodifiableList(new ArrayList<>(
                cookies == null ? Collections.<String>emptyList() : cookies));
        }
    }

    public Reply get(String url, Map<String, String> headers) throws Exception {
        return request("GET", url, headers, null);
    }

    public Reply post(String url, JSONObject data, Map<String, String> headers) throws Exception {
        Map<String, String> requestHeaders = copyHeaders(headers);
        requestHeaders.put("Content-Type", "application/json; charset=UTF-8");
        return request("POST", url, requestHeaders, data.toString().getBytes(StandardCharsets.UTF_8));
    }

    public Reply postForm(String url, Map<String, String> data, Map<String, String> headers) throws Exception {
        Map<String, String> requestHeaders = copyHeaders(headers);
        requestHeaders.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        return request("POST", url, requestHeaders, encodeForm(data).getBytes(StandardCharsets.UTF_8));
    }

    /** Disconnect only this owner's current calls; callers may also Future.cancel(true). */
    public void cancel(Thread owner) {
        Set<HttpURLConnection> calls = active.get(owner);
        if (calls != null) for (HttpURLConnection connection : calls) connection.disconnect();
    }

    /** Overridable for deterministic JVM tests. URL policy is enforced before this method. */
    protected HttpURLConnection openConnection(URL url) throws IOException {
        return (HttpURLConnection) url.openConnection();
    }

    protected Reply request(String method, String target, Map<String, String> headers, byte[] data)
            throws Exception {
        checkInterrupted();
        URL url = checkedApiUrl(target);
        if (!"GET".equals(method) && !"POST".equals(method)) throw new IOException("INVALID_METHOD");
        if (data != null && data.length > MAX_JSON_BYTES) throw new IOException("REQUEST_TOO_LARGE");
        Map<String, String> safeHeaders = copyHeaders(headers);
        for (Map.Entry<String, String> header : safeHeaders.entrySet()) validateHeader(header.getKey(), header.getValue());
        HttpURLConnection connection = null;
        Thread owner = Thread.currentThread();
        Set<HttpURLConnection> calls = active.computeIfAbsent(owner,
            key -> Collections.newSetFromMap(new ConcurrentHashMap<HttpURLConnection, Boolean>()));
        try {
            connection = openConnection(url);
            calls.add(connection);
            checkInterrupted();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setUseCaches(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("User-Agent", DEFAULT_UA);
            connection.setRequestProperty("Accept", "application/json, text/plain, */*");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            for (Map.Entry<String, String> header : safeHeaders.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (data != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream stream = connection.getOutputStream()) {
                    for (int offset = 0; offset < data.length; offset += 8192) {
                        checkInterrupted();
                        stream.write(data, offset, Math.min(8192, data.length - offset));
                    }
                }
            }
            checkInterrupted();
            int status = connection.getResponseCode();
            // No Location is followed, even between two allowed hosts: login cookies stay put.
            if (status >= 300 && status < 400) throw new IOException("REDIRECT_REJECTED");
            InputStream raw = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text;
            if (raw == null) {
                text = "";
            } else {
                try (InputStream input = decodedStream(raw, connection.getContentEncoding())) {
                    text = new String(readLimited(input), StandardCharsets.UTF_8);
                }
            }
            checkInterrupted();
            JSONObject body;
            if (text.trim().isEmpty() && status >= 400) {
                body = new JSONObject().put("code", status).put("error", "UPSTREAM_HTTP_ERROR");
            } else {
                body = parseObject(text);
            }
            return new Reply(status, body, readCookies(connection));
        } catch (InterruptedIOException cancelled) {
            if (Thread.currentThread().isInterrupted() || "REQUEST_CANCELLED".equals(cancelled.getMessage()))
                throw new InterruptedIOException("REQUEST_CANCELLED");
            throw new IOException("NETWORK_TIMEOUT");
        } catch (IOException failure) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("REQUEST_CANCELLED");
            String code = failure.getMessage();
            if (!"REDIRECT_REJECTED".equals(code) && !"RESPONSE_TOO_LARGE".equals(code)
                && !"INVALID_JSON_RESPONSE".equals(code)) code = "NETWORK_ERROR";
            // Never attach the original exception: it can contain a signed URL or credentials.
            throw new IOException(code);
        } finally {
            if (connection != null) {
                calls.remove(connection);
                connection.disconnect();
            }
            if (calls.isEmpty()) active.remove(owner, calls);
        }
    }

    private static URL checkedApiUrl(String target) throws IOException {
        try {
            URI uri = new URI(target);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || !API_HOSTS.contains(host.toLowerCase(Locale.ROOT))
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) throw new IOException("API_URL_REJECTED");
            return uri.toURL();
        } catch (Exception failure) {
            throw new IOException("API_URL_REJECTED");
        }
    }

    private static Map<String, String> copyHeaders(Map<String, String> headers) {
        return headers == null ? new LinkedHashMap<String, String>() : new LinkedHashMap<>(headers);
    }

    private static void validateHeader(String name, String value) throws IOException {
        if (name == null || value == null || !name.matches("[A-Za-z0-9-]+") || value.length() > 65536
            || "Host".equalsIgnoreCase(name) || "Content-Length".equalsIgnoreCase(name)
            || "Transfer-Encoding".equalsIgnoreCase(name) || "Connection".equalsIgnoreCase(name))
            throw new IOException("INVALID_HEADER");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c == 127) throw new IOException("INVALID_HEADER");
        }
    }

    private static InputStream decodedStream(InputStream raw, String encoding) throws IOException {
        if (encoding == null || encoding.isEmpty() || "identity".equalsIgnoreCase(encoding)) return raw;
        if ("gzip".equalsIgnoreCase(encoding)) return new GZIPInputStream(raw);
        if ("deflate".equalsIgnoreCase(encoding)) return new InflaterInputStream(raw);
        raw.close();
        throw new IOException("UNSUPPORTED_ENCODING");
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            checkInterrupted();
            if (count > MAX_JSON_BYTES - result.size()) throw new IOException("RESPONSE_TOO_LARGE");
            result.write(buffer, 0, count);
        }
        return result.toByteArray();
    }

    private static JSONObject parseObject(String text) throws IOException {
        try {
            String json = text.trim();
            if (json.startsWith("\ufeff")) json = json.substring(1).trim();
            // Some legacy QQ APIs return a JSONP wrapper even with format=json.
            if (!json.startsWith("{") && json.matches("(?s)[A-Za-z_$][A-Za-z0-9_$.]*\\s*\\(.*")) {
                int open = json.indexOf('('), close = json.lastIndexOf(')');
                String suffix = close < 0 ? "invalid" : json.substring(close + 1).trim();
                if (!(suffix.isEmpty() || ";".equals(suffix))) throw new IOException("INVALID_JSON_RESPONSE");
                json = json.substring(open + 1, close).trim();
            }
            JSONTokener token = new JSONTokener(json);
            JSONObject object = new JSONObject(token);
            if (token.nextClean() != 0) throw new IOException("INVALID_JSON_RESPONSE");
            return object;
        } catch (Exception invalid) {
            throw new IOException("INVALID_JSON_RESPONSE");
        }
    }

    private static List<String> readCookies(HttpURLConnection connection) {
        List<String> cookies = new ArrayList<>();
        // Indexed fields preserve wire order, unlike some getHeaderFields implementations.
        for (int i = 1; i < 512; i++) {
            String name = connection.getHeaderFieldKey(i), value = connection.getHeaderField(i);
            if (name == null && value == null) break;
            if ("Set-Cookie".equalsIgnoreCase(name) && safeCookie(value)) cookies.add(value);
        }
        if (cookies.isEmpty()) {
            Map<String, List<String>> fields = connection.getHeaderFields();
            if (fields != null) for (Map.Entry<String, List<String>> field : fields.entrySet()) {
                if ("Set-Cookie".equalsIgnoreCase(field.getKey()) && field.getValue() != null)
                    for (String value : field.getValue()) if (safeCookie(value)) cookies.add(value);
            }
        }
        return cookies;
    }

    private static boolean safeCookie(String value) {
        return value != null && value.length() <= 65536 && value.indexOf('\r') < 0
            && value.indexOf('\n') < 0 && value.indexOf('\0') < 0;
    }

    public static String encodeForm(Map<String, String> values) throws IOException {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> value : values.entrySet()) {
            if (result.length() > 0) result.append('&');
            result.append(URLEncoder.encode(value.getKey(), "UTF-8")).append('=')
                .append(URLEncoder.encode(value.getValue() == null ? "" : value.getValue(), "UTF-8"));
        }
        return result.toString();
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("REQUEST_CANCELLED");
    }
}
