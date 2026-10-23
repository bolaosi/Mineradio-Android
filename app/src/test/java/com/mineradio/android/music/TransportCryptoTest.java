package com.mineradio.android.music;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;

public class TransportCryptoTest {
    // Independent expected vectors produced by the pinned JS crypto-js/node-forge implementation.
    @Test public void eapiMatchesExistingJavaScriptIncludingUnicodeAndLargeData() throws Exception {
        assertEquals("1CA81A97B7BAEB099F29A9B99A25CD6E58E7BA5B35F25231033065C776830C0ACD212933C9432002B586ED0F458155F631B44BD2C5B733983A66E6224C0585A52777484232CB13E16B21684479BA3B9B3CF9FA119BBE8E17B07F40E02346BE3781A9B22A53CC3C44EC77569FAD1631FF",
            NeteaseClient.eapiParams("/api/login/qrcode/unikey", "{\"type\":3,\"e_r\":false}"));
        assertEquals("2B5D64177AA6460FBAA3DCB1285E28954BBB4F7556E09B0FB25750F12398BB507CB8FFD13EAB28F35B05AFC3ABA333C99856D3FAA5DB96F718E4E4FC9F4FEF9F96A1C450E931F62E7C62778E951992D03565059F160BF53B62B4D2D656A8B657B4EC69458381050CBD35ED6AAD86BD1F17287B23E4D9E7DBB2E86CCE3C770683FE1030E6E38B3D26E3B4A66D94B9F4A8",
            NeteaseClient.eapiParams("/api/cloudsearch/pc", "{\"s\":\"夜曲\",\"type\":1,\"limit\":2,\"offset\":0,\"e_r\":false}"));
        String large = "{\"s\":\"" + String.join("", Collections.nCopies(40000, "夜")) + "\",\"e_r\":false}";
        assertEquals("0511f4c82fa9056d5bb71b695d295ef3c99b964650bc656d4924f06e88d879a6",
            hex(MessageDigest.getInstance("SHA-256").digest(NeteaseClient.eapiParams(
                "/api/cloudsearch/pc", large).getBytes(StandardCharsets.UTF_8))));
    }

    @Test public void weapiMatchesExistingJavaScriptRsaAndBothAesStages() throws Exception {
        Map<String, String> encrypted = NeteaseClient.weapiParams("{\"hello\":\"测试\",\"e_r\":false}", "aaaaaaaaaaaaaaaa");
        assertEquals("aelvwAJ7JhmHu5gDjNzcq7jpVA9lEx+myWQC66JtU5eE5ZTsdAEfo7LhtCaDmZXZ", encrypted.get("params"));
        assertEquals("d473b9eca232f1b4090dd606b0df86de318748dd2eec307e4ed4345030fc4ee30331e598f41d5a6f5befaab94630ea1a1eda7cfade84fbec1a907913d2e4d2c8744bc572b99a050075e075b4537f645ecfa994f95906c32818e076aeda6bdb906bfa0bb96c4cf4bc3ed6d9ab76cf08441153d9d85e1ea3d78fa8d9210d581cee", encrypted.get("encSecKey"));
    }

    @Test public void requestBuildsRealProtocolAndPreservesCallerDataAndCookiePadding() throws Exception {
        CaptureTransport transport = new CaptureTransport();
        NeteaseClient client = new NeteaseClient(transport);
        JSONObject original = new JSONObject().put("type", 3).put("e_r", true);
        HttpTransport.Reply reply = client.request("/api/login/qrcode/unikey", original, "MUSIC_U=test==; __csrf=csrf");
        assertEquals("https://interface.music.163.com/eapi/login/qrcode/unikey", transport.url);
        JSONObject sent = decryptedData(transport.form.get("params"));
        assertFalse(sent.getBoolean("e_r"));
        assertEquals("test==", sent.getJSONObject("header").getString("MUSIC_U"));
        assertEquals("csrf", sent.getJSONObject("header").getString("__csrf"));
        assertTrue(transport.headers.get("Cookie").contains("MUSIC_U=test%3D%3D"));
        assertFalse(transport.headers.get("Cookie").contains("MUSIC_A"));
        assertTrue(original.getBoolean("e_r"));
        assertFalse(original.has("header"));
        assertEquals(801, reply.body.getInt("code"));
        assertFalse(reply.body.has("loggedIn"));
        client.requestWeapi("/api/w/nuser/account/get", new JSONObject(), "");
        assertEquals("https://music.163.com/weapi/w/nuser/account/get", transport.url);
        assertTrue(transport.form.containsKey("encSecKey"));
    }

