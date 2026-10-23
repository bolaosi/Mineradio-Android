package com.mineradio.android.playback;

import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionResult;
import androidx.media3.session.SessionToken;
import com.google.common.util.concurrent.ListenableFuture;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Activity-only connection; releasing it leaves ongoing service playback and notification alive. */
public final class PlaybackGateway implements AutoCloseable {
    public interface Listener { void onState(JSONObject snapshot); }
    public interface Callback { void onResult(JSONObject snapshot,String error); }
    private static final class Pending {
        final JSONObject command;final Callback callback;
        Pending(JSONObject command,Callback callback){this.command=command;this.callback=callback;}
    }
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final List<Pending> waiting=new ArrayList<>();
    private final ListenableFuture<MediaController> connection;
    private MediaController controller;
    private boolean closed,visible=true,polling;
    private String connectionError;
    private long knownQueueRevision=-1;
    private final Runnable poll=new Runnable(){@Override public void run(){
        if(closed||!visible)return;
        requestSnapshot();handler.postDelayed(this,150);
    }};

    public PlaybackGateway(Context context,Listener listener){
        requireMain();this.listener=listener;
        connection=new MediaController.Builder(context.getApplicationContext(),new SessionToken(context,
                new ComponentName(context,PlaybackService.class))).setListener(new MediaController.Listener(){
            @Override public void onDisconnected(MediaController ignored){
                controller=null;connectionError="播放服务连接已断开，请重新打开应用";
                failWaiting(connectionError);
            }
        }).buildAsync();
        connection.addListener(()->{
            if(closed)return;
            try{
                controller=connection.get();connectionError=null;
                controller.addListener(new Player.Listener(){
                    @Override public void onEvents(Player player,Player.Events events){if(visible)requestSnapshot();}
                });
                List<Pending> pending=new ArrayList<>(waiting);waiting.clear();
                for(Pending command:pending)send(command.command,command.callback);
                sendVisibility();handler.post(poll);
            }catch(Exception failure){connectionError="无法连接原生播放服务，请重新打开应用";failWaiting(connectionError);}
        },handler::post);
        handler.postDelayed(()->{
            if(controller==null&&!closed){connectionError="播放服务连接超时，请重新打开应用";failWaiting(connectionError);}
        },10000);
    }

    public void command(JSONObject command,Callback callback){
        requireMain();
        if(closed){complete(callback,null,"播放界面已关闭");return;}
        if(connectionError!=null){complete(callback,null,connectionError);return;}
        if(command==null){complete(callback,null,"播放命令无效");return;}
        // Binder parcels strings as UTF-16; leave room below its shared 1MiB transaction limit.
        if(command.toString().length()>350_000){complete(callback,null,"播放队列信息过长，请减少歌曲数量");return;}
        if(controller==null){
            if(waiting.size()>=30){complete(callback,null,"播放服务正在连接，请稍后重试");return;}
            try{waiting.add(new Pending(new JSONObject(command.toString()),callback));}
            catch(Exception invalid){complete(callback,null,"播放命令无效");}
        }else send(command,callback);
    }

    public void setVisible(boolean value){
        requireMain();visible=value;handler.removeCallbacks(poll);
        if(controller!=null)sendVisibility();
        if(value&&controller!=null)handler.post(poll);
    }
    private void sendVisibility(){
        try{send(new JSONObject().put("action","visible").put("visible",visible),null);}
        catch(Exception ignored){}
    }
    private void requestSnapshot(){
        if(polling||controller==null)return;
        try{
            polling=true;
            send(new JSONObject().put("action","snapshot").put("knownQueueRevision",knownQueueRevision),(snapshot,error)->{
                polling=false;if(visible&&!closed&&snapshot!=null&&listener!=null)listener.onState(snapshot);
            });
        }catch(Exception ignored){polling=false;}
    }
    private void send(JSONObject command,Callback callback){
        if(controller==null){complete(callback,null,"播放服务未连接");return;}
        Bundle args=new Bundle();args.putString("json",command.toString());
        ListenableFuture<SessionResult> future=controller.sendCustomCommand(
                new SessionCommand(PlaybackService.COMMAND,Bundle.EMPTY),args);
        boolean[] done={false};
        Runnable timeout=()->{if(!done[0]){done[0]=true;future.cancel(false);complete(callback,null,"播放操作超时，请重试");}};
        handler.postDelayed(timeout,10000);
        future.addListener(()->{
            if(done[0]||closed)return;done[0]=true;handler.removeCallbacks(timeout);
            try{
                SessionResult result=future.get();
                if(result.resultCode!=SessionResult.RESULT_SUCCESS){complete(callback,null,"原生播放操作未被接受");return;}
                String value=result.extras.getString("snapshot");
                JSONObject snapshot=value==null?null:new JSONObject(value);
                if(snapshot!=null)knownQueueRevision=snapshot.optLong("queueRevision",knownQueueRevision);
                complete(callback,snapshot,result.extras.getString("error"));
            }catch(Exception failure){complete(callback,null,"播放服务暂时不可用，请重试");}
        },handler::post);
    }
    private void failWaiting(String message){
        List<Pending> pending=new ArrayList<>(waiting);waiting.clear();
        for(Pending item:pending)complete(item.callback,null,message);
    }
    private static void complete(Callback callback,JSONObject snapshot,String error){if(callback!=null)callback.onResult(snapshot,error);}
    private static void requireMain(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("PlaybackGateway must run on the UI thread");}
    @Override public void close(){
        requireMain();if(closed)return;
        visible=false;if(controller!=null)sendVisibility();
        closed=true;handler.removeCallbacksAndMessages(null);failWaiting("播放界面已关闭");
        MediaController.releaseFuture(connection);controller=null;
    }
}
