package com.mineradio.android.music;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** App-private API adapter. Upstream failures never expose response cookies or Error details. */
public class MusicApi {
    public interface Store { String get(String key); void put(String key, String value); }
    public static final class ApiResponse {
        public final int status;
        public final JSONObject body;
        public ApiResponse(int status, JSONObject body) { this.status = status; this.body = body; }
    }
    private static final String NE_COOKIE = "netease.cookie", QQ_COOKIE = "qq.cookie";
    private static final String QQ_MUSICU = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private final Store store;
    private final NeteaseClient netease;
    private final HttpTransport http;
    private final Object authLock = new Object();
    private final AtomicLong neteaseEpoch = new AtomicLong(), qqEpoch = new AtomicLong();

    public MusicApi(Store store, NeteaseClient netease, HttpTransport http) {
        this.store = store; this.netease = netease; this.http = http;
    }
    public ApiResponse route(String path, String method, String requestBody) {
        try {
            if (path == null || path.length() > 32768 || !path.startsWith("/api/")) return error(400, "INVALID_PATH", "请求地址无效");
            URI uri = URI.create(path);
            String route = uri.getPath();
            Map<String, String> query = query(uri.getRawQuery());
            String verb = method == null ? "GET" : method;
            if (!"GET".equals(verb) && !"POST".equals(verb)) return error(405, "METHOD_NOT_ALLOWED", "仅支持 GET/POST");
            JSONObject input = requestBody == null || requestBody.trim().isEmpty() ? j() : new JSONObject(requestBody);
            if ((route.endsWith("/login/cookie") || route.endsWith("/logout")) && !"POST".equals(verb)) return error(405, "POST_REQUIRED", "此操作需要 POST");
            switch (route) {
                case "/api/health": return ok(j("ok", true, "service", "mineradio", "apiVersion", 1, "mode", "builtin"));
                case "/api/login/qr/key": {
                    synchronized (authLock) { neteaseEpoch.incrementAndGet(); }
                    JSONObject body = checked(ne("/api/login/qrcode/unikey", j("type", 3), "", false));
                    String key = first(body, "unikey"); if (key.isEmpty()) key = first(obj(body, "data"), "unikey");
                    if (key.isEmpty()) return error(502, "QR_KEY_UNAVAILABLE", "官方接口未返回二维码 key");
                    return ok(j("key", key));
                }
                case "/api/login/qr/create": {
                    String key = qrKey(query); return ok(j("url", "https://music.163.com/login?codekey=" + enc(key), "format", "url"));
                }
                case "/api/login/qr/check": return checkQr(qrKey(query));
                case "/api/login/status": return ok(neteaseStatus(saved(NE_COOKIE)));
                case "/api/login/cookie": return importCookie(false, first(input, "cookie", "data", "text"));
                case "/api/logout": {
                    String prior;
                    synchronized (authLock) { neteaseEpoch.incrementAndGet(); prior = saved(NE_COOKIE); store.put(NE_COOKIE, ""); }
                    if (!prior.isEmpty()) try { ne("/api/logout", j(), prior, false); } catch (Exception ignored) { }
                    return ok(j("ok", true, "loggedIn", false));
                }
                case "/api/user/playlists": return neteasePlaylists(query);
                case "/api/playlist/tracks": return neteaseTracks(query);
                case "/api/search": return neteaseSearch(query);
                case "/api/song/url": return neteaseSongUrl(query);
                case "/api/lyric": return neteaseLyric(query);
                case "/api/song/like/check": return likeCheck(query);
                case "/api/song/like": return like(query, input);
                case "/api/qq/login/status": return ok(qqStatus(saved(QQ_COOKIE)));
                case "/api/qq/login/cookie": return importCookie(true, first(input, "cookie", "data", "text"));
                case "/api/qq/logout": synchronized (authLock) { qqEpoch.incrementAndGet(); store.put(QQ_COOKIE, ""); } return ok(j("provider", "qq", "ok", true, "loggedIn", false));
                case "/api/qq/search": return qqSearch(query);
                case "/api/qq/song/url": return qqSongUrl(query);
                case "/api/qq/user/playlists": return qqPlaylists();
                case "/api/qq/playlist/tracks": return qqTracks(query);
                case "/api/qq/lyric": return qqLyric(query);
                case "/api/discover/home": return discover();
                case "/api/podcast/my": return ok(j("supported", false, "loggedIn", false, "collections", a(), "message", "本版不支持在线播客收藏；音乐歌单仍可使用"));
                default: return error(501, "UNSUPPORTED_ON_ANDROID", "本版不支持此附加功能");
            }
        } catch (IllegalArgumentException error) {
            return error(400, "INVALID_REQUEST", "请求参数无效");
        } catch (Exception error) {
            return error(502, "UPSTREAM_UNAVAILABLE", "官方服务请求或私有凭据存储失败，请稍后重试");
        }
    }

