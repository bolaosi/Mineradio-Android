package com.mineradio.android.playback;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.mineradio.android.MainActivity;
import com.mineradio.android.music.AppMusicApi;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The sole owner of playback, including screen-off completion and headset/lock-screen controls. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlaybackService extends MediaSessionService {
    static final String COMMAND="com.mineradio.android.PLAYBACK_COMMAND";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final SpectrumProcessor spectrum=new SpectrumProcessor();
    private final RetryBudget retries=new RetryBudget();
    private ExoPlayer player;
    private MediaSession session;
    private PlaybackResolver resolver;
    private SharedPreferences preferences;
    private List<PlaybackTrack> queue=Collections.emptyList();
    private String mode="sequence",error="";
    private boolean editing;
    private long revision;
    private long queueRevision;
    private int playWhenReadyChangeReason=Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST;
    private final String sessionId=java.util.UUID.randomUUID().toString();
    private Equalizer equalizer;
    private double eqLow,eqMid,eqHigh;
    private boolean eqSupported;
    private final Runnable savePosition=new Runnable(){@Override public void run(){
        if(player!=null && player.getPlayWhenReady())persist();
        handler.postDelayed(this,15000);
    }};

    @Override public void onCreate(){
        super.onCreate();
        preferences=getSharedPreferences("mineradio-playback",MODE_PRIVATE);
        resolver=new PlaybackResolver(this,AppMusicApi.get(this).api);
        DefaultDataSource.Factory upstream=new DefaultDataSource.Factory(this,SafeMediaDataSource::new);
        ResolvingDataSource.Factory resolving=new ResolvingDataSource.Factory(upstream,resolver);
        DefaultRenderersFactory renderers=new DefaultRenderersFactory(this){
            @Override protected AudioSink buildAudioSink(Context context,boolean floatOutput,boolean playbackParams){
                return new DefaultAudioSink.Builder(context).setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(playbackParams)
                        .setAudioProcessors(new AudioProcessor[]{spectrum}).build();
            }
        };
        player=new ExoPlayer.Builder(this,renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(resolving)
                        .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(1)))
                .build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true);
        player.setHandleAudioBecomingNoisy(true);
        player.setWakeMode(C.WAKE_MODE_LOCAL);
        player.addListener(new Player.Listener(){
            @Override public void onEvents(Player ignored,Player.Events events){
                revision++;
                if(!editing && (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
                        ||events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)
                        ||events.contains(Player.EVENT_POSITION_DISCONTINUITY)))persist();
            }
            @Override public void onAudioSessionIdChanged(int id){attachEqualizer(id);}
            @Override public void onPlayWhenReadyChanged(boolean ready,int reason){playWhenReadyChangeReason=reason;}
            @Override public void onMediaItemTransition(MediaItem item,int reason){error="";}
            @Override public void onPlayerError(PlaybackException failure){handleFailure(failure);}
            @Override public void onIsPlayingChanged(boolean playing){
                MediaItem item=player.getCurrentMediaItem();if(!playing||item==null)return;
                String id=item.mediaId;
                handler.postDelayed(()->{
                    if(player!=null&&player.isPlaying()&&player.getCurrentMediaItem()!=null
                            &&id.equals(player.getCurrentMediaItem().mediaId))retries.reset(id);
                },5000);
            }
        });
        PendingIntent launch=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        session=new MediaSession.Builder(this,player).setCallback(new SessionCallback())
                .setSessionActivity(launch).build();
        restore();
        handler.postDelayed(savePosition,15000);
    }

    @Override public MediaSession onGetSession(MediaSession.ControllerInfo info){return session;}
    @Override public void onTaskRemoved(Intent rootIntent){
        if(player==null||!player.getPlayWhenReady()||player.getMediaItemCount()==0
                ||player.getPlaybackState()==Player.STATE_ENDED){
            if(player!=null){persist();player.stop();}
            stopSelf();
        }
        // An active/buffering queue continues in Media3's managed foreground service.
        super.onTaskRemoved(rootIntent);
    }
    @Override public void onDestroy(){
        handler.removeCallbacksAndMessages(null);
        if(player!=null){persist();player.release();player=null;}
        releaseEqualizer();
        if(session!=null){session.release();session=null;}
        super.onDestroy();
    }

    private final class SessionCallback implements MediaSession.Callback {
        @Override public int onPlayerCommandRequest(MediaSession s,MediaSession.ControllerInfo controller,int command){
            // A lock-screen Play after a load error must recover just like the in-app Play button.
            if((command==Player.COMMAND_PREPARE||command==Player.COMMAND_PLAY_PAUSE&&!player.getPlayWhenReady())
                    &&player.getPlaybackState()==Player.STATE_IDLE&&!queue.isEmpty()){
                MediaItem item=player.getCurrentMediaItem();
                if(item!=null){retries.reset(item.mediaId);resolver.invalidate(item.mediaId);}
                error="";player.prepare();
            }
            return SessionResult.RESULT_SUCCESS;
        }
        @Override public MediaSession.ConnectionResult onConnect(MediaSession s,MediaSession.ControllerInfo controller){
            boolean own=controller.getUid()==Process.myUid();
            if(!own&&!controller.isTrusted()&&!s.isMediaNotificationController(controller))
                return MediaSession.ConnectionResult.reject();
            Player.Commands commands=MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_SET_MEDIA_ITEM).remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).build();
            SessionCommands custom=own?new SessionCommands.Builder().add(new SessionCommand(COMMAND,Bundle.EMPTY)).build()
                    :SessionCommands.EMPTY;
            return MediaSession.ConnectionResult.accept(custom,commands);
        }
        @Override public ListenableFuture<SessionResult> onCustomCommand(MediaSession s,MediaSession.ControllerInfo controller,
                SessionCommand command,Bundle args){
            if(controller.getUid()!=Process.myUid()||!COMMAND.equals(command.customAction))
                return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED));
            Bundle response=new Bundle();
            long knownQueueRevision=-1;
            try{
                String json=args.getString("json","");
                if(json.length()>1024*1024)throw new IllegalArgumentException("播放命令过长");
                JSONObject request=new JSONObject(json);knownQueueRevision=request.optLong("knownQueueRevision",-1);
                execute(request);
            }catch(IllegalArgumentException e){response.putString("error",e.getMessage()==null?"播放命令无效":e.getMessage());}
            catch(Exception e){response.putString("error","无法执行播放操作，请重试");}
            response.putString("snapshot",snapshot(knownQueueRevision).toString());
            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS,response));
        }
    }

    private void execute(JSONObject command)throws Exception{
        String action=command.optString("action");
        switch(action){
            case "queue":setQueue(command);break;
            case "play":
                if(queue.isEmpty())throw new IllegalArgumentException("请先选择歌曲");
                error="";MediaItem item=player.getCurrentMediaItem();
                if(item!=null){retries.reset(item.mediaId);if(player.getPlayerError()!=null)resolver.invalidate(item.mediaId);}
                if(player.getPlaybackState()==Player.STATE_ENDED)player.seekToDefaultPosition();
                if(player.getPlaybackState()==Player.STATE_IDLE||player.getPlayerError()!=null)player.prepare();
                player.play();break;
            case "pause":player.pause();break;
            case "seek":
                long target=Math.max(0,command.optLong("positionMs",0));
                if(player.getDuration()!=C.TIME_UNSET)target=Math.min(target,Math.max(0,player.getDuration()));
                player.seekTo(target);break;
            case "next":step(true);break;
            case "previous":step(false);break;
            case "mode":setMode(command.optString("mode"));break;
            case "volume":
                double volume=command.optDouble("volume",1);
                player.setVolume((float)(Double.isFinite(volume)?Math.max(0,Math.min(1,volume)):1));break;
            case "stop":player.stop();player.setPlayWhenReady(false);error="";break;
            case "visible":spectrum.visible=command.optBoolean("visible",true);break;
            case "eq":
                eqLow=clampDb(command.optDouble("low",0));eqMid=clampDb(command.optDouble("mid",0));
                eqHigh=clampDb(command.optDouble("high",0));applyEqualizer();break;
            case "snapshot":return;
            default:throw new IllegalArgumentException("不支持的播放操作");
        }
        revision++;
        if(!action.equals("visible"))persist();
    }

    private void setQueue(JSONObject command)throws Exception{
        List<PlaybackTrack> replacement=PlaybackTrack.parse(command.optJSONArray("queue"));
        boolean keep=command.optBoolean("keepCurrent",false);
        String current=player.getCurrentMediaItem()==null?null:player.getCurrentMediaItem().mediaId;
        int oldIndex=player.getCurrentMediaItemIndex();
        int index=PlaybackTrack.replacementIndex(replacement,current,oldIndex,command.optInt("index",0),keep);
        boolean retained=keep&&current!=null&&!replacement.isEmpty()&&replacement.get(index).mediaId.equals(current);
        boolean autoplay=keep?player.getPlayWhenReady():command.optBoolean("autoplay",false);
        queue=replacement;queueRevision++;resolver.setTracks(queue);retries.clear();error="";
        editing=true;
        try{
            if(retained){
                // Incremental edits preserve the current MediaSource, decoder and buffer.
                for(int i=0;i<queue.size();i++){
                    if(i<player.getMediaItemCount()&&queue.get(i).mediaId.equals(player.getMediaItemAt(i).mediaId))continue;
                    int found=-1;for(int j=i+1;j<player.getMediaItemCount();j++)
                        if(queue.get(i).mediaId.equals(player.getMediaItemAt(j).mediaId)){found=j;break;}
                    if(found>=0)player.moveMediaItem(found,i);else player.addMediaItem(i,mediaItem(queue.get(i)));
                }
                if(player.getMediaItemCount()>queue.size())player.removeMediaItems(queue.size(),player.getMediaItemCount());
            }else{
                List<MediaItem> items=new ArrayList<>();for(PlaybackTrack track:queue)items.add(mediaItem(track));
                player.setMediaItems(items,index,0);
            }
            setMode(command.optString("mode",mode));
            player.setPlayWhenReady(autoplay);
            if(!queue.isEmpty()&&(!retained||player.getPlaybackState()==Player.STATE_IDLE))player.prepare();
            if(queue.isEmpty()){player.stop();player.setPlayWhenReady(false);}
        }finally{editing=false;}
    }

    private static MediaItem mediaItem(PlaybackTrack track){
        // Artwork is omitted here so the notification cannot initiate unrestricted image fetches.
        MediaMetadata metadata=new MediaMetadata.Builder().setTitle(track.field("name"))
                .setArtist(track.field("artist")).setIsPlayable(true).setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build();
        return new MediaItem.Builder().setMediaId(track.mediaId)
                .setUri("mineradio://track/"+track.mediaId).setMediaMetadata(metadata).build();
    }

    private void setMode(String value){
        mode=PlaybackTrack.mode(value);player.setShuffleModeEnabled(mode.equals("random"));
        player.setRepeatMode(mode.equals("single")?Player.REPEAT_MODE_ONE:Player.REPEAT_MODE_ALL);
    }
    private void step(boolean next){
        if(queue.isEmpty())return;
        error="";
        // Explicit next/previous always moves, even in repeat-one mode.
        if(mode.equals("single"))player.seekTo((player.getCurrentMediaItemIndex()+(next?1:-1)+queue.size())%queue.size(),0);
        else if(next)player.seekToNextMediaItem();else player.seekToPreviousMediaItem();
        MediaItem item=player.getCurrentMediaItem();if(item!=null)retries.reset(item.mediaId);
        if(player.getPlaybackState()==Player.STATE_IDLE)player.prepare();
    }
    private void handleFailure(PlaybackException failure){
        MediaItem item=player.getCurrentMediaItem();int index=player.getCurrentMediaItemIndex();
        boolean online=item!=null&&index>=0&&index<queue.size()&&!queue.get(index).isLocal();
        boolean expired=false;
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof SafeMediaDataSource.HttpFailure){
            int code=((SafeMediaDataSource.HttpFailure)cause).status;expired=code==401||code==403||code==404||code==410;break;
        }
        if(online&&expired&&retries.consume(item.mediaId)){
            String id=item.mediaId;long position=player.getCurrentPosition();boolean resume=player.getPlayWhenReady();
            resolver.invalidate(id);
            handler.post(()->{if(player==null||player.getCurrentMediaItem()==null||!id.equals(player.getCurrentMediaItem().mediaId))return;
                player.seekTo(position);player.prepare();player.setPlayWhenReady(resume);});
            return;
        }
        String code=safeErrorCode(failure);
        error=PlaybackFailure.message(code,online);
        // Deliberately omit Throwable/URI/account/track URL, including when the OS logs the warning.
        android.util.Log.w("MineradioPlayback","code="+code+"; source="+(online?"online":"local"));
        player.setPlayWhenReady(false);revision++;persist();
    }

    private JSONObject snapshot(long knownQueueRevision){
        JSONObject state=new JSONObject();
        try{
            if(knownQueueRevision!=queueRevision){
                JSONArray list=new JSONArray();for(PlaybackTrack track:queue)list.put(track.json());state.put("queue",list);
            }
            state.put("queueRevision",queueRevision);state.put("index",queue.isEmpty()?-1:player.getCurrentMediaItemIndex());
            state.put("positionMs",Math.max(0,player.getCurrentPosition()));
            state.put("durationMs",player.getDuration()==C.TIME_UNSET?0:Math.max(0,player.getDuration()));
            state.put("isPlaying",player.isPlaying());state.put("playWhenReady",player.getPlayWhenReady());
            state.put("buffering",player.getPlaybackState()==Player.STATE_BUFFERING);
            state.put("playbackState",player.getPlaybackState());state.put("error",error);state.put("mode",mode);
            state.put("playbackSuppressionReason",player.getPlaybackSuppressionReason());
            state.put("playWhenReadyChangeReason",playWhenReadyChangeReason);state.put("isLoading",player.isLoading());
            state.put("errorCode",safeErrorCode(player.getPlayerError()));
            state.put("revision",revision);state.put("eqSupported",eqSupported);
            state.put("sessionId",sessionId);state.put("volume",player.getVolume());
            MediaItem selected=player.getCurrentMediaItem();
            JSONObject details=selected==null?new JSONObject():resolver.details(selected.mediaId);
            state.put("trial",details.optBoolean("trial",false));
            state.put("restriction",details.optJSONObject("restriction")==null?JSONObject.NULL:details.optJSONObject("restriction"));
            state.put("quality",details.optString("quality",""));
            state.put("eq",new JSONObject().put("low",eqLow).put("mid",eqMid).put("high",eqHigh));
            state.put("spectrumScale","log");state.put("spectrumMinHz",20);state.put("spectrumMaxHz",20000);
            state.put("sampleRate",spectrum.sampleRate);state.put("fftSize",PcmAnalysis.FFT_SIZE);
            PcmAnalysis.Frame frame=player.isPlaying()?spectrum.latest:null;
            JSONArray bins=new JSONArray(),wave=new JSONArray();
            for(int i=0;i<64;i++)bins.put(frame==null?0:frame.spectrum[i]);
            for(int i=0;i<128;i++)wave.put(frame==null?128:frame.waveform[i]);
            state.put("spectrum",bins);state.put("waveform",wave);state.put("rms",frame==null?0:frame.rms);
        }catch(Exception ignored){}
        return state;
    }

    private boolean persistentGrant(String value){
        for(UriPermission permission:getContentResolver().getPersistedUriPermissions())
            if(permission.isReadPermission()&&permission.getUri().equals(Uri.parse(value)))return true;
        return false;
    }
    private static String safeErrorCode(PlaybackException failure){
        if(failure==null)return "";
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()){
            if(cause instanceof SafeMediaDataSource.HttpFailure)return "MEDIA_HTTP_"+((SafeMediaDataSource.HttpFailure)cause).status;
            String message=cause.getMessage();
            if(message!=null&&message.matches("MEDIA_[A-Z_]{1,60}"))return message;
        }
        return failure.getErrorCodeName();
    }
    private void persist(){
        if(player==null||editing)return;
        try{
            String current=player.getCurrentMediaItem()==null?"":player.getCurrentMediaItem().mediaId;
            JSONObject document=QueuePersistence.save(queue,current,player.getCurrentPosition(),this::persistentGrant)
                    .put("mode",mode).put("low",eqLow).put("mid",eqMid).put("high",eqHigh).put("volume",player.getVolume());
            preferences.edit().putString("state",document.toString()).apply();
        }catch(Exception ignored){}
    }
    private void restore(){
        try{
            JSONObject saved=QueuePersistence.restore(new JSONObject(preferences.getString("state","{}")),this::persistentGrant);
            JSONArray valid=saved.getJSONArray("queue");
            setMode(saved.optString("mode","sequence"));
            if(valid.length()>0){
                saved.put("queue",valid).put("autoplay",false);setQueue(saved);
                player.seekTo(Math.max(0,saved.optLong("positionMs",0)));
            }
            eqLow=clampDb(saved.optDouble("low",0));eqMid=clampDb(saved.optDouble("mid",0));eqHigh=clampDb(saved.optDouble("high",0));
            double volume=saved.optDouble("volume",1);player.setVolume((float)(Double.isFinite(volume)?Math.max(0,Math.min(1,volume)):1));
        }catch(Exception invalid){queue=Collections.emptyList();resolver.setTracks(queue);player.clearMediaItems();}
    }

    private static double clampDb(double value){return Double.isFinite(value)?Math.max(-12,Math.min(12,value)):0;}
    private void releaseEqualizer(){if(equalizer!=null){equalizer.release();equalizer=null;}eqSupported=false;}
    private void attachEqualizer(int id){
        releaseEqualizer();if(id==C.AUDIO_SESSION_ID_UNSET||id==0)return;
        try{equalizer=new Equalizer(0,id);eqSupported=equalizer.getNumberOfBands()>0;applyEqualizer();}
        catch(Exception unavailable){releaseEqualizer();}
    }
    private void applyEqualizer(){
        if(equalizer==null)return;
        try{
            if(equalizer.getNumberOfBands()==0||!equalizer.hasControl())throw new IllegalStateException();
            short[] range=equalizer.getBandLevelRange();
            for(short band=0;band<equalizer.getNumberOfBands();band++){
                double hz=equalizer.getCenterFreq(band)/1000.0;
                double db=hz<=100?eqLow:hz>=10000?eqHigh:hz<=1000?
                        eqLow+(eqMid-eqLow)*Math.log10(hz/100):eqMid+(eqHigh-eqMid)*Math.log10(hz/1000);
                equalizer.setBandLevel(band,(short)Math.max(range[0],Math.min(range[1],Math.round(db*100))));
            }
            if(equalizer.setEnabled(true)!=android.media.audiofx.AudioEffect.SUCCESS)throw new IllegalStateException();
            eqSupported=true;
        }catch(Exception unavailable){releaseEqualizer();}
    }
}
