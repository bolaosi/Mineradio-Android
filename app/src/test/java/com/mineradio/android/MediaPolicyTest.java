package com.mineradio.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class MediaPolicyTest {
    @Test public void upgradesCdnWithoutChangingSignedPathOrQuery() {
        assertEquals("https://m802.music.126.net/a%2Fb.mp3?vkey=a%2Bb%3D&x=1",
                MediaPolicy.secureUri("http://m802.music.126.net:80/a%2Fb.mp3?vkey=a%2Bb%3D&x=1").toString());
        assertEquals("https://aqqmusic.tc.qq.com/a.m4a?vkey=test",
                MediaPolicy.secureUri("http://aqqmusic.tc.qq.com/a.m4a?vkey=test").toString());
    }
    @Test public void permitsOfficialMusicCdnOnly() {
        assertEquals("m801.music.126.net", MediaPolicy.validate("http://m801.music.126.net/a.mp3").getHost());
        assertEquals("isure.stream.qqmusic.qq.com", MediaPolicy.validate("https://isure.stream.qqmusic.qq.com/a.m4a?v=1").getHost());
        assertEquals("aqqmusic.tc.qq.com", MediaPolicy.validate("https://aqqmusic.tc.qq.com/a.mp3?vkey=test").getHost());
        assertEquals("y.gtimg.cn", MediaPolicy.validate("https://y.gtimg.cn/a.jpg").getHost());
        assertEquals("y.qq.com", MediaPolicy.validate("https://y.qq.com/music/photo_new/T002R300x300M000test.jpg").getHost());
        for (String value : new String[]{"file:///sdcard/private", "http://127.0.0.1/a", "http://192.168.1.2/a",
                "https://music.126.net.evil.example/a", "https://user:secret@music.126.net/a", "https://evil.example@music.126.net/a",
                "https://aqqmusic.tc.qq.com.evil.example/a", "https://unrelated.qq.com/a",
                "https://music.126.net:8080/a", "https://y.qq.com/account", "https://evil.example/a", "content://music.126.net/a"}) {
            try { MediaPolicy.validate(value); fail("Accepted non-CDN URL"); } catch (IllegalArgumentException expected) { }
        }
    }
}