    private ApiResponse checkQr(String key) throws Exception {
        long generation = neteaseEpoch.get();
        HttpTransport.Reply reply = ne("/api/login/qrcode/client/login", j("key", key, "type", 3), "", false);
        int code = reply.body.optInt("code", 0);
        if (code != 803) {
            if (code != 800 && code != 801 && code != 802) return error(502, "QR_STATUS_UNAVAILABLE", "官方接口未返回可识别的二维码状态");
            return ok(j("code", code, "loggedIn", false, "verified", false));
        }
        String cookie = responseCookie(reply);
        if (cookie.isEmpty()) {
            reply = ne("/api/login/qrcode/client/login", j("key", key, "type", 3), "", false);
            cookie = responseCookie(reply);
        }
        JSONObject account = cookie.isEmpty() ? unverified("netease", "", "missing") : neteaseStatus(cookie);
        boolean verified = account.optBoolean("loggedIn") && account.optBoolean("verified");
        if (verified && !commit(NE_COOKIE, cookie, neteaseEpoch, generation)) return cancelled();
        put(account, "code", 803); put(account, "saved", verified); put(account, "hasCookie", !cookie.isEmpty());
        put(account, "message", verified ? "官方账号验证通过" : "扫码已确认，但未取得可验证的账号凭据，请刷新重试");
        return ok(account);
    }
    private ApiResponse importCookie(boolean qq, String text) throws Exception {
        AtomicLong epoch = qq ? qqEpoch : neteaseEpoch; long generation;
        synchronized (authLock) { generation = epoch.incrementAndGet(); }
        String candidate = normalizeCookie(text);
        Map<String, String> parts = cookies(candidate);
        if (qq) {
            if (!positiveId(qqUin(parts)) || qqKey(parts).isEmpty()) return error(400, "INVALID_QQ_COOKIE", "QQ Cookie 缺少 uin 或音乐登录票据");
        } else if (!parts.containsKey("MUSIC_U")) return error(400, "INVALID_NETEASE_COOKIE", "网易云 Cookie 缺少 MUSIC_U");
        JSONObject account = qq ? qqStatus(candidate) : neteaseStatus(candidate);
        boolean verified = account.optBoolean("loggedIn") && account.optBoolean("verified");
        put(account, "saved", verified);
        if (!verified) { put(account, "message", "凭据未通过官方账号验证，未替换已有会话"); return new ApiResponse(422, account); }
        if (!commit(qq ? QQ_COOKIE : NE_COOKIE, candidate, epoch, generation)) return cancelled();
        return ok(account);
    }
    private JSONObject neteaseStatus(String cookie) {
        if (cookie.isEmpty()) return unverified("netease", cookie, "missing");
        boolean invalid = false;
        for (String path : new String[]{"/api/w/nuser/account/get", "/api/nuser/account/get"}) {
            if (Thread.currentThread().isInterrupted()) break;
            try {
                JSONObject raw = ne(path, j(), cookie, true).body;
                JSONObject data = raw.optJSONObject("data") == null ? raw : obj(raw, "data");
                int code = data.optInt("code", raw.optInt("code", 0));
                if (code == 301 || code == 401) invalid = true;
                JSONObject profile = obj(data, "profile"), account = obj(data, "account");
                String id = first(profile, "userId", "user_id", "id"); if (id.isEmpty()) id = first(account, "userId", "id");
                if (code == 200 && positiveId(id)) {
                    int vip = profile.optInt("vipType", account.optInt("vipType", 0)); boolean svip = vip >= 10;
                    return j("provider", "netease", "loggedIn", true, "verified", true, "credentialState", "verified", "hasCookie", true,
                            "userId", id, "nickname", first(profile, "nickname", "userName"), "avatar", first(profile, "avatarUrl", "avatar"),
                            "vipType", vip, "vipLevel", svip ? "svip" : vip > 0 ? "vip" : "none", "isVip", vip > 0, "isSvip", svip, "vipLabel", svip ? "SVIP" : vip > 0 ? "VIP" : "无VIP");
                }
            } catch (Exception ignored) { }
        }
        return unverified("netease", cookie, invalid ? "invalid" : "unverified");
    }
    private JSONObject qqStatus(String cookie) {
        Map<String, String> parts = cookies(cookie); String uin = qqUin(parts), key = qqKey(parts);
        if (!positiveId(uin) || key.isEmpty()) return unverified("qq", cookie, cookie.isEmpty() ? "missing" : "invalid");
        try {
            JSONObject body = qq(j("comm", j("uin", uin, "format", "json", "ct", 24, "cv", 0),
                    "nick", block("music.UserInfo.userInfoServer", "GetLoginUserInfo", j())), cookie);
            JSONObject result = obj(body, "nick"), profile = obj(obj(result, "data"), "info");
            String returned = first(profile, "uin", "musicid", "musicId");
            if (body.optInt("code", -1) != 0 || result.optInt("code", -1) != 0 || first(profile, "nick").isEmpty() || (!returned.isEmpty() && !digits(returned).equals(uin)))
                return unverified("qq", cookie, "invalid");
            return j("provider", "qq", "loggedIn", true, "verified", true, "credentialState", "verified", "hasCookie", true, "userId", uin,
                    "nickname", first(profile, "nick"), "avatar", first(profile, "headpic", "avatar", "avatarUrl"), "vipType", 0, "vipLevel", "none", "isVip", false,
                    "playbackKeyReady", !first(parts, "qm_keyst", "qqmusic_key", "music_key", "wxskey").isEmpty(), "profileSource", "authenticated-user");
        } catch (Exception ignored) { return unverified("qq", cookie, "unverified"); }
    }

