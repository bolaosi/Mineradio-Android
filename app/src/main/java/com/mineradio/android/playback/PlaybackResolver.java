package com.mineradio.android.playback;

import android.net.Uri;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Process;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.ResolvingDataSource;
import com.mineradio.android.MediaPolicy;
import com.mineradio.android.music.MusicApi;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Runs only on ExoPlayer loading threads, never the UI thread; URLs remain in memory. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class PlaybackResolver implements ResolvingDataSource.Resolver {
    private static final class Cached {
        final String url;final long created;
        Cached(String url){this.url=url;created=System.nanoTime();}
    }
    private final MusicApi api;
    private final Context context;
    private volatile Map<String,PlaybackTrack> tracks=java.util.Collections.emptyMap();
    private final ConcurrentHashMap<String,Cached> cache=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,org.json.JSONObject> details=new ConcurrentHashMap<>();
    PlaybackResolver(Context context,MusicApi api){this.context=context.getApplicationContext();this.api=api;}
    void setTracks(List<PlaybackTrack> queue){
        Map<String,PlaybackTrack> next=new HashMap<>();for(PlaybackTrack t:queue){
            next.put(t.mediaId,t);PlaybackTrack previous=tracks.get(t.mediaId);
            if(previous!=null&&(!previous.field("quality").equals(t.field("quality"))
                    ||!previous.field("mediaMid").equals(t.field("mediaMid")))){
                cache.remove(t.mediaId);details.remove(t.mediaId);
            }
        }
        tracks=next;cache.keySet().retainAll(next.keySet());details.keySet().retainAll(next.keySet());
    }
    void invalidate(String id){cache.remove(id);}
    org.json.JSONObject details(String id){
        org.json.JSONObject value=details.get(id);
        try{return value==null?new org.json.JSONObject():new org.json.JSONObject(value.toString());}
        catch(Exception impossible){return new org.json.JSONObject();}
    }
    @Override public DataSpec resolveDataSpec(DataSpec spec)throws IOException {
        if(!"mineradio".equals(spec.uri.getScheme()) || !"track".equals(spec.uri.getHost()))
            throw new IOException("MEDIA_ITEM_REJECTED");
        String id=spec.uri.getLastPathSegment();PlaybackTrack track=tracks.get(id);
        if(track==null)throw new IOException("MEDIA_ITEM_REMOVED");
        if(track.isLocal()){
            Uri uri=Uri.parse(track.localUri);
            if(context.checkUriPermission(uri,Process.myPid(),Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    !=PackageManager.PERMISSION_GRANTED)throw new IOException("MEDIA_LOCAL_PERMISSION_LOST");
            return spec.withUri(uri);
        }
        Cached cached=cache.get(id);
        if(cached==null || System.nanoTime()-cached.created>240_000_000_000L){
            if(Thread.currentThread().isInterrupted())throw new IOException("MEDIA_CANCELLED");
            String path;
            if(track.provider.equals("qq"))path="/api/qq/song/url?mid="+encode(track.field("mid").isEmpty()?track.id:track.field("mid"))
                    +"&mediaMid="+encode(track.field("mediaMid"));
            else path="/api/song/url?id="+encode(track.id);
            path+="&quality="+encode(track.field("quality"));
            MusicApi.ApiResponse result=api.route(path,"GET","");
            if(Thread.currentThread().isInterrupted())throw new IOException("MEDIA_CANCELLED");
            if(result.status==401)throw new IOException("MEDIA_LOGIN_REQUIRED");
            if(result.status>=500)throw new IOException("MEDIA_API_CONNECTION_FAILED");
            if(result.status!=200)throw new IOException("MEDIA_API_REQUEST_REJECTED");
            if(!result.body.optBoolean("playable",false) || result.body.optString("url").isEmpty())
                throw new IOException("MEDIA_TRACK_UNAVAILABLE");
            String url=result.body.optString("url");
            try{MediaPolicy.validate(url);}catch(Exception invalid){throw new IOException("MEDIA_HOST_REJECTED");}
            try{
                org.json.JSONObject info=new org.json.JSONObject().put("trial",result.body.optBoolean("trial",false));
                for(String field:new String[]{"quality","level","requestedQuality"})info.put(field,result.body.optString(field,""));
                org.json.JSONObject restriction=result.body.optJSONObject("restriction");
                if(restriction!=null){org.json.JSONObject safe=new org.json.JSONObject();
                    for(String field:new String[]{"kind","category","message","provider"})safe.put(field,restriction.optString(field,""));
                    info.put("restriction",safe);
                }
                details.put(id,info);
            }catch(Exception impossible){throw new IOException("MEDIA_METADATA_FAILED");}
            cached=new Cached(url);cache.put(id,cached);
        }
        return spec.withUri(Uri.parse(cached.url));
    }
    @Override public Uri resolveReportedUri(Uri uri){return Uri.parse("mineradio://track");}
    private static String encode(String value){
        try{return URLEncoder.encode(value,"UTF-8");}catch(java.io.UnsupportedEncodingException impossible){throw new AssertionError(impossible);}
    }
}
