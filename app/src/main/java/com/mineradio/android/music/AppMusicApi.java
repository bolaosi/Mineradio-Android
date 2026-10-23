package com.mineradio.android.music;

import android.content.Context;
import android.content.SharedPreferences;
import com.mineradio.android.music.HttpTransport;
import com.mineradio.android.music.MusicApi;
import com.mineradio.android.music.NeteaseClient;

/** One account store/epoch and transport shared by UI requests and background playback. */
public final class AppMusicApi {
    private static AppMusicApi instance;
    public final HttpTransport transport;
    public final MusicApi api;

    private AppMusicApi(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("mineradio-accounts", Context.MODE_PRIVATE);
        transport = new HttpTransport();
        api = new MusicApi(new MusicApi.Store() {
            @Override public String get(String key) { return preferences.getString(key, ""); }
            @Override public void put(String key, String value) {
                if (!preferences.edit().putString(key, value).commit())
                    throw new IllegalStateException("Account storage unavailable");
            }
        }, new NeteaseClient(transport), transport);
    }

    public static synchronized AppMusicApi get(Context context) {
        if (instance == null) instance = new AppMusicApi(context.getApplicationContext());
        return instance;
    }
}