    private ApiResponse neteasePlaylists(Map<String, String> q) throws Exception {
        JSONObject account = neteaseStatus(saved(NE_COOKIE));
        if (!account.optBoolean("verified")) return new ApiResponse(401, j("loggedIn", false, "playlists", a(), "error", "LOGIN_REQUIRED"));
        JSONObject raw = checked(ne("/api/user/playlist", j("uid", first(account, "userId"), "limit", limit(q, 60, 100), "offset", 0, "includeVideo", true), saved(NE_COOKIE), true));
        JSONArray list = a(); JSONArray incoming = arr(raw, "playlist");
        for (int i = 0; i < incoming.length(); i++) list.put(mapPlaylist(incoming.optJSONObject(i), false, false));
        return ok(j("loggedIn", true, "userId", first(account, "userId"), "playlists", list));
    }
    private ApiResponse neteaseTracks(Map<String, String> q) throws Exception {
        String id = numeric(q.get("id"));
        JSONObject body = checked(ne("/api/v6/playlist/detail", j("id", id, "n", 100000, "s", 0), saved(NE_COOKIE), false));
        JSONObject playlist = obj(body, "playlist"); JSONArray tracks = arr(playlist, "tracks"), ids = arr(playlist, "trackIds");
        if (ids.length() > tracks.length()) {
            JSONArray requested = a();
            for (int i = 0; i < Math.min(ids.length(), 500); i++) requested.put(j("id", objAt(ids, i).opt("id")));
            if (requested.length() > 0) tracks = arr(checked(ne("/api/v3/song/detail", j("c", requested.toString()), saved(NE_COOKIE), false)), "songs");
        }
        JSONArray mapped = mapSongs(tracks, false);
        int total = Math.max(ids.length(), playlist.optInt("trackCount", mapped.length()));
        boolean partial = mapped.length() < total;
        return ok(j("playlist", mapPlaylist(playlist, false, false), "tracks", mapped, "partial", partial, "total", total, "loaded", mapped.length(),
                "message", partial ? "本次仅载入 " + mapped.length() + "/" + total + " 首；超过500首或官方未返回的歌曲未全部载入" : ""));
    }
    private ApiResponse neteaseSearch(Map<String, String> q) throws Exception {
        String keyword = bounded(q.get("keywords"), 200); if (keyword.isEmpty()) return ok(j("songs", a()));
        JSONObject raw = checked(ne("/api/cloudsearch/pc", j("s", keyword, "type", 1, "limit", limit(q, 18, 60), "offset", 0, "total", true), saved(NE_COOKIE), false));
        return ok(j("songs", mapSongs(arr(obj(raw, "result"), "songs"), false)));
    }
    private ApiResponse neteaseSongUrl(Map<String, String> q) throws Exception {
        String id = numeric(q.get("id")); String cookie = saved(NE_COOKIE); String quality = quality(q.get("quality"));
        String[] levels = {"hires", "lossless", "exhigh", "standard"}; int start = quality.equals("standard") ? 3 : quality.equals("exhigh") ? 2 : quality.equals("lossless") ? 1 : 0;
        JSONObject trial = null;
        for (int i = start; i < levels.length; i++) {
            try {
                JSONObject raw = ne("/api/song/enhance/player/url/v1", j("ids", "[" + id + "]", "level", levels[i], "encodeType", "flac"), cookie, false).body;
                if (raw.optInt("code", 0) != 200) raw = checked(ne("/api/song/enhance/player/url", j("ids", "[\"" + id + "\"]", "br", i == 3 ? 128000 : i == 2 ? 999000 : i == 1 ? 1411000 : 1999000), cookie, false));
                JSONObject data = objAt(arr(raw, "data"), 0); String url = first(data, "url");
                if (url.isEmpty()) continue;
                boolean preview = data.opt("freeTrialInfo") instanceof JSONObject;
                JSONObject out = j("url", url, "trial", preview, "playable", true, "level", levels[i], "quality", levels[i], "br", data.optLong("br", 0), "requestedQuality", quality);
                if (!preview) return ok(out);
                put(out, "trialInfo", data.opt("freeTrialInfo")); put(out, "restriction", restriction("netease", "trial_only", "官方仅返回试听片段，完整播放需相应账号或版权权限")); if (trial == null) trial = out;
            } catch (Exception ignored) { return error(502, "SONG_URL_UNAVAILABLE", "播放地址请求失败，请检查网络后重试"); }
        }
        if (trial != null) return ok(trial);
        return ok(j("url", "", "trial", false, "playable", false, "requestedQuality", quality, "restriction", restriction("netease", "url_unavailable", "官方未返回播放地址，可能受账号、版权、会员或地区限制"), "message", "当前歌曲不可播放"));
    }
    private ApiResponse neteaseLyric(Map<String, String> q) throws Exception {
        String id = numeric(q.get("id")); JSONObject raw;
        try { raw = checked(ne("/api/song/lyric/v1", j("id", id, "cp", false, "tv", 0, "lv", 0, "rv", 0, "kv", 0, "yv", 0, "ytv", 0, "yrv", 0), saved(NE_COOKIE), false)); }
        catch (Exception ignored) { raw = checked(ne("/api/song/lyric", j("id", id, "tv", -1, "lv", -1, "rv", -1, "kv", -1, "_nmclfl", 1), saved(NE_COOKIE), false)); }
        return ok(j("lyric", first(obj(raw, "lrc"), "lyric"), "tlyric", first(obj(raw, "tlyric"), "lyric"), "yrc", first(obj(raw, "yrc"), "lyric"), "roma", first(obj(raw, "romalrc"), "lyric")));
    }
    private ApiResponse likeCheck(Map<String, String> q) throws Exception {
        JSONObject account = neteaseStatus(saved(NE_COOKIE)); if (!account.optBoolean("verified")) return error(401, "LOGIN_REQUIRED", "请先验证网易云账号");
        String input = q.containsKey("ids") ? q.get("ids") : q.get("id"); String[] ids = bounded(input, 10000).split(","); JSONArray requested = a();
        for (String id : ids) if (!id.trim().isEmpty()) requested.put(numeric(id.trim()));
        if (requested.length() == 0 || requested.length() > 500) throw new IllegalArgumentException();
        JSONObject raw = checked(ne("/api/song/like/get", j("uid", first(account, "userId")), saved(NE_COOKIE), false));
        Set<String> liked = new HashSet<>(); JSONArray likedIds = arr(raw, "ids"); for (int i = 0; i < likedIds.length(); i++) liked.add(String.valueOf(likedIds.opt(i)));
        JSONObject matches = j(); for (int i = 0; i < requested.length(); i++) { String id = requested.optString(i); put(matches, id, liked.contains(id)); }
        return ok(j("loggedIn", true, "ids", requested, "liked", matches));
    }
    private ApiResponse like(Map<String, String> q, JSONObject input) throws Exception {
        if (!neteaseStatus(saved(NE_COOKIE)).optBoolean("verified")) return error(401, "LOGIN_REQUIRED", "请先验证网易云账号");
        String id = numeric(first(input, "id").isEmpty() ? q.get("id") : first(input, "id")); String rawLike = first(input, "like"); if (rawLike.isEmpty()) rawLike = q.get("like");
        boolean liked = !"false".equals(rawLike);
        checked(ne("/api/radio/like", j("alg", "itembased", "trackId", id, "like", liked, "time", "3"), saved(NE_COOKIE), true));
        return ok(j("loggedIn", true, "id", id, "liked", liked, "code", 200));
    }

