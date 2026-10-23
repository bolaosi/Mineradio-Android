package com.mineradio.android.music;

import org.json.JSONObject;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.RSAPublicKeySpec;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Protocol port of the pinned NeteaseCloudMusicApi util/{crypto,request}.js. */
public class NeteaseClient {
    private static final String API_DOMAIN = "https://interface.music.163.com";
    private static final String WEB_DOMAIN = "https://music.163.com";
    private static final String EAPI_KEY = "e82ckenh8dichen8";
    private static final String PRESET_KEY = "0CoJUm6Qyw8W8jud";
    private static final String IV = "0102030405060708";
    private static final String BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final String RSA_MODULUS = "e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3"
        + "ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685"
        + "b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7";
    private static final String API_UA = "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)";
    private static final String WEB_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final HttpTransport transport;
    private final String deviceId = randomHex(16);
    private final String wnmcid = randomLetters(6) + "." + System.currentTimeMillis() + ".01.0";

    public NeteaseClient() { this(new HttpTransport()); }
    public NeteaseClient(HttpTransport transport) { this.transport = transport; }

    public HttpTransport.Reply request(String apiPath, JSONObject data, String cookie) throws Exception {
        return send(apiPath, data, cookie, false);
    }

    /** The upstream login status/account and user playlist modules explicitly select weapi. */
    public HttpTransport.Reply requestWeapi(String apiPath, JSONObject data, String cookie) throws Exception {
        return send(apiPath, data, cookie, true);
    }

