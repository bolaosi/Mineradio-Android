package com.mineradio.android.music;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class MusicApiTest {
    interface NeHandler { HttpTransport.Reply call(String path, JSONObject data, String cookie, boolean weapi) throws Exception; }
    interface HttpHandler { HttpTransport.Reply call(String url, JSONObject data, Map<String, String> headers) throws Exception; }
    static class MemoryStore implements MusicApi.Store {
        final Map<String, String> values = Collections.synchronizedMap(new LinkedHashMap<>());
        boolean failWrites;
        public String get(String key) { return values.get(key); }
        public void put(String key, String value) { if (failWrites) throw new IllegalStateException("PRIVATE_TEST_STORAGE_ERROR"); values.put(key, value); }
    }
    static class Ne extends NeteaseClient {
        NeHandler handler = (path, data, cookie, weapi) -> reply("{\"code\":301}");
        public HttpTransport.Reply request(String path, JSONObject data, String cookie) throws Exception { return handler.call(path, data, cookie, false); }
        public HttpTransport.Reply requestWeapi(String path, JSONObject data, String cookie) throws Exception { return handler.call(path, data, cookie, true); }
    }
    static class Http extends HttpTransport {
        HttpHandler handler = (url, data, headers) -> reply("{\"code\":1000}");
        public Reply get(String url, Map<String, String> headers) throws Exception { return handler.call(url, null, headers); }
        public Reply post(String url, JSONObject data, Map<String, String> headers) throws Exception { return handler.call(url, data, headers); }
    }
    static class Fixture {
        final MemoryStore store = new MemoryStore(); final Ne ne = new Ne(); final Http http = new Http(); final MusicApi api = new MusicApi(store, ne, http);
    }
    static HttpTransport.Reply reply(String body) throws Exception { return new HttpTransport.Reply(200, new JSONObject(body), Collections.emptyList()); }
    static HttpTransport.Reply account() throws Exception { return reply("{\"code\":200,\"profile\":{\"userId\":42,\"nickname\":\"Test\",\"vipType\":0}}"); }
    static HttpTransport.Reply qqAccount() throws Exception { return reply("{\"code\":0,\"nick\":{\"code\":0,\"data\":{\"info\":{\"uin\":\"12345\",\"nick\":\"TestQQ\"}}}}"); }
    static String cookieBody(boolean qq) throws Exception { return new JSONObject().put("cookie", qq ? "uin=12345; qm_keyst=TEST_ONLY" : "MUSIC_U=TEST_ONLY").toString(); }

    @Test public void healthAndUnsupportedAreTruthfulAndLocal() {
        Fixture f = new Fixture(); MusicApi.ApiResponse health = f.api.route("/api/health", "GET", "");
        assertEquals(200, health.status); assertEquals("builtin", health.body.optString("mode")); assertFalse(health.body.has("authRequired"));
        MusicApi.ApiResponse podcast = f.api.route("/api/podcast/my", "GET", ""); assertEquals(200, podcast.status); assertFalse(podcast.body.optBoolean("supported")); assertEquals(0, podcast.body.optJSONArray("collections").length());
        assertEquals(501, f.api.route("/api/podcast/hot", "GET", "").status);
        assertEquals(405, f.api.route("/api/logout", "GET", "").status);
        assertEquals(400, f.api.route("https://evil.invalid/api/login/status", "GET", "").status);
    }
    @Test public void qrKeyAndCreateUseActualModuleFieldsWithoutExposingCredentials() {
        Fixture f = new Fixture(); f.ne.handler = (path, data, cookie, weapi) -> { assertEquals("/api/login/qrcode/unikey", path); assertEquals(3, data.optInt("type")); assertFalse(weapi); return reply("{\"code\":200,\"unikey\":\"test-key\"}"); };
        assertEquals("test-key", f.api.route("/api/login/qr/key", "GET", "").body.optString("key"));
        MusicApi.ApiResponse qr = f.api.route("/api/login/qr/create?key=test-key", "GET", ""); assertEquals(200, qr.status); assertEquals("https://music.163.com/login?codekey=test-key", qr.body.optString("url"));
        assertEquals(400, f.api.route("/api/login/qr/create", "GET", "").status);
        f.ne.handler = (path, data, cookie, weapi) -> reply("{\"code\":500,\"unikey\":\"test-key\"}");
        assertEquals(502, f.api.route("/api/login/qr/key", "GET", "").status);
    }
    @Test public void qrWaitingScannedAndExpiredAreNotLogin() {
        for (int code : new int[]{800, 801, 802}) {
            Fixture f = new Fixture(); f.ne.handler = (path, data, cookie, weapi) -> reply("{\"code\":" + code + "}");
            JSONObject state = f.api.route("/api/login/qr/check?key=test", "GET", "").body;
            assertEquals(code, state.optInt("code")); assertFalse(state.optBoolean("loggedIn")); assertFalse(state.optBoolean("verified")); assertNull(f.store.get("netease.cookie"));
        }
    }
    @Test public void qr803WithCookieOrProfileOnlyDoesNotFakeSuccess() throws Exception {
        Fixture f = new Fixture(); f.ne.handler = (path, data, cookie, weapi) -> path.contains("qrcode") ? new HttpTransport.Reply(200, new JSONObject("{\"code\":803,\"profile\":{\"userId\":42}}"), Arrays.asList("MUSIC_U=TEST_ONLY; Path=/; HttpOnly")) : reply("{\"code\":301}");
        JSONObject result = f.api.route("/api/login/qr/check?key=test", "GET", "").body;
        assertEquals(803, result.optInt("code")); assertTrue(result.optBoolean("hasCookie")); assertFalse(result.optBoolean("saved")); assertFalse(result.optBoolean("verified")); assertFalse(result.optBoolean("loggedIn")); assertNull(f.store.get("netease.cookie"));
        assertFalse(result.toString().contains("TEST_ONLY"));
    }
    @Test public void verifiedQrPersistsOnlyNormalizedOfficialCookies() throws Exception {
        Fixture f = new Fixture(); f.ne.handler = (path, data, cookie, weapi) -> {
            if (path.contains("qrcode")) return new HttpTransport.Reply(200, new JSONObject("{\"code\":803}"), Arrays.asList("MUSIC_U=TEST_ONLY; Path=/; HttpOnly", "__csrf=TEST_CSRF; Domain=.music.163.com; Secure"));
            assertTrue(weapi); assertTrue(cookie.contains("MUSIC_U=TEST_ONLY")); return account();
        };
        MusicApi.ApiResponse response = f.api.route("/api/login/qr/check?key=test", "GET", "");
        assertEquals(200, response.status); assertTrue(response.body.optBoolean("saved")); assertTrue(response.body.optBoolean("verified"));
        assertEquals("MUSIC_U=TEST_ONLY; __csrf=TEST_CSRF", f.store.get("netease.cookie")); assertFalse(response.body.toString().contains("TEST_ONLY"));
        assertTrue(f.api.route("/api/login/status", "GET", "").body.optBoolean("verified"));
    }
    @Test public void importsRejectInvalidOrUnverifiedAndPreservePreviousCredential() throws Exception {
        Fixture f = new Fixture(); f.store.put("netease.cookie", "MUSIC_U=PREVIOUS_TEST_ONLY");
        assertEquals(400, f.api.route("/api/login/cookie", "POST", "{\"cookie\":\"foo=bar\"}").status);
        MusicApi.ApiResponse rejected = f.api.route("/api/login/cookie", "POST", cookieBody(false)); assertEquals(422, rejected.status); assertFalse(rejected.body.optBoolean("saved"));
        assertEquals("MUSIC_U=PREVIOUS_TEST_ONLY", f.store.get("netease.cookie"));
        f.ne.handler = (path, data, cookie, weapi) -> new HttpTransport.Reply(500, account().body, Collections.emptyList());
        assertFalse(f.api.route("/api/login/status", "GET", "").body.optBoolean("verified"));
        f.ne.handler = (path, data, cookie, weapi) -> { throw new IllegalStateException("SECRET_TEST_SHOULD_NOT_LEAK"); };
        JSONObject unknown = f.api.route("/api/login/status", "GET", "").body; assertEquals("unverified", unknown.optString("credentialState")); assertFalse(unknown.toString().contains("SECRET_TEST"));
    }
    @Test public void accountZeroAndFailedStorageNeverProduceSavedSuccess() throws Exception {
        Fixture f = new Fixture(); f.ne.handler = (path, data, cookie, weapi) -> reply("{\"code\":200,\"profile\":{\"userId\":0}}");
        assertEquals(422, f.api.route("/api/login/cookie", "POST", cookieBody(false)).status);
        f.ne.handler = (path, data, cookie, weapi) -> account(); f.store.failWrites = true;
        MusicApi.ApiResponse response = f.api.route("/api/login/cookie", "POST", cookieBody(false)); assertEquals(502, response.status); assertFalse(response.body.optBoolean("saved")); assertFalse(response.body.toString().contains("PRIVATE_TEST"));
    }
    @Test public void qqRequiresAuthenticatedUserResponseAndNeverCookieNicknameFallback() throws Exception {
        Fixture f = new Fixture(); assertEquals(422, f.api.route("/api/qq/login/cookie", "POST", cookieBody(true)).status);
        assertEquals(400, f.api.route("/api/qq/login/cookie", "POST", "{\"cookie\":\"uin=0; qm_keyst=TEST_ONLY\"}").status);
        f.http.handler = (url, data, headers) -> {
            assertEquals("https://u.y.qq.com/cgi-bin/musicu.fcg", url); assertEquals("music.UserInfo.userInfoServer", data.optJSONObject("nick").optString("module")); assertEquals("GetLoginUserInfo", data.optJSONObject("nick").optString("method")); assertEquals(0, data.optJSONObject("nick").optJSONObject("param").length()); assertTrue(headers.get("Cookie").contains("qm_keyst")); return qqAccount();
        };
        MusicApi.ApiResponse result = f.api.route("/api/qq/login/cookie", "POST", cookieBody(true)); assertEquals(200, result.status); assertTrue(result.body.optBoolean("verified")); assertTrue(result.body.optBoolean("saved")); assertTrue(result.body.optBoolean("playbackKeyReady"));
        f.http.handler = (url, data, headers) -> reply("{\"code\":0,\"nick\":{\"code\":0,\"data\":{\"info\":{\"nick\":\"Wrong\",\"uin\":\"999\"}}}}");
        assertFalse(f.api.route("/api/qq/login/status", "GET", "").body.optBoolean("loggedIn"));
        assertTrue(f.api.route("/api/qq/logout", "POST", "").body.optBoolean("ok")); assertEquals("", f.store.get("qq.cookie"));
    }
    @Test public void searchAndPlaylistTracksNormalizeActualProviderFields() throws Exception {
        Fixture f = new Fixture(); String song = "{\"id\":123,\"name\":\"Music\",\"ar\":[{\"id\":5,\"name\":\"Artist\"}],\"al\":{\"name\":\"Album\",\"picUrl\":\"https://p1.music.126.net/test.jpg\"},\"dt\":10000}";
        f.ne.handler = (path, data, cookie, weapi) -> {
            if (path.equals("/api/cloudsearch/pc")) { assertEquals("test", data.optString("s")); return reply("{\"code\":200,\"result\":{\"songs\":[" + song + "]}}"); }
            if (path.contains("playlist/detail")) return reply("{\"code\":200,\"playlist\":{\"id\":88,\"name\":\"List\",\"tracks\":[],\"trackIds\":[{\"id\":123}]}}");
            assertEquals("/api/v3/song/detail", path); assertEquals(123, new JSONArray(data.optString("c")).optJSONObject(0).optInt("id")); return reply("{\"code\":200,\"songs\":[" + song + "]}");
        };
        JSONObject result = f.api.route("/api/search?keywords=test", "GET", "").body.optJSONArray("songs").optJSONObject(0);
        assertEquals("Artist", result.optString("artist")); assertEquals("Album", result.optString("album")); assertEquals(10000, result.optInt("duration")); assertEquals("netease", result.optString("provider"));
        MusicApi.ApiResponse tracks = f.api.route("/api/playlist/tracks?id=88", "GET", ""); assertEquals(200, tracks.status); assertEquals(1, tracks.body.optJSONArray("tracks").length());
    }
    @Test public void playbackKeepsTrialAndEmptyUrlRestrictionsHonest() throws Exception {
        Fixture f = new Fixture(); f.ne.handler = (path, data, cookie, weapi) -> reply("{\"code\":200,\"data\":[{\"url\":\"https://m.music.126.net/test.mp3\",\"freeTrialInfo\":{\"start\":0,\"end\":30},\"br\":128000}]}");
        JSONObject trial = f.api.route("/api/song/url?id=123&quality=standard", "GET", "").body; assertTrue(trial.optBoolean("playable")); assertTrue(trial.optBoolean("trial"));
        f.ne.handler = (path, data, cookie, weapi) -> reply("{\"code\":200,\"data\":[{\"url\":null}]}"); JSONObject missing = f.api.route("/api/song/url?id=123", "GET", "").body; assertFalse(missing.optBoolean("playable")); assertEquals("", missing.optString("url"));
        f.http.handler = (url, data, headers) -> reply("{\"code\":0,\"req_0\":{\"code\":0,\"data\":{\"midurlinfo\":[{\"purl\":\"\",\"result\":104003}]}}}");
        assertFalse(f.api.route("/api/qq/song/url?mid=test", "GET", "").body.optBoolean("playable"));
        f.http.handler = (url, data, headers) -> reply("{\"code\":0,\"req_0\":{\"code\":1000,\"data\":{\"midurlinfo\":[{\"purl\":\"stale.mp3\"}]}}}");
        MusicApi.ApiResponse rejected = f.api.route("/api/qq/song/url?mid=test", "GET", ""); assertEquals(502, rejected.status); assertFalse(rejected.body.optBoolean("playable"));
    }
    @Test public void playlistLimitIsExplicitlyReportedInsteadOfPretendingAllTracksLoaded() throws Exception {
        Fixture f = new Fixture();
        f.ne.handler = (path, data, cookie, weapi) -> {
            if (path.contains("playlist/detail")) return reply("{\"code\":200,\"playlist\":{\"id\":88,\"name\":\"Large\",\"trackCount\":1000,\"tracks\":[{\"id\":123,\"name\":\"Music\"}]}}");
            throw new AssertionError("unexpected endpoint");
        };
        JSONObject body = f.api.route("/api/playlist/tracks?id=88", "GET", "").body;
        assertTrue(body.optBoolean("partial")); assertEquals(1000, body.optInt("total")); assertEquals(1, body.optInt("loaded")); assertTrue(body.optString("message").contains("1/1000"));
    }
    @Test public void logoutCannotBeUndoneByAnOlderInFlightImport() throws Exception {
        Fixture f = new Fixture(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        f.ne.handler = (path, data, cookie, weapi) -> { if (path.contains("account/get")) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return account(); } return reply("{\"code\":200}"); };
        AtomicReference<MusicApi.ApiResponse> response = new AtomicReference<>();
        Thread worker = new Thread(() -> { try { response.set(f.api.route("/api/login/cookie", "POST", cookieBody(false))); } catch (Exception error) { throw new AssertionError(error); } }); worker.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS)); assertEquals(200, f.api.route("/api/logout", "POST", "").status); release.countDown(); worker.join(5000);
        assertFalse(worker.isAlive()); assertEquals(409, response.get().status); assertEquals("", f.store.get("netease.cookie"));
    }
    @Test public void interruptedValidationCannotSaveCredentials() throws Exception {
        Fixture f = new Fixture(); f.ne.handler = (path, data, cookie, weapi) -> { Thread.currentThread().interrupt(); return account(); };
        try { MusicApi.ApiResponse response = f.api.route("/api/login/cookie", "POST", cookieBody(false)); assertFalse(response.body.optBoolean("saved")); assertNull(f.store.get("netease.cookie")); }
        finally { Thread.interrupted(); }
    }
    @Test public void likesAndNeteaseUserPlaylistUseCopiedWeapiShapes() throws Exception {
        Fixture f = new Fixture(); f.store.put("netease.cookie", "MUSIC_U=TEST_ONLY");
        f.ne.handler = (path, data, cookie, weapi) -> {
            if (path.contains("account/get")) { assertTrue(weapi); return account(); }
            if (path.equals("/api/user/playlist")) { assertTrue(weapi); assertEquals("42", data.optString("uid")); return reply("{\"code\":200,\"playlist\":[{\"id\":5,\"name\":\"Mine\"}]}"); }
            if (path.equals("/api/song/like/get")) return reply("{\"code\":200,\"ids\":[123]}");
            assertEquals("/api/radio/like", path); assertTrue(weapi); assertFalse(data.optBoolean("like")); return reply("{\"code\":200}");
        };
        assertEquals(1, f.api.route("/api/user/playlists", "GET", "").body.optJSONArray("playlists").length());
        JSONObject liked = f.api.route("/api/song/like/check?ids=123,456", "GET", "").body.optJSONObject("liked"); assertTrue(liked.optBoolean("123")); assertFalse(liked.optBoolean("456"));
        assertFalse(f.api.route("/api/song/like?id=123&like=false", "GET", "").body.optBoolean("liked"));
    }
    @Test public void qqPublicSearchPlaybackAndLyricsUseExistingServerProtocol() throws Exception {
        Fixture f = new Fixture();
        String lyric = "[00:01.00]歌词";
        String encoded = java.util.Base64.getEncoder().encodeToString(lyric.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        f.http.handler = (url, data, headers) -> {
            if (url.contains("smartbox_new")) return reply("{\"code\":0,\"data\":{\"song\":{\"itemlist\":[{\"mid\":\"mid1\",\"name\":\"Song\",\"singer\":\"Artist\"}]}}}");
            if (data.has("songinfo")) return reply("{\"code\":0,\"songinfo\":{\"code\":0,\"data\":{\"track_info\":{\"mid\":\"mid1\",\"name\":\"Song\",\"interval\":10,\"singer\":[{\"name\":\"Artist\"}],\"album\":{\"mid\":\"album1\"},\"file\":{\"media_mid\":\"media1\"}}}}}");
            if (data.has("req_0")) {
                JSONObject request = data.optJSONObject("req_0"); assertEquals("vkey.GetVkeyServer", request.optString("module")); assertEquals("M800media1.mp3", request.optJSONObject("param").optJSONArray("filename").optString(0));
                return reply("{\"code\":0,\"req_0\":{\"code\":0,\"data\":{\"sip\":[\"https://ws.stream.qqmusic.qq.com/\"],\"midurlinfo\":[{\"purl\":\"test.mp3\",\"filename\":\"M800media1.mp3\"}]}}}");
            }
            assertEquals("music.musichallSong.PlayLyricInfo", data.optJSONObject("lyric").optString("module"));
            return reply("{\"code\":0,\"lyric\":{\"code\":0,\"data\":{\"lyric\":\"" + encoded + "\"}}}");
        };
        JSONObject song = f.api.route("/api/qq/search?keywords=test", "GET", "").body.optJSONArray("songs").optJSONObject(0);
        assertEquals("qq", song.optString("provider")); assertEquals("media1", song.optString("mediaMid")); assertEquals(10000, song.optInt("duration"));
        JSONObject playback = f.api.route("/api/qq/song/url?mid=mid1&mediaMid=media1&quality=exhigh", "GET", "").body;
        assertTrue(playback.optBoolean("playable")); assertEquals("exhigh", playback.optString("level"));
        assertEquals(lyric, f.api.route("/api/qq/lyric?mid=mid1", "GET", "").body.optString("lyric"));
    }
    @Test public void qqPlaylistPartialFailureAndLegacyTrackShapesStayExplicit() throws Exception {
        Fixture f = new Fixture(); f.store.put("qq.cookie", "uin=12345; qm_keyst=TEST_ONLY");
        f.http.handler = (url, data, headers) -> {
            if (url.contains("musicu")) return qqAccount();
            if (url.contains("fcg_user_created_diss")) return reply("{\"code\":0,\"data\":{\"disslist\":[{\"tid\":9,\"diss_name\":\"Mine\",\"song_cnt\":1}]}}");
            if (url.contains("get_profile_order_asset")) return reply("{\"code\":1000}");
            return reply("{\"code\":0,\"cdlist\":[{\"dissname\":\"Mine\",\"songlist\":[{\"songmid\":\"mid1\",\"songid\":123,\"songname\":\"Music\",\"albummid\":\"album1\",\"interval\":20,\"singer\":[{\"name\":\"Artist\"}]}]}]}");
        };
        JSONObject playlists = f.api.route("/api/qq/user/playlists", "GET", "").body; assertTrue(playlists.optBoolean("partial")); assertEquals(1, playlists.optJSONArray("playlists").length());
        JSONObject track = f.api.route("/api/qq/playlist/tracks?id=9", "GET", "").body.optJSONArray("tracks").optJSONObject(0);
        assertEquals("mid1", track.optString("mid")); assertEquals("Music", track.optString("name")); assertEquals("Artist", track.optString("artist")); assertEquals(20000, track.optInt("duration"));
    }
}
