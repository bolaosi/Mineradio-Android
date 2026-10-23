package com.mineradio.android.playback;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Whitelisted public metadata; signed URLs and account credentials never enter a queue. */
public final class PlaybackTrack {
    public final String mediaId, provider, id, localUri;
    private final JSONObject metadata;

    private PlaybackTrack(JSONObject input) throws Exception {
        provider = text(input, "provider", 20).toLowerCase(Locale.ROOT);
        id = text(input, "id", 256);
        localUri = text(input, "localUri", 8192);
        if (!(provider.equals("netease") || provider.equals("qq") || provider.equals("local")))
            throw new IllegalArgumentException("不支持的歌曲来源");
        if (provider.equals("local")) {
            URI uri = new URI(localUri);
            if (!"content".equals(uri.getScheme()) || uri.getAuthority() == null || uri.getFragment() != null)
                throw new IllegalArgumentException("请选择系统文件选择器中的音频文件");
        } else {
            if (!localUri.isEmpty()) throw new IllegalArgumentException("在线歌曲不能携带本地文件地址");
            if (id.isEmpty() || provider.equals("netease") && !id.matches("[0-9]{1,20}"))
                throw new IllegalArgumentException("歌曲标识无效");
            if (provider.equals("qq") && !id.matches("[A-Za-z0-9]{1,100}"))
                throw new IllegalArgumentException("QQ 歌曲标识无效");
        }
        metadata = new JSONObject();
        metadata.put("provider", provider); metadata.put("id", id);
        for (String field : new String[]{"name", "artist", "mid", "mediaMid", "cover", "quality"})
            metadata.put(field, text(input, field, field.equals("cover") ? 4096 : 500));
        if (metadata.optString("name").isEmpty()) metadata.put("name", "未命名音频");
        metadata.put("duration", Math.max(0, input.optLong("duration", 0)));
        if (provider.equals("local")) {
            metadata.put("localUri", localUri);
            metadata.put("persisted", input.optBoolean("persisted", false));
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (provider + ":" + (provider.equals("local") ? localUri : id)).getBytes(StandardCharsets.UTF_8));
        char[] hash=new char[digest.length*2],digits="0123456789abcdef".toCharArray();
        for(int i=0;i<digest.length;i++){int value=digest[i]&255;hash[i*2]=digits[value>>>4];hash[i*2+1]=digits[value&15];}
        mediaId = new String(hash);
    }

    public JSONObject json() {
        try { return new JSONObject(metadata.toString()); }
        catch (Exception impossible) { throw new IllegalStateException(); }
    }

    public String field(String name) { return metadata.optString(name, ""); }
    public boolean isLocal() { return provider.equals("local"); }
    public static List<PlaybackTrack> parse(JSONArray array) throws Exception {
        if (array == null || array.length() > 500) throw new IllegalArgumentException("队列最多支持 500 首歌曲");
        List<PlaybackTrack> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) throw new IllegalArgumentException("队列项目无效");
            result.add(new PlaybackTrack(item));
        }
        return result;
    }

    public static String mode(String value) {
        if ("single".equals(value)) return "single";
        if ("random".equals(value)) return "random";
        return "sequence";
    }

    /** Preserve the current track when editing the list; removal selects the nearest survivor. */
    public static int replacementIndex(List<PlaybackTrack> items, String currentId, int oldIndex, int requested, boolean keep) {
        if (items.isEmpty()) return 0;
        if (keep && currentId != null)
            for (int i = 0; i < items.size(); i++) if (items.get(i).mediaId.equals(currentId)) return i;
        return Math.max(0, Math.min(items.size() - 1, keep ? oldIndex : requested));
    }

    private static String text(JSONObject obj, String key, int max) {
        Object value = obj.opt(key);
        String text = value == null || value == JSONObject.NULL ? "" : String.valueOf(value);
        if (text.length() > max || text.indexOf('\u0000') >= 0) throw new IllegalArgumentException("歌曲信息过长或无效");
        return text;
    }
}
