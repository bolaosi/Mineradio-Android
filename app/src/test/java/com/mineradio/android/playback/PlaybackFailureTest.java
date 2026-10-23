package com.mineradio.android.playback;
import org.junit.Test;
import static org.junit.Assert.*;
public class PlaybackFailureTest {
    @Test public void distinguishesPermissionsNetworkDecodeAndOutput(){
        assertTrue(PlaybackFailure.message("ERROR_CODE_FAILED_RUNTIME_CHECK",true).contains("内部状态"));
        assertTrue(PlaybackFailure.message("MEDIA_TRACK_UNAVAILABLE",true).contains("版权"));
        assertTrue(PlaybackFailure.message("MEDIA_API_CONNECTION_FAILED",true).contains("获取播放地址"));
        assertTrue(PlaybackFailure.message("MEDIA_NETWORK_TIMEOUT",true).contains("超时"));
        assertTrue(PlaybackFailure.message("ERROR_CODE_DECODING_FAILED",true).contains("解码"));
        assertTrue(PlaybackFailure.message("ERROR_CODE_AUDIO_TRACK_INIT_FAILED",true).contains("系统音频输出"));
        assertTrue(PlaybackFailure.message("MEDIA_LOCAL_PERMISSION_LOST",false).contains("授权"));
    }
    @Test public void neverEchoesAnUntrustedUrlOrCookie(){
        String safe=PlaybackFailure.message("https://secret.example/?token=private",true);
        assertFalse(safe.contains("secret"));assertFalse(safe.contains("private"));assertTrue(safe.contains("MEDIA_UNKNOWN"));
    }
}
