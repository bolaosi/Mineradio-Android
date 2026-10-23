package com.mineradio.android.music;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** One-use private process handoff. Never passed through an Intent, JS bridge or log. */
public final class QqLoginHandoff {
    public static final long TTL_MS = 5 * 60 * 1000;
    private static final int MAX_BYTES = 65536;
    private QqLoginHandoff() {}
    private static File file(File directory, String id, String suffix) throws IOException {
        if (!QqLoginPolicy.validRequestId(id)) throw new IOException("INVALID_QQ_REQUEST");
        File target = new File(directory, id + suffix);
        if (!directory.getCanonicalFile().equals(target.getCanonicalFile().getParentFile())) throw new IOException("INVALID_QQ_HANDOFF_PATH");
        return target;
    }
    public static void write(File directory, String id, String cookie, JSONObject account, long now) throws Exception {
        if (cookie == null || !QqLoginPolicy.hasCandidate(cookie) || !account.optBoolean("verified") || !account.optBoolean("loggedIn") || account.optString("userId").isEmpty()) throw new IOException("UNVERIFIED_QQ_HANDOFF");
        byte[] value = new JSONObject().put("createdAt", now).put("cookie", cookie).put("account", account).toString().getBytes(StandardCharsets.UTF_8);
        if (value.length > MAX_BYTES) throw new IOException("QQ_HANDOFF_TOO_LARGE");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("QQ_HANDOFF_STORAGE_FAILED");
        cleanExpired(directory, now);
        File target = file(directory, id, ".json"), temporary = file(directory, id, ".tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary)) { stream.write(value); stream.getFD().sync(); }
        if (!temporary.renameTo(target)) { temporary.delete(); throw new IOException("QQ_HANDOFF_STORAGE_FAILED"); }
    }
    public static String take(File directory, String id, long now) throws Exception {
        File target = file(directory, id, ".json"), claimed = file(directory, id, ".claim");
        if (!target.exists() || target.length() < 1 || target.length() > MAX_BYTES || !target.renameTo(claimed)) { target.delete(); return ""; }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (FileInputStream input = new FileInputStream(claimed)) {
                byte[] buffer = new byte[4096]; int read;
                while ((read = input.read(buffer)) != -1) { if (bytes.size() + read > MAX_BYTES) throw new IOException("QQ_HANDOFF_TOO_LARGE"); bytes.write(buffer, 0, read); }
            }
            JSONObject body = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            long created = body.optLong("createdAt", 0); JSONObject account = body.optJSONObject("account");
            if (created <= 0 || created > now + 30000 || now - created > TTL_MS || account == null || !account.optBoolean("verified") || !account.optBoolean("loggedIn")) return "";
            String cookie = body.optString("cookie", "");
            return cookie.length() <= 32768 && QqLoginPolicy.hasCandidate(cookie) ? cookie : "";
        } finally { claimed.delete(); }
    }
    public static void discard(File directory, String id) throws IOException { for (String suffix : new String[]{".json", ".tmp", ".claim"}) file(directory, id, suffix).delete(); }
    public static void cleanExpired(File directory, long now) throws IOException {
        File[] entries = directory.listFiles(); if (entries == null) return;
        for (File candidate : entries) {
            String name = candidate.getName(); int dot = name.lastIndexOf('.'); if (dot < 0 || !QqLoginPolicy.validRequestId(name.substring(0, dot))) continue;
            String suffix = name.substring(dot); if (!suffix.equals(".json") && !suffix.equals(".tmp") && !suffix.equals(".claim")) continue;
            File safe = file(directory, name.substring(0, dot), suffix);
            if (safe.lastModified() <= now - TTL_MS || safe.lastModified() > now + 30000) safe.delete();
        }
    }
}
