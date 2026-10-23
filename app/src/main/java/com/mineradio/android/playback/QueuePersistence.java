package com.mineradio.android.playback;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/** A claimed persisted flag alone is never sufficient to retain a SAF URI across restarts. */
public final class QueuePersistence {
    public interface Grant { boolean canRead(String uri); }
    private QueuePersistence(){}
    public static JSONObject save(List<PlaybackTrack> queue,String current,long position,Grant grant)throws Exception{
        JSONArray result=new JSONArray();int index=0;boolean retained=false;
        for(PlaybackTrack track:queue){
            if(track.isLocal()&&(!track.json().optBoolean("persisted",false)||!grant.canRead(track.localUri)))continue;
            if(track.mediaId.equals(current)){index=result.length();retained=true;}
            result.put(track.json());
        }
        return new JSONObject().put("queue",result).put("index",index).put("currentMediaId",retained?current:"")
                .put("positionMs",retained?Math.max(0,position):0);
    }
    public static JSONObject restore(JSONObject saved,Grant grant)throws Exception{
        JSONArray source=saved.optJSONArray("queue"),valid=new JSONArray();
        if(source==null)source=new JSONArray();
        List<PlaybackTrack> tracks=PlaybackTrack.parse(source);
        String current=saved.optString("currentMediaId","");
        int index=0;boolean retained=false;
        for(PlaybackTrack track:tracks){
            if(track.isLocal()&&(!track.json().optBoolean("persisted",false)||!grant.canRead(track.localUri)))continue;
            if(track.mediaId.equals(current)){index=valid.length();retained=true;}
            valid.put(track.json());
        }
        return new JSONObject(saved.toString()).put("queue",valid).put("index",index)
                .put("positionMs",retained?Math.max(0,saved.optLong("positionMs",0)):0);
    }
}
