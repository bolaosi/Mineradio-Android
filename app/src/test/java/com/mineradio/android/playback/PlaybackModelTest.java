package com.mineradio.android.playback;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class PlaybackModelTest {
    private static JSONObject song(String id)throws Exception{return new JSONObject().put("provider","netease").put("id",id).put("name","song"+id);}
    @Test public void queueAcceptsMixedSafAndOnlineMetadata()throws Exception{
        JSONObject local=new JSONObject().put("provider","local").put("id","local:1")
                .put("localUri","content://com.android.providers.media.documents/document/audio%3A42").put("persisted",true);
        List<PlaybackTrack> queue=PlaybackTrack.parse(new JSONArray().put(song("123")).put(local));
        assertEquals(2,queue.size());assertTrue(queue.get(1).isLocal());assertTrue(queue.get(1).json().optBoolean("persisted"));
    }
    @Test public void sensitiveAndPlaybackUrlsAreNeverStoredInMetadata()throws Exception{
        JSONObject input=song("123").put("cookie","secret").put("url","https://signed.invalid/?token=secret").put("token","secret");
        String json=PlaybackTrack.parse(new JSONArray().put(input)).get(0).json().toString();
        assertFalse(json.contains("secret"));assertFalse(json.contains("signed"));assertFalse(json.contains("cookie"));
    }
    @Test public void localBlobFileAndNetworkUrisAreRejected()throws Exception{
        for(String uri:new String[]{"blob:https://appassets.androidplatform.net/a","file:///data/data/x/private","https://example.com/file.mp3"}){
            try{PlaybackTrack.parse(new JSONArray().put(new JSONObject().put("provider","local").put("localUri",uri)));fail(uri);}
            catch(IllegalArgumentException expected){}
        }
    }
    @Test public void onlineItemsCannotSmuggleContentUris()throws Exception{
        try{PlaybackTrack.parse(new JSONArray().put(song("123").put("localUri","content://private/1")));fail();}
        catch(IllegalArgumentException expected){}
    }
    @Test public void appendAndRemovePreserveCurrentIdentity()throws Exception{
        List<PlaybackTrack> old=PlaybackTrack.parse(new JSONArray().put(song("1")).put(song("2")));
        List<PlaybackTrack> next=PlaybackTrack.parse(new JSONArray().put(song("2")).put(song("3")));
        assertEquals(0,PlaybackTrack.replacementIndex(next,old.get(1).mediaId,1,1,true));
        assertEquals(1,PlaybackTrack.replacementIndex(next,old.get(1).mediaId,1,1,false));
    }
    @Test public void removedCurrentSelectsNearestSurvivorAndEmptyIsSafe()throws Exception{
        List<PlaybackTrack> next=PlaybackTrack.parse(new JSONArray().put(song("1")).put(song("3")));
        assertEquals(1,PlaybackTrack.replacementIndex(next,"removed",2,0,true));
        assertEquals(0,PlaybackTrack.replacementIndex(java.util.Collections.emptyList(),"removed",2,0,true));
    }
    @Test public void duplicateTrackIdentityIsStableAcrossMetadataChanges()throws Exception{
        PlaybackTrack a=PlaybackTrack.parse(new JSONArray().put(song("123"))).get(0);
        PlaybackTrack b=PlaybackTrack.parse(new JSONArray().put(song("123").put("name","updated"))).get(0);
        assertEquals(a.mediaId,b.mediaId);
    }
    @Test public void queueBoundAndProviderAreEnforced()throws Exception{
        JSONArray tooLarge=new JSONArray();for(int i=0;i<501;i++)tooLarge.put(song("1"));
        try{PlaybackTrack.parse(tooLarge);fail();}catch(IllegalArgumentException expected){}
        try{PlaybackTrack.parse(new JSONArray().put(new JSONObject().put("provider","unknown")));fail();}
        catch(IllegalArgumentException expected){}
    }
    @Test public void refreshBudgetPreventsInfiniteFailureLoop(){
        RetryBudget budget=new RetryBudget();assertTrue(budget.consume("a"));assertFalse(budget.consume("a"));
        assertTrue(budget.consume("b"));budget.reset("a");assertTrue(budget.consume("a"));
        budget.clear();assertTrue(budget.consume("b"));
    }
    @Test public void persistenceRequiresRealGrantAndFlagAndDoesNotReuseRemovedPosition()throws Exception{
        JSONObject local=new JSONObject().put("provider","local").put("id","local:1")
                .put("localUri","content://documents/audio/1").put("persisted",true);
        List<PlaybackTrack> queue=PlaybackTrack.parse(new JSONArray().put(song("1")).put(local));
        JSONObject denied=QueuePersistence.save(queue,queue.get(1).mediaId,42000,uri->false);
        assertEquals(1,denied.getJSONArray("queue").length());assertEquals(0,denied.getLong("positionMs"));
        JSONObject granted=QueuePersistence.save(queue,queue.get(1).mediaId,42000,uri->true);
        assertEquals(2,granted.getJSONArray("queue").length());assertEquals(1,granted.getInt("index"));
        assertEquals(42000,granted.getLong("positionMs"));
        local.put("persisted",false);queue=PlaybackTrack.parse(new JSONArray().put(local));
        assertEquals(0,QueuePersistence.save(queue,queue.get(0).mediaId,5,uri->true).getJSONArray("queue").length());
    }
    @Test public void pcmSilenceIsActuallySilent(){
        PcmAnalysis.Frame frame=PcmAnalysis.analyze(new float[512],44100);
        assertEquals(0,frame.rms,0);for(int level:frame.spectrum)assertEquals(0,level);
        for(int sample:frame.waveform)assertEquals(128,sample);
    }
    @Test public void revokedGrantDoesNotShiftRestoredCurrentSong()throws Exception{
        JSONObject local=new JSONObject().put("provider","local").put("localUri","content://documents/audio/1").put("persisted",true);
        List<PlaybackTrack> queue=PlaybackTrack.parse(new JSONArray().put(local).put(song("99")));
        JSONObject saved=QueuePersistence.save(queue,queue.get(1).mediaId,12345,uri->true);
        JSONObject restored=QueuePersistence.restore(saved,uri->false);
        assertEquals(1,restored.getJSONArray("queue").length());assertEquals("99",restored.getJSONArray("queue").getJSONObject(0).getString("id"));
        assertEquals(0,restored.getInt("index"));assertEquals(12345,restored.getLong("positionMs"));
        saved=QueuePersistence.save(queue,queue.get(0).mediaId,9000,uri->true);
        assertEquals(0,QueuePersistence.restore(saved,uri->false).getLong("positionMs"));
    }
    @Test public void pcmToneProducesFrequencyPeakAndRealWaveform(){
        float[] samples=new float[512];double hz=1033.59375;
        for(int i=0;i<samples.length;i++)samples[i]=(float)(0.5*Math.sin(2*Math.PI*hz*i/44100));
        PcmAnalysis.Frame frame=PcmAnalysis.analyze(samples,44100);int peak=0;
        for(int i=1;i<64;i++)if(frame.spectrum[i]>frame.spectrum[peak])peak=i;
        double low=20*Math.pow(1000,peak/64.0),high=20*Math.pow(1000,(peak+1)/64.0);
        assertTrue(low<1200&&high>900);assertEquals(Math.sqrt(0.125),frame.rms,0.02);
        assertTrue(java.util.Arrays.stream(frame.waveform).min().getAsInt()<80);
        assertTrue(java.util.Arrays.stream(frame.waveform).max().getAsInt()>175);
    }
    @Test public void pcmOutputIsFiniteAndBoundedAtLowSampleRates(){
        float[] input=new float[512];java.util.Arrays.fill(input,1);
        PcmAnalysis.Frame frame=PcmAnalysis.analyze(input,8000);
        for(int value:frame.spectrum)assertTrue(value>=0&&value<=255);
        assertEquals(1,frame.rms,0);assertEquals(255,frame.waveform[0]);
    }
}