    @Test public void rejectsInjectedApiPathsAndCookieHeadersBeforeTransport() throws Exception {
        CaptureTransport transport = new CaptureTransport();
        NeteaseClient client = new NeteaseClient(transport);
        for (String path : Arrays.asList("https://evil.test/api/x", "/api/../x", "/api/x?q=1", "/api/x#f", "/api/%2e%2e/x")) {
            try { client.request(path, new JSONObject(), ""); fail(); }
            catch (IOException expected) { assertEquals("INVALID_NETEASE_PATH", expected.getMessage()); }
        }
        try { client.request("/api/login/qrcode/unikey", new JSONObject(), "MUSIC_U=x\r\nHost: evil.test"); fail(); }
        catch (IOException expected) { assertEquals("INVALID_COOKIE", expected.getMessage()); }
        assertEquals(0, transport.calls);
    }

    @Test public void transportRejectsNonProviderTargetsBeforeOpeningAConnection() throws Exception {
        FakeTransport transport = new FakeTransport(new FakeConnection(200, "{\"code\":200}"));
        for (String url : Arrays.asList("http://music.163.com/api/x", "https://127.0.0.1/x", "https://music.163.com.evil.test/x",
            "https://music.163.com@evil.test/x", "https://evil.test@music.163.com/x", "https://c.y.qq.com:444/x", "file:///etc/passwd")) {
            try { transport.get(url, null); fail(); }
            catch (IOException expected) { assertEquals("API_URL_REJECTED", expected.getMessage()); }
        }
        assertEquals(0, transport.opens);
    }

    @Test public void redirectNeverForwardsLoginCookieEvenToAnotherAllowedHost() throws Exception {
        FakeConnection connection = new FakeConnection(302, "");
        FakeTransport transport = new FakeTransport(connection);
        try { transport.get("https://music.163.com/api/x", Collections.singletonMap("Cookie", "MUSIC_U=fake")); fail(); }
        catch (IOException expected) { assertEquals("REDIRECT_REJECTED", expected.getMessage()); }
        assertFalse(connection.getInstanceFollowRedirects());
        assertEquals(1, transport.opens);
        assertTrue(connection.disconnected);
    }

    @Test public void jsonAndSetCookieAreReturnedWithoutInventingLoginSuccess() throws Exception {
        FakeConnection connection = new FakeConnection(400, "{\"code\":400,\"loggedIn\":false}");
        connection.cookies = Arrays.asList("MUSIC_U=fake==; HttpOnly; Secure", "__csrf=abc; Path=/");
        HttpTransport.Reply reply = new FakeTransport(connection).get("https://music.163.com/api/x", null);
        assertEquals(400, reply.status);
        assertEquals(400, reply.body.getInt("code"));
        assertFalse(reply.body.getBoolean("loggedIn"));
        assertEquals(connection.cookies, reply.cookies);
        assertTrue(connection.disconnected);
    }

