package com.mineradio.android.playback;

import android.os.Looper;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAudioTrack;
import static org.junit.Assert.*;

/** Executes the service-created ExoPlayer and audio sink. AudioTrack is shadowed: no speaker/hardware clock. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class PlaybackServiceRuntimeTest {
    private static Object field(Object owner,String name)throws Exception{
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    static File wav()throws Exception{
        int samples=44100*3,bytes=samples*4;
        ByteBuffer out=ByteBuffer.allocate(44+bytes).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes()).putInt(36+bytes).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)2)
                .putInt(44100).putInt(44100*4).putShort((short)4).putShort((short)16).put("data".getBytes()).putInt(bytes);
        for(int i=0;i<samples;i++){short value=(short)(Math.sin(i*2*Math.PI*620/44100)*6000);out.putShort(value).putShort(value);}
        File file=File.createTempFile("mineradio-runtime-",".wav",RuntimeEnvironment.getApplication().getCacheDir());
        Files.write(file.toPath(),out.array());return file;
    }
    @Test public void servicePlayerStartsWithoutRuntimeFailureAndFeedsPcm()throws Exception{
        android.media.AudioManager manager=(android.media.AudioManager)RuntimeEnvironment.getApplication().getSystemService(android.content.Context.AUDIO_SERVICE);
        Shadows.shadowOf(manager).setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        var lifecycle=Robolectric.buildService(PlaybackService.class).create();PlaybackService service=lifecycle.get();
        ExoPlayer player=(ExoPlayer)field(service,"player");((SpectrumProcessor)field(service,"spectrum")).visible=true;
        AtomicInteger written=new AtomicInteger();
        ShadowAudioTrack.OnAudioDataWrittenListener listener=(track,data,format)->written.addAndGet(data.length);
        ShadowAudioTrack.addAudioDataListener(listener);File audio=wav();
        try{
            player.setMediaSource(new ProgressiveMediaSource.Factory(new DefaultDataSource.Factory(service))
                    .createMediaSource(MediaItem.fromUri(android.net.Uri.fromFile(audio))));
            player.prepare();player.play();
            for(int i=0;i<500&&player.getPlayerError()==null&&(written.get()<17640||!player.isPlaying());i++){
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));Thread.sleep(2);
            }
            PlaybackException failure=player.getPlayerError();
            if(failure!=null){
                System.out.println("playerFailure="+failure.getErrorCodeName());
                for(Throwable cause=failure;cause!=null;cause=cause.getCause()){
                    System.out.println("cause="+cause.getClass().getName());
                    for(StackTraceElement frame:cause.getStackTrace())System.out.println("at "+frame);
                }
            }
            assertNull("Service-created player must not fail at runtime",failure);
            assertTrue("PCM must reach AudioTrack (shadow)",written.get()>=17640);
            assertTrue("Service player must be playing",player.isPlaying());
            player.pause();assertFalse(player.getPlayWhenReady());
            player.play();assertTrue(player.getPlayWhenReady());
            for(int i=0;i<60&&player.getPlayerError()==null;i++){
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));Thread.sleep(2);
            }
            assertNull("Pause/resume must not introduce a runtime failure",player.getPlayerError());
            System.out.println("actual service/audio-sink PCM bytes="+written.get()+"; startup and pause/resume commands passed; no physical speaker, hardware clock or seek-output assertion");
        }finally{ShadowAudioTrack.removeAudioDataListener(listener);lifecycle.destroy();assertTrue(audio.delete());}
    }
}
