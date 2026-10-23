import com.mineradio.android.MediaPolicy;
import com.mineradio.android.music.HttpTransport;
import com.mineradio.android.music.MusicApi;
import com.mineradio.android.music.NeteaseClient;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.LinkedHashMap;

/** Real official-service probe with an empty in-memory Store; no raw provider payload output. */
public final class MusicApiLiveProbe {
    private static final class EmptyStore implements MusicApi.Store {
        private final Map<String, String> values = new LinkedHashMap<>();
        public String get(String key) { return values.get(key); }
        public void put(String key, String value) { throw new IllegalStateException("LIVE_PROBE_MUST_NOT_PERSIST_CREDENTIALS"); }
    }
    private static JSONObject record(String operation, MusicApi.ApiResponse response) throws Exception {
        JSONObject item = new JSONObject().put("operation", operation).put("status", response.status);
        if (response.body.has("code")) item.put("code", response.body.optInt("code"));
        String error = response.body.optString("error", "");
        if (!error.isEmpty()) item.put("errorCode", error.matches("[A-Z_]{1,60}") ? error : "UNSPECIFIED");
        return item;
    }
    private static boolean allowedMedia(String url) { if (url == null || url.isEmpty()) return false; try { MediaPolicy.validate(url); return true; } catch (Exception ignored) { return false; } }
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "output/native-api-tests/music-api-live-results.json");
        Files.createDirectories(output.toAbsolutePath().getParent());
        HttpTransport http = new HttpTransport();
        MusicApi api = new MusicApi(new EmptyStore(), new NeteaseClient(http), http);
        JSONObject report = new JSONObject().put("checkedAt", java.time.Instant.now().toString())
                .put("implementation", "actual-java-MusicApi-NeteaseClient-HttpTransport")
                .put("store", "empty-memory-only-no-credential-reads-or-writes")
                .put("realAccountUsed", false).put("loginVerified", false)
                .put("audioDecodedOrPlayed", false).put("proxy", "process-only-existing-loopback-proxy");
        JSONArray results = new JSONArray(); report.put("results", results);
        MusicApi.ApiResponse health = api.route("/api/health", "GET", "");
        results.put(record("health", health).put("builtin", "builtin".equals(health.body.optString("mode"))).put("localReadyOnly", true));

        MusicApi.ApiResponse search = api.route("/api/search?keywords=test&limit=1", "GET", "");
        JSONArray songs = search.body.optJSONArray("songs"); JSONObject song = songs == null ? null : songs.optJSONObject(0);
        results.put(record("netease-search", search).put("count", songs == null ? 0 : songs.length())
                .put("firstSongHasId", song != null && !song.optString("id").isEmpty())
                .put("firstCoverAllowedByMediaPolicy", song != null && allowedMedia(song.optString("cover", ""))));
        if (song != null && song.optString("id").matches("[0-9]{1,20}")) {
            String id = song.optString("id");
            MusicApi.ApiResponse lyric = api.route("/api/lyric?id=" + id, "GET", "");
            results.put(record("netease-lyric", lyric).put("hasLyric", !lyric.body.optString("lyric", "").isEmpty()).put("hasWordLyric", !lyric.body.optString("yrc", "").isEmpty()));
            MusicApi.ApiResponse play = api.route("/api/song/url?id=" + id + "&quality=standard", "GET", "");
            String url = play.body.optString("url", "");
            results.put(record("netease-song-url", play).put("hasUrl", !url.isEmpty()).put("playableReported", play.body.optBoolean("playable", false))
                    .put("trialReported", play.body.optBoolean("trial", false)).put("urlAllowedByMediaPolicy", allowedMedia(url)).put("fullPlaybackVerified", false));
        }

        MusicApi.ApiResponse qq = api.route("/api/qq/search?keywords=test&limit=1", "GET", "");
        JSONArray qqSongs = qq.body.optJSONArray("songs"); JSONObject qqSong = qqSongs == null ? null : qqSongs.optJSONObject(0);
        results.put(record("qq-search", qq).put("count", qqSongs == null ? 0 : qqSongs.length())
                .put("firstSongHasMid", qqSong != null && !qqSong.optString("mid").isEmpty())
                .put("firstCoverAllowedByMediaPolicy", qqSong != null && allowedMedia(qqSong.optString("cover", ""))));
        if (qqSong != null && !qqSong.optString("mid").isEmpty()) {
            MusicApi.ApiResponse lyric = api.route("/api/qq/lyric?mid=" + java.net.URLEncoder.encode(qqSong.optString("mid"), "UTF-8"), "GET", "");
            results.put(record("qq-lyric", lyric).put("hasLyric", !lyric.body.optString("lyric", "").isEmpty()));
        }
        report.put("conclusion", "Only public API routing and response normalization were probed. No real login, private playlist, membership entitlement, audio decoding or playback was verified.");
        Files.writeString(output, report.toString(2), StandardCharsets.UTF_8);
        System.out.println(report.toString(2));
    }
}