    @Test public void gzipAndLegacyQqJsonpWorkWithoutExecutingJavascript() throws Exception {
        FakeConnection connection = new FakeConnection(200, "");
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write("profileCallback({\"code\":0,\"name\":\"测试\"});".getBytes(StandardCharsets.UTF_8));
        }
        connection.bytes = compressed.toByteArray(); connection.encoding = "gzip";
        HttpTransport.Reply reply = new FakeTransport(connection).get("https://c.y.qq.com/test", null);
        assertEquals("测试", reply.body.getString("name"));
        FakeConnection unsafe = new FakeConnection(200, "callback({\"code\":0});evil()");
        try { new FakeTransport(unsafe).get("https://c.y.qq.com/test", null); fail(); }
        catch (IOException expected) { assertEquals("INVALID_JSON_RESPONSE", expected.getMessage()); }
    }

    @Test public void formEncodingAndFailuresDoNotExposeCredentialsOrOriginalErrors() throws Exception {
        Map<String, String> values = new LinkedHashMap<>(); values.put("params", "a+b/= 测试");
        assertEquals("params=a%2Bb%2F%3D+%E6%B5%8B%E8%AF%95", HttpTransport.encodeForm(values));
        FakeConnection bad = new FakeConnection(200, "private cookie hidden in invalid HTML");
        try { new FakeTransport(bad).get("https://u.y.qq.com/test?token=private", null); fail(); }
        catch (IOException expected) { assertEquals("INVALID_JSON_RESPONSE", expected.getMessage()); assertNull(expected.getCause()); }
        HttpTransport networkFailure = new HttpTransport() {
            @Override protected HttpURLConnection openConnection(URL url) throws IOException {
                throw new IOException("https://private.test/?MUSIC_U=private");
            }
        };
        try { networkFailure.get("https://u.y.qq.com/test", null); fail(); }
        catch (IOException expected) { assertEquals("NETWORK_ERROR", expected.getMessage()); assertNull(expected.getCause()); }
    }

    @Test public void cancellationDisconnectsAndNeverReturnsLateData() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        FakeConnection connection = new FakeConnection(200, "{}") {
            @Override public InputStream getInputStream() {
                return new InputStream() {
                    @Override public int read() throws IOException {
                        reading.countDown();
                        while (!disconnected && !Thread.currentThread().isInterrupted()) {
                            try { Thread.sleep(5); } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        throw new InterruptedIOException("private error must not escape");
                    }
                };
            }
        };
        FakeTransport transport = new FakeTransport(connection);
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread owner = new Thread(() -> {
            try { transport.get("https://music.163.com/api/x", null); result.set(new AssertionError("Late data returned")); }
            catch (Throwable failure) { result.set(failure); }
        });
        owner.start(); assertTrue(reading.await(2, TimeUnit.SECONDS));
        owner.interrupt(); transport.cancel(owner); owner.join(2000);
        assertFalse(owner.isAlive()); assertTrue(connection.disconnected);
        assertTrue(result.get() instanceof InterruptedIOException);
        assertEquals("REQUEST_CANCELLED", result.get().getMessage());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test public void networkTimeoutIsDistinctFromUserCancellation() throws Exception {
        HttpTransport transport = new HttpTransport() {
            @Override protected HttpURLConnection openConnection(URL url) throws IOException {
                throw new java.net.SocketTimeoutException("private target must not escape");
            }
        };
        try { transport.get("https://music.163.com/api/x", null); fail(); }
        catch (IOException expected) { assertEquals("NETWORK_TIMEOUT", expected.getMessage()); assertNull(expected.getCause()); }
    }

    private static class CaptureTransport extends HttpTransport {
        String url; Map<String, String> form, headers; int calls;
        @Override public Reply postForm(String url, Map<String, String> form, Map<String, String> headers) throws Exception {
            this.url = url; this.form = form; this.headers = headers; calls++;
            return new Reply(200, new JSONObject().put("code", 801), Collections.emptyList());
        }
    }
    private static class FakeTransport extends HttpTransport {
        final FakeConnection connection; int opens;
        FakeTransport(FakeConnection connection) { this.connection = connection; }
        @Override protected HttpURLConnection openConnection(URL url) { opens++; return connection; }
    }
    private static class FakeConnection extends HttpURLConnection {
        final int status; byte[] bytes; String encoding; volatile boolean disconnected;
        List<String> cookies = Collections.emptyList();
        FakeConnection(int status, String text) throws Exception {
            super(new URL("https://music.163.com/test")); this.status = status;
            bytes = text.getBytes(StandardCharsets.UTF_8);
        }
        @Override public int getResponseCode() { return status; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(bytes); }
        @Override public String getContentEncoding() { return encoding; }
        @Override public Map<String, List<String>> getHeaderFields() { return Collections.singletonMap("Set-Cookie", cookies); }
        @Override public String getHeaderFieldKey(int index) { return null; }
        @Override public String getHeaderField(int index) { return null; }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
    private static JSONObject decryptedData(String hex) throws Exception {
        byte[] encrypted = new byte[hex.length() / 2];
        for (int i = 0; i < encrypted.length; i++) encrypted[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec("e82ckenh8dichen8".getBytes(StandardCharsets.UTF_8), "AES"));
        String payload = new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        String separator = "-36cd479b6b5-";
        return new JSONObject(payload.substring(payload.indexOf(separator) + separator.length(), payload.lastIndexOf(separator)));
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(); for (byte value : bytes) out.append(String.format("%02x", value & 255)); return out.toString();
    }
}