    private ApiResponse qqSearch(Map<String, String> q) throws Exception {
        String keyword = bounded(q.get("keywords"), 200); if (keyword.isEmpty()) return ok(j("provider", "qq", "songs", a()));
        JSONObject raw = qqGet("https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg", j("format", "json", "key", keyword, "g_tk", "5381", "loginUin", "0", "hostUin", "0", "inCharset", "utf8", "outCharset", "utf-8", "notice", "0", "platform", "yqq.json", "needNewCode", "0"), "");
        if (raw.optInt("code", -1) != 0) throw new IllegalStateException();
        JSONArray rawSongs = arr(obj(obj(raw, "data"), "song"), "itemlist"), songs = a();
        for (int i = 0; i < Math.min(rawSongs.length(), limit(q, 6, 10)); i++) {
            JSONObject item = objAt(rawSongs, i); String mid = first(item, "mid", "songmid", "id"); JSONObject song;
            try {
                JSONObject detailed = qq(j("comm", j("ct", 24, "cv", 0), "songinfo", block("music.pf_song_detail_svr", "get_song_detail_yqq", j("song_mid", mid))), "");
                song = mapSong(obj(obj(qqBlock(detailed, "songinfo"), "data"), "track_info"), true);
                if (first(song, "name").isEmpty()) throw new IllegalStateException();
            } catch (Exception ignored) { song = j("provider", "qq", "source", "qq", "type", "qq", "id", mid, "mid", mid, "songmid", mid, "qqId", item.opt("id"), "name", first(item, "name", "title"), "artist", first(item, "singer"), "artists", a(), "cover", "", "duration", 0); }
            songs.put(song);
        }
        return ok(j("provider", "qq", "songs", songs));
    }
    private ApiResponse qqSongUrl(Map<String, String> q) throws Exception {
        String mid = bounded(q.get("mid"), 100); if (mid.isEmpty()) throw new IllegalArgumentException();
        String media = bounded(q.get("mediaMid"), 100); String requested = quality(q.get("quality"));
        String[] prefixes = {"RS01", "F000", "M800", "M500", "C400"}, extensions = {".flac", ".flac", ".mp3", ".mp3", ".m4a"}, levels = {"hires", "lossless", "exhigh", "standard", "aac"};
        int start = requested.equals("standard") ? 3 : requested.equals("exhigh") ? 2 : requested.equals("lossless") ? 1 : 0;
        JSONArray files = a(), mids = a(), types = a();
        for (String candidate : media.isEmpty() || media.equals(mid) ? new String[]{mid} : new String[]{media, mid}) for (int i = start; i < prefixes.length; i++) { files.put(prefixes[i] + candidate + extensions[i]); mids.put(mid); types.put(0); }
        String cookie = saved(QQ_COOKIE); Map<String, String> parts = cookies(cookie); String uin = qqUin(parts), key = qqKey(parts); if (uin.isEmpty()) uin = "0";
        JSONObject comm = j("uin", uin, "format", "json", "ct", key.isEmpty() ? 24 : 19, "cv", 0); if (!key.isEmpty()) put(comm, "authst", key);
        JSONObject raw = qq(j("comm", comm, "req_0", block("vkey.GetVkeyServer", "CgiGetVkey", j("guid", String.valueOf(System.nanoTime() & 0x7fffffff), "songmid", mids, "songtype", types, "uin", uin, "loginflag", 1, "platform", "20", "filename", files))), cookie);
        JSONObject data = obj(qqBlock(raw, "req_0"), "data"); JSONArray infos = arr(data, "midurlinfo");
        for (int i = 0; i < infos.length(); i++) {
            JSONObject info = objAt(infos, i); String purl = first(info, "purl"); if (purl.isEmpty()) continue;
            String sip = arr(data, "sip").optString(0, "https://ws.stream.qqmusic.qq.com/"); String file = first(info, "filename"), actual = "";
            for (int n = 0; n < prefixes.length; n++) if (file.startsWith(prefixes[n])) actual = levels[n];
            return ok(j("provider", "qq", "url", sip + purl, "playable", true, "trial", false, "level", actual, "quality", actual, "requestedQuality", requested));
        }
        return ok(j("provider", "qq", "url", "", "playable", false, "error", "QQ_URL_UNAVAILABLE", "restriction", restriction("qq", "url_unavailable", "QQ 官方未返回播放地址，可能受登录、版权或会员权限限制"), "message", "当前 QQ 歌曲不可播放"));
    }
    private ApiResponse qqPlaylists() throws Exception {
        String cookie = saved(QQ_COOKIE); JSONObject account = qqStatus(cookie); if (!account.optBoolean("verified")) return new ApiResponse(401, j("provider", "qq", "loggedIn", false, "playlists", a(), "error", "LOGIN_REQUIRED"));
        String uin = first(account, "userId"); JSONArray out = a(); int successes = 0; Set<String> seen = new HashSet<>();
        try {
            JSONObject raw = qqGet("https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss", j("hostUin", 0, "hostuin", uin, "sin", 0, "size", 200, "g_tk", 5381, "loginUin", uin, "format", "json", "inCharset", "utf8", "outCharset", "utf-8", "notice", 0, "platform", "yqq.json", "needNewCode", 0), cookie);
            if (raw.optInt("code", -1) == 0) { appendPlaylists(out, arr(obj(raw, "data"), "disslist"), false, seen); successes++; }
        } catch (Exception ignored) { }
        try {
            JSONObject raw = qqGet("https://c.y.qq.com/fav/fcgi-bin/fcg_get_profile_order_asset.fcg", j("ct", 20, "cid", 205360956, "userid", uin, "reqtype", 3, "sin", 0, "ein", 80, "format", "json"), cookie);
            if (raw.optInt("code", -1) == 0) { appendPlaylists(out, arr(obj(raw, "data"), "cdlist"), true, seen); successes++; }
        } catch (Exception ignored) { }
        if (successes == 0) return error(502, "QQ_PLAYLISTS_UNAVAILABLE", "QQ 歌单查询未通过，不能确认歌单为空");
        return ok(j("provider", "qq", "loggedIn", true, "userId", uin, "playlists", out, "partial", successes < 2));
    }
    private ApiResponse qqTracks(Map<String, String> q) throws Exception {
        String cookie = saved(QQ_COOKIE); JSONObject account = qqStatus(cookie); if (!account.optBoolean("verified")) return new ApiResponse(401, j("loggedIn", false, "tracks", a(), "error", "LOGIN_REQUIRED"));
        String id = numeric(q.get("id")); JSONObject raw = qqGet("https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg", j("type", 1, "utf8", 1, "disstid", id, "loginUin", first(account, "userId"), "format", "json", "inCharset", "utf8", "outCharset", "utf-8", "notice", 0, "platform", "yqq.json", "needNewCode", 0), cookie);
        if (raw.optInt("code", -1) != 0) throw new IllegalStateException(); JSONObject playlist = objAt(arr(raw, "cdlist"), 0);
        if (playlist.length() == 0) return error(502, "QQ_PLAYLIST_UNAVAILABLE", "QQ 官方未返回歌单详情");
        JSONObject mapped = mapPlaylist(playlist, true, false); put(mapped, "id", id);
        return ok(j("provider", "qq", "loggedIn", true, "playlist", mapped, "tracks", mapSongs(arr(playlist, "songlist"), true)));
    }
    private ApiResponse qqLyric(Map<String, String> q) throws Exception {
        String mid = bounded(q.get("mid"), 100), id = digits(q.get("id")); JSONObject param = j(); if (!mid.isEmpty()) put(param, "songMID", mid); if (!id.isEmpty()) put(param, "songID", Long.parseLong(id));
        if (param.length() == 0) throw new IllegalArgumentException(); JSONObject data = j();
        try { JSONObject raw = qq(j("comm", j("ct", 24, "cv", 0), "lyric", block("music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo", param)), saved(QQ_COOKIE)); data = obj(qqBlock(raw, "lyric"), "data"); } catch (Exception ignored) { }
        String lyric = decodeLyric(first(data, "lyric")), translated = decodeLyric(first(data, "trans"));
        if (lyric.isEmpty() && !mid.isEmpty()) {
            JSONObject raw = qqGet("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg", j("songmid", mid, "songtype", 0, "format", "json", "nobase64", 1, "g_tk", 5381, "loginUin", qqUin(cookies(saved(QQ_COOKIE))), "hostUin", 0, "inCharset", "utf8", "outCharset", "utf-8", "notice", 0, "platform", "yqq.json", "needNewCode", 0), saved(QQ_COOKIE));
            if (raw.optInt("code", -1) != 0) throw new IllegalStateException();
            lyric = decodeLyric(first(raw, "lyric")); translated = decodeLyric(first(raw, "trans", "tlyric"));
        }
        return ok(j("provider", "qq", "id", id, "mid", mid, "lyric", lyric, "tlyric", translated, "yrc", "", "qrc", decodeLyric(first(data, "qrc")), "roma", decodeLyric(first(data, "roma"))));
    }
    private ApiResponse discover() throws Exception {
        JSONObject account = neteaseStatus(saved(NE_COOKIE)); boolean loggedIn = account.optBoolean("verified"); JSONArray songs = a(), playlists = a(); boolean partial = false;
        try {
            JSONObject raw = checked(ne("/api/personalized/playlist", j("limit", 8, "total", true, "n", 1000), saved(NE_COOKIE), true));
            JSONArray records = arr(raw, "result"); for (int i = 0; i < records.length(); i++) playlists.put(mapPlaylist(objAt(records, i), false, false));
        } catch (Exception ignored) { partial = true; }
        if (loggedIn) try {
            JSONObject raw = checked(ne("/api/v3/discovery/recommend/songs", j(), saved(NE_COOKIE), true)); songs = mapSongs(arr(obj(raw, "data"), "dailySongs"), false);
        } catch (Exception ignored) { partial = true; }
        return ok(j("loggedIn", loggedIn, "user", loggedIn ? account : JSONObject.NULL, "dailySongs", songs, "playlists", playlists, "podcasts", a(), "supported", true, "partial", partial, "message", partial ? "部分官方推荐暂不可用，仍可搜索或导入本地音乐" : ""));
    }

