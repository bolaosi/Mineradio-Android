package com.mineradio.android.music;

import org.json.JSONObject;

/** Explicit opt-in, account-free probes. Never print QR keys, Cookies, body text or target URLs. */
public class NeteaseLiveProbe {
    public static void main(String[] args) throws Exception {
        NeteaseClient client = new NeteaseClient();
        boolean ok = true;
        try {
            HttpTransport.Reply keyReply = client.request("/api/login/qrcode/unikey", new JSONObject().put("type", 3), "");
            String key = keyReply.body.optString("unikey", "");
            if (key.isEmpty() && keyReply.body.optJSONObject("data") != null)
                key = keyReply.body.getJSONObject("data").optString("unikey", "");
            boolean hasKey = !key.isEmpty();
            System.out.println("qrKey code=" + keyReply.body.optInt("code") + " keyPresent=" + hasKey);
            ok &= hasKey && keyReply.body.optInt("code") == 200;
            if (hasKey) {
                HttpTransport.Reply check = client.request("/api/login/qrcode/client/login",
                    new JSONObject().put("key", key).put("type", 3), "");
                int code = check.body.optInt("code");
                boolean expected = code == 800 || code == 801 || code == 802;
                System.out.println("qrCheck code=" + code + " waitingOrExpired=" + expected);
                ok &= expected;
            }
        } catch (Exception failure) { System.out.println("qrProbe success=false"); ok = false; }
        try {
            HttpTransport.Reply search = client.request("/api/cloudsearch/pc", new JSONObject()
                .put("s", "夜曲").put("type", 1).put("limit", 1).put("offset", 0).put("total", true), "");
            JSONObject result = search.body.optJSONObject("result");
            boolean songs = result != null && result.optJSONArray("songs") != null && result.getJSONArray("songs").length() > 0;
            System.out.println("search code=" + search.body.optInt("code") + " songsPresent=" + songs);
            ok &= search.body.optInt("code") == 200 && songs;
        } catch (Exception failure) { System.out.println("searchProbe success=false"); ok = false; }
        if (!ok) System.exit(1);
    }
}
