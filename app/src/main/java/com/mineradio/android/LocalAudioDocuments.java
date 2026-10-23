package com.mineradio.android;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Only user-selected SAF documents; never resolves arbitrary paths or uploads audio. */
public final class LocalAudioDocuments {
    private LocalAudioDocuments() {}
    public static JSONObject describe(Context context, Uri uri, int flags) throws Exception {
        if (uri == null || !"content".equals(uri.getScheme())) throw new IllegalArgumentException();
        ContentResolver resolver = context.getContentResolver();
        String name = "本地音频", mime = resolver.getType(uri); long size = -1;
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                if (!cursor.isNull(0)) name = cursor.getString(0);
                if (!cursor.isNull(1)) size = cursor.getLong(1);
            }
        }
        if (size == 0 || !((mime != null && mime.startsWith("audio/")) || name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(mp3|m4a|aac|flac|wav|ogg|opus|webm|mp4|3gp)$")))
            throw new IllegalArgumentException();
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null || input.read() == -1) throw new IllegalArgumentException();
        }
        boolean persisted = false;
        if ((flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
            try { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { /* Some document providers issue session grants only. */ }
        }
        for (UriPermission permission : resolver.getPersistedUriPermissions())
            if (permission.isReadPermission() && uri.equals(permission.getUri())) { persisted = true; break; }
        return new JSONObject().put("id", "local:" + UUID.nameUUIDFromBytes(uri.toString().getBytes(StandardCharsets.UTF_8)))
                .put("provider", "local").put("type", "local").put("name", name).put("artist", "本地文件")
                .put("localUri", uri.toString()).put("persisted", persisted).put("size", size);
    }
    public static JSONArray selected(Context context, Intent data) {
        JSONArray result = new JSONArray();
        if (data == null) return result;
        java.util.LinkedHashSet<Uri> uris = new java.util.LinkedHashSet<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < Math.min(100, data.getClipData().getItemCount()); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        for (Uri uri : uris) {
            try { result.put(describe(context, uri, data.getFlags())); }
            catch (Exception ignored) { /* Fixed aggregate feedback, no path or provider detail logged. */ }
        }
        return result;
    }
}