    private HttpTransport.Reply ne(String path, JSONObject data, String cookie, boolean weapi) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("CANCELLED");
        HttpTransport.Reply reply = weapi ? netease.requestWeapi(path, data, cookie) : netease.request(path, data, cookie);
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("CANCELLED");
        if (reply.status < 200 || reply.status >= 300 || reply.body == null) throw new IllegalStateException(); return reply;
    }
    private static JSONObject checked(HttpTransport.Reply reply) { if (reply.body.optInt("code", 0) != 200) throw new IllegalStateException(); return reply.body; }
    private JSONObject qq(JSONObject data, String cookie) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("CANCELLED");
        HttpTransport.Reply reply = http.post(QQ_MUSICU, data, qqHeaders(cookie)); if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("CANCELLED"); if (reply.status < 200 || reply.status >= 300 || reply.body == null) throw new IllegalStateException(); return reply.body;
    }
    private JSONObject qqGet(String url, JSONObject params, String cookie) throws Exception {
        StringBuilder result = new StringBuilder(url).append('?'); JSONArray keys = params.names();
        if (keys != null) for (int i = 0; i < keys.length(); i++) { String key = keys.optString(i); result.append(enc(key)).append('=').append(enc(String.valueOf(params.opt(key)))).append('&'); }
        HttpTransport.Reply reply = http.get(result.toString(), qqHeaders(cookie)); if (reply.status < 200 || reply.status >= 300 || reply.body == null) throw new IllegalStateException(); return reply.body;
    }
    private static Map<String, String> qqHeaders(String cookie) {
        Map<String, String> headers = new LinkedHashMap<>(); headers.put("Referer", "https://y.qq.com/"); headers.put("User-Agent", "Mozilla/5.0"); if (!cookie.isEmpty()) headers.put("Cookie", cookie); return headers;
    }
    private static JSONObject block(String module, String method, JSONObject param) { return j("module", module, "method", method, "param", param); }
    private static JSONObject qqBlock(JSONObject body, String key) { JSONObject block = obj(body, key); if (body.optInt("code", -1) != 0 || block.optInt("code", -1) != 0) throw new IllegalStateException(); return block; }
    private String saved(String key) { String value = store.get(key); return value == null ? "" : value; }
    private boolean commit(String key, String cookie, AtomicLong epoch, long generation) {
        synchronized (authLock) {
            if (Thread.currentThread().isInterrupted() || epoch.get() != generation) return false;
            store.put(key, cookie); return true;
        }
    }
    private static ApiResponse cancelled() { return error(409, "LOGIN_CANCELLED", "此登录请求已取消或被更新的操作替代"); }
    private static JSONObject unverified(String provider, String cookie, String state) { return j("provider", provider, "loggedIn", false, "verified", false, "hasCookie", !cookie.isEmpty(), "credentialState", state, "vipType", 0, "vipLevel", "none", "isVip", false, "isSvip", false); }
    private static ApiResponse ok(JSONObject data) { return new ApiResponse(200, data); }
    private static ApiResponse error(int status, String code, String message) { return new ApiResponse(status, j("error", code, "message", message, "loggedIn", false, "verified", false, "saved", false)); }
    private static JSONObject restriction(String provider, String category, String message) { return j("provider", provider, "category", category, "message", message, "action", "switch_source"); }
    private static JSONObject j(Object... values) { JSONObject object = new JSONObject(); for (int i = 0; i < values.length; i += 2) put(object, String.valueOf(values[i]), values[i + 1]); return object; }
    private static void put(JSONObject object, String key, Object value) { try { object.put(key, value == null ? JSONObject.NULL : value); } catch (Exception ignored) { throw new IllegalArgumentException(); } }
    private static JSONArray a() { return new JSONArray(); }
    private static JSONObject obj(JSONObject source, String key) { JSONObject result = source == null ? null : source.optJSONObject(key); return result == null ? j() : result; }
    private static JSONArray arr(JSONObject source, String key) { JSONArray result = source == null ? null : source.optJSONArray(key); return result == null ? a() : result; }
    private static JSONObject objAt(JSONArray array, int index) { JSONObject result = array.optJSONObject(index); return result == null ? j() : result; }
    private static String first(JSONObject object, String... keys) { if (object != null) for (String key : keys) { Object value = object.opt(key); if (value != null && value != JSONObject.NULL && !String.valueOf(value).isEmpty()) return String.valueOf(value); } return ""; }
    private static String first(Map<String, String> values, String... keys) { for (String key : keys) if (values.containsKey(key) && !values.get(key).isEmpty()) return values.get(key); return ""; }
    private static String enc(String value) { try { return URLEncoder.encode(value, "UTF-8"); } catch (Exception ignored) { throw new IllegalArgumentException(); } }
    private static Map<String, String> query(String raw) { Map<String, String> map = new LinkedHashMap<>(); if (raw == null) return map; for (String part : raw.split("&")) { String[] pair = part.split("=", 2); try { map.put(URLDecoder.decode(pair[0], "UTF-8"), pair.length > 1 ? URLDecoder.decode(pair[1], "UTF-8") : ""); } catch (Exception ignored) { throw new IllegalArgumentException(); } } return map; }
    private static String bounded(String value, int max) { if (value == null) return ""; if (value.length() > max || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw new IllegalArgumentException(); return value.trim(); }
    private static String numeric(String value) { if (value == null || !value.matches("[0-9]{1,20}")) throw new IllegalArgumentException(); return value; }
    private static String qrKey(Map<String, String> query) { String key = bounded(query.get("key"), 512); if (key.isEmpty()) throw new IllegalArgumentException(); return key; }
    private static boolean positiveId(String value) { return value != null && value.matches("[0-9]{1,20}") && !value.matches("0+"); }
    private static String digits(String value) { return value == null ? "" : value.replaceAll("[^0-9]", "").replaceFirst("^0+(?!$)", ""); }
    private static int limit(Map<String, String> query, int fallback, int max) { try { return Math.max(1, Math.min(max, Integer.parseInt(query.get("limit")))); } catch (Exception ignored) { return fallback; } }
    private static String quality(String value) { if ("standard".equals(value) || "exhigh".equals(value) || "lossless".equals(value)) return value; return "hires"; }
    private static Map<String, String> cookies(String input) { Map<String, String> out = new LinkedHashMap<>(); if (input == null) return out; for (String part : input.split(";")) { int eq = part.indexOf('='); if (eq > 0) out.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim()); } return out; }
    private static String normalizeCookie(String input) {
        if (input == null || input.length() > 32768) throw new IllegalArgumentException(); StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> pair : cookies(input).entrySet()) {
            if (pair.getKey().matches("(?i)path|domain|expires|max-age|samesite|secure|httponly") || pair.getValue().isEmpty()) continue;
            if (!pair.getKey().matches("[A-Za-z0-9_.$-]+") || pair.getValue().indexOf('\r') >= 0 || pair.getValue().indexOf('\n') >= 0) throw new IllegalArgumentException();
            if (out.length() > 0) out.append("; "); out.append(pair.getKey()).append('=').append(pair.getValue());
        }
        return out.toString();
    }
    private static String responseCookie(HttpTransport.Reply reply) {
        StringBuilder text = new StringBuilder(); if (reply.cookies != null) for (String cookie : reply.cookies) text.append(cookie).append(';');
        if (text.length() == 0) text.append(first(reply.body, "cookie")); return normalizeCookie(text.toString());
    }
    private static String qqUin(Map<String, String> parts) { return digits("2".equals(parts.get("login_type")) ? first(parts, "wxuin", "uin", "p_uin") : first(parts, "uin", "qqmusic_uin", "wxuin", "p_uin")); }
    private static String qqKey(Map<String, String> parts) { return first(parts, "qm_keyst", "qqmusic_key", "music_key", "p_skey", "skey", "psrf_qqaccess_token", "psrf_qqrefresh_token", "wxrefresh_token", "wxskey"); }
    private static JSONArray mapSongs(JSONArray raw, boolean qq) { JSONArray mapped = a(); for (int i = 0; i < raw.length(); i++) { JSONObject song = mapSong(objAt(raw, i), qq); if (!first(song, "id").isEmpty() && !first(song, "name").isEmpty()) mapped.put(song); } return mapped; }
    private static JSONObject mapSong(JSONObject raw, boolean qq) {
        if (raw == null) raw = j();
        if (qq) for (String key : new String[]{"track_info", "songInfo", "songinfo", "song"}) if (raw.optJSONObject(key) != null) { raw = obj(raw, key); break; }
        JSONArray source = qq ? arr(raw, "singer") : raw.optJSONArray("ar") != null ? arr(raw, "ar") : arr(raw, "artists"), artists = a(); StringBuilder names = new StringBuilder();
        for (int i = 0; i < source.length(); i++) { JSONObject artist = objAt(source, i); String name = first(artist, "name", "title"); if (name.isEmpty()) continue; artists.put(j("id", artist.opt("id"), "mid", first(artist, "mid"), "name", name)); if (names.length() > 0) names.append(" / "); names.append(name); }
        JSONObject album = qq ? obj(raw, "album") : raw.optJSONObject("al") != null ? obj(raw, "al") : obj(raw, "album");
        String mid = first(raw, "mid", "songmid"), albumMid = first(album, "mid", "pmid"); if (albumMid.isEmpty()) albumMid = first(raw, "albummid");
        String id = qq ? (mid.isEmpty() ? first(raw, "id", "songid") : mid) : first(raw, "id");
        String cover = qq ? albumMid.isEmpty() ? "" : "https://y.qq.com/music/photo_new/T002R300x300M000" + albumMid + ".jpg?max_age=2592000" : first(album, "picUrl", "coverUrl");
        JSONObject song = j("provider", qq ? "qq" : "netease", "source", qq ? "qq" : "netease", "type", qq ? "qq" : "song", "id", id, "name", first(raw, "name", "title", "songname"), "artists", artists,
                "artist", names.length() > 0 ? names.toString() : first(raw, "singername"), "artistId", objAt(artists, 0).opt("id"), "album", first(album, "name", "title"), "cover", cover,
                "duration", qq ? raw.optLong("interval", 0) * 1000 : raw.optLong("dt", raw.optLong("duration", 0)), "fee", qq ? obj(raw, "pay").optInt("pay_play", 0) : raw.optInt("fee", 0));
        if (qq) { put(song, "mid", mid); put(song, "songmid", mid); put(song, "qqId", first(raw, "id", "songid")); put(song, "albumMid", albumMid); put(song, "artistMid", first(objAt(artists, 0), "mid")); put(song, "mediaMid", first(obj(raw, "file"), "media_mid").isEmpty() ? first(raw, "strMediaMid", "media_mid") : first(obj(raw, "file"), "media_mid")); }
        return song;
    }
    private static JSONObject mapPlaylist(JSONObject raw, boolean qq, boolean collected) {
        if (raw == null) raw = j(); return j("provider", qq ? "qq" : "netease", "source", qq ? "qq" : "netease", "id", qq ? first(raw, "dissid", "tid", "dirid", "id", "diss_id") : first(raw, "id"),
                "name", qq ? first(raw, "diss_name", "dissname", "name", "title") : first(raw, "name"), "cover", qq ? first(raw, "diss_cover", "logo", "picurl", "cover") : first(raw, "coverImgUrl", "picUrl"),
                "trackCount", qq ? raw.optLong("song_cnt", raw.optLong("songnum", raw.optLong("total_song_num", raw.optLong("song_count", 0)))) : raw.optLong("trackCount", 0),
                "playCount", qq ? raw.optLong("listen_num", raw.optLong("visitnum", 0)) : raw.optLong("playCount", 0), "creator", qq ? first(raw, "hostname", "nick") : first(obj(raw, "creator"), "nickname"),
                "subscribed", qq ? collected : raw.optBoolean("subscribed", false), "specialType", raw.optInt("specialType", 0));
    }
    private static void appendPlaylists(JSONArray result, JSONArray source, boolean collected, Set<String> seen) { for (int i = 0; i < source.length(); i++) { JSONObject pl = mapPlaylist(objAt(source, i), true, collected); String id = first(pl, "id"); if (!id.isEmpty() && seen.add(id)) result.put(pl); } }
    private static String decodeLyric(String text) {
        if (text.matches("[A-Za-z0-9+/]+={0,2}") && text.length() >= 8 && text.length() % 4 == 0) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"; int bits = 0, value = 0;
            for (int i = 0; i < text.length() && text.charAt(i) != '='; i++) { value = (value << 6) | alphabet.indexOf(text.charAt(i)); bits += 6; if (bits >= 8) { bits -= 8; bytes.write((value >> bits) & 255); } }
            text = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        return text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("\r\n", "\n");
    }
}