    private HttpTransport.Reply send(String apiPath, JSONObject source, String cookie, boolean weapi)
            throws Exception {
        if (apiPath == null || !apiPath.matches("/api/[A-Za-z0-9_/-]+"))
            throw new IOException("INVALID_NETEASE_PATH");
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("REQUEST_CANCELLED");
        Map<String, String> cookies = processedCookies(cookie, apiPath);
        JSONObject data = source == null ? new JSONObject() : new JSONObject(source.toString());
        // A plain JSON response avoids conflating binary decryption failure with login state.
        data.put("e_r", false);
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> encrypted;
        String target;
        try {
            if (weapi) {
                data.put("csrf_token", value(cookies, "__csrf", ""));
                headers.put("Referer", WEB_DOMAIN + "/");
                headers.put("User-Agent", WEB_UA);
                headers.put("Cookie", cookieHeader(cookies));
                encrypted = weapiParams(data.toString(), randomSecret());
                target = WEB_DOMAIN + "/weapi/" + apiPath.substring(5);
            } else {
                JSONObject header = eapiHeader(cookies);
                data.put("header", header);
                Map<String, String> headerCookie = new LinkedHashMap<>();
                java.util.Iterator<String> keys = header.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    headerCookie.put(key, String.valueOf(header.get(key)));
                }
                headers.put("Cookie", cookieHeader(headerCookie));
                headers.put("User-Agent", API_UA);
                encrypted = new LinkedHashMap<>();
                encrypted.put("params", eapiParams(apiPath, data.toString()));
                target = API_DOMAIN + "/eapi/" + apiPath.substring(5);
            }
        } catch (Exception encryptionFailure) {
            throw new IOException("NETEASE_ENCRYPTION_FAILED");
        }
        // Transport returns the actual HTTP body/code and Set-Cookie; no login fallback exists here.
        return transport.postForm(target, encrypted, headers);
    }

    private JSONObject eapiHeader(Map<String, String> cookie) throws Exception {
        JSONObject header = new JSONObject();
        header.put("osver", value(cookie, "osver", "Microsoft-Windows-10-Professional-build-19045-64bit"));
        header.put("deviceId", value(cookie, "deviceId", deviceId));
        header.put("os", value(cookie, "os", "pc"));
        header.put("appver", value(cookie, "appver", "3.1.17.204416"));
        header.put("versioncode", value(cookie, "versioncode", "140"));
        header.put("mobilename", value(cookie, "mobilename", ""));
        header.put("buildver", value(cookie, "buildver", String.valueOf(System.currentTimeMillis() / 1000)));
        header.put("resolution", value(cookie, "resolution", "1920x1080"));
        header.put("__csrf", value(cookie, "__csrf", ""));
        header.put("channel", value(cookie, "channel", "netease"));
        header.put("requestId", System.currentTimeMillis() + "_" + String.format(Locale.ROOT, "%04d", RANDOM.nextInt(1000)));
        if (cookie.containsKey("MUSIC_U")) header.put("MUSIC_U", cookie.get("MUSIC_U"));
        if (cookie.containsKey("MUSIC_A")) header.put("MUSIC_A", cookie.get("MUSIC_A"));
        return header;
    }

    private Map<String, String> processedCookies(String text, String path) throws IOException {
        if (text != null && (text.length() > 65536 || text.indexOf('\r') >= 0
            || text.indexOf('\n') >= 0 || text.indexOf('\0') >= 0)) throw new IOException("INVALID_COOKIE");
        Map<String, String> result = new LinkedHashMap<>();
        if (text != null) for (String part : text.split(";")) {
            int equal = part.indexOf('=');
            if (equal > 0) {
                String key = part.substring(0, equal).trim();
                if (key.matches("[A-Za-z0-9_]+")) result.put(key, part.substring(equal + 1).trim());
            }
        }
        String nuid = value(result, "_ntes_nuid", randomHex(32));
        result.put("__remember_me", "true");
        result.put("ntes_kaola_ad", "1");
        result.put("_ntes_nuid", nuid);
        result.put("_ntes_nnid", value(result, "_ntes_nnid", nuid + "," + System.currentTimeMillis()));
        result.put("WNMCID", value(result, "WNMCID", wnmcid));
        result.put("WEVNSM", value(result, "WEVNSM", "1.0.0"));
        result.put("osver", value(result, "osver", "Microsoft-Windows-10-Professional-build-19045-64bit"));
        result.put("deviceId", value(result, "deviceId", deviceId));
        result.put("os", value(result, "os", "pc"));
        result.put("channel", value(result, "channel", "netease"));
        result.put("appver", value(result, "appver", "3.1.17.204416"));
        if (!path.contains("login")) result.put("NMTID", randomHex(16));
        // Anonymous tokens are never copied from the development machine or bundled in the APK.
        return result;
    }

    static String eapiParams(String apiPath, String text) throws Exception {
        String digest = hex(MessageDigest.getInstance("MD5").digest(
            ("nobody" + apiPath + "use" + text + "md5forencrypt").getBytes(StandardCharsets.UTF_8)), false);
        String payload = apiPath + "-36cd479b6b5-" + text + "-36cd479b6b5-" + digest;
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(EAPI_KEY.getBytes(StandardCharsets.UTF_8), "AES"));
        return hex(cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8)), true);
    }

    static Map<String, String> weapiParams(String text, String secret) throws Exception {
        if (secret == null || !secret.matches("[A-Za-z0-9]{16}")) throw new IOException("INVALID_WEAPI_SECRET");
        String params = aesCbc(aesCbc(text, PRESET_KEY), secret);
        Cipher rsa = Cipher.getInstance("RSA/ECB/NoPadding");
        rsa.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(
            new RSAPublicKeySpec(new BigInteger(RSA_MODULUS, 16), BigInteger.valueOf(65537))));
        byte[] reversed = new StringBuilder(secret).reverse().toString().getBytes(StandardCharsets.UTF_8);
        byte[] block = new byte[128];
        System.arraycopy(reversed, 0, block, block.length - reversed.length, reversed.length);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("params", params);
        result.put("encSecKey", hex(rsa.doFinal(block), false));
        return result;
    }

    private static String aesCbc(String text, String key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
            new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8)));
        return base64(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    // java.util.Base64 is API26 on Android; this small encoder also runs on API24.
    private static String base64(byte[] bytes) {
        StringBuilder result = new StringBuilder((bytes.length + 2) / 3 * 4);
        for (int i = 0; i < bytes.length; i += 3) {
            int a = bytes[i] & 255, b = i + 1 < bytes.length ? bytes[i + 1] & 255 : 0;
            int c = i + 2 < bytes.length ? bytes[i + 2] & 255 : 0;
            result.append(BASE64.charAt(a >>> 2)).append(BASE64.charAt(((a & 3) << 4) | (b >>> 4)))
                .append(i + 1 < bytes.length ? BASE64.charAt(((b & 15) << 2) | (c >>> 6)) : '=')
                .append(i + 2 < bytes.length ? BASE64.charAt(c & 63) : '=');
        }
        return result.toString();
    }

    private static String cookieHeader(Map<String, String> cookie) throws Exception {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> item : cookie.entrySet()) {
            if (result.length() > 0) result.append("; ");
            result.append(component(item.getKey())).append('=').append(component(item.getValue()));
        }
        return result.toString();
    }

    private static String component(String text) throws Exception {
        return URLEncoder.encode(text, "UTF-8").replace("+", "%20").replace("%7E", "~")
            .replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")");
    }

    private static String value(Map<String, String> values, String key, String fallback) {
        String value = values.get(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String randomSecret() {
        StringBuilder result = new StringBuilder(16);
        for (int i = 0; i < 16; i++) result.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
        return result.toString();
    }

    private static String randomLetters(int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append((char) ('a' + RANDOM.nextInt(26)));
        return result.toString();
    }

    private static String randomHex(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return hex(bytes, false);
    }

    private static String hex(byte[] bytes, boolean upper) {
        char[] digits = (upper ? "0123456789ABCDEF" : "0123456789abcdef").toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            result[2 * i] = digits[(bytes[i] & 255) >>> 4];
            result[2 * i + 1] = digits[bytes[i] & 15];
        }
        return new String(result);
    }
}
