package com.mineradio.android.playback;

import android.net.Uri;
import androidx.media3.datasource.*;
import androidx.media3.extractor.*;
import androidx.media3.extractor.mp3.Mp3Extractor;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.DataReader;
import com.mineradio.android.music.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Opt-in real network, empty account store, actual Android Uri/Media3 resolver, transport and extractor.
 * Enabled with -PmineradioLiveAudioProbe; no physical Android codec/AudioTrack is exercised. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class NativeAudioLiveTest {
    @Test public void neteaseSynergyLoadsThroughActualNativeChain()throws Exception {probe("netease","2070287133","");}
    @Test public void qqFreeSongLoadsThroughActualNativeChain()throws Exception {probe("qq","002MicCm2pZIuc", "");}
    private void probe(String provider,String id,String mediaMid)throws Exception {
        HttpTransport http=new HttpTransport();MusicApi api=new MusicApi(new MusicApi.Store(){
            public String get(String key){return null;}public void put(String key,String value){throw new AssertionError("No credentials");}
        },new NeteaseClient(http),http);
        PlaybackTrack track=PlaybackTrack.parse(new JSONArray().put(new JSONObject().put("provider",provider).put("id",id)
            .put("mid",provider.equals("qq")?id:"").put("mediaMid",mediaMid).put("quality","standard"))).get(0);
        PlaybackResolver resolver=new PlaybackResolver(RuntimeEnvironment.getApplication(),api);resolver.setTracks(java.util.List.of(track));
        DataSource source=new ResolvingDataSource(new DefaultDataSource.Factory(RuntimeEnvironment.getApplication(),SafeMediaDataSource::new).createDataSource(),resolver);
        try{
            long length=source.open(new DataSpec.Builder().setUri(Uri.parse("mineradio://track/"+track.mediaId)).build());
            DefaultExtractorInput input=new DefaultExtractorInput(source,0,length);Mp3Extractor extractor=new Mp3Extractor();
            assertTrue("Actual CDN bytes must sniff as MP3",extractor.sniff(input));input.resetPeekPosition();
            int[] samples={0};extractor.init(new ExtractorOutput(){
                public TrackOutput track(int id,int type){return new TrackOutput(){
                    public void format(androidx.media3.common.Format f){}
                    public int sampleData(DataReader reader,int length,boolean allowEnd,int part)throws java.io.IOException {
                        byte[] buffer=new byte[Math.min(length,8192)];return reader.read(buffer,0,buffer.length);
                    }
                    public void sampleData(ParsableByteArray bytes,int length,int part){bytes.skipBytes(length);}
                    public void sampleMetadata(long time,int flags,int size,int offset,TrackOutput.CryptoData crypto){samples[0]++;}
                };}
                public void endTracks(){}public void seekMap(SeekMap seekMap){}
            });
            PositionHolder position=new PositionHolder();for(int i=0;i<200&&samples[0]<40;i++)if(extractor.read(input,position)==Extractor.RESULT_END_OF_INPUT)break;
            assertTrue("Native extractor must emit actual audio samples",samples[0]>=40);
            System.out.println(provider+" native-chain passed; mp3Samples="+samples[0]+"; trial="+resolver.details(track.mediaId).optBoolean("trial"));
            extractor.release();
        }catch(Exception failure){
            // Omit URL-bearing upstream exceptions from test logs.
            String code="NATIVE_CHAIN_FAILED_"+failure.getClass().getSimpleName();
            if(failure.getMessage()!=null&&failure.getMessage().matches("MEDIA_[A-Z_0-9]{1,60}"))code=failure.getMessage();
            throw new AssertionError(code);
        }finally{source.close();}
    }
}
