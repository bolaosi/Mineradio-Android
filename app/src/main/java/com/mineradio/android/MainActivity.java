package com.mineradio.android;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.*;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.*;
import com.mineradio.android.music.HttpTransport;
import com.mineradio.android.music.MusicApi;
import com.mineradio.android.music.AppMusicApi;
import com.mineradio.android.playback.PlaybackGateway;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Bundled main-frame content alone receives native capabilities. */
public class MainActivity extends AppCompatActivity {
    private static final String ORIGIN = "https://mineradio.local";
    private WebView webView;
    private HttpTransport transport;
    private MusicApi musicApi;
    private PlaybackGateway playback;
    private JavaScriptReplyProxy stateReply, audioImportReply, qqLoginReply;
    private String audioImportId, qqLoginId, qqRequestId;
    private boolean qqActivityRunning;
    private final ExecutorService network = Executors.newFixedThreadPool(4);
    private final ExecutorService cancellation = Executors.newSingleThreadExecutor();
    private final Map<String, FutureTask<Void>> active = new ConcurrentHashMap<>();
    private final Map<String, Thread> requestThreads = new ConcurrentHashMap<>();
    private final Set<String> qqValidations = ConcurrentHashMap.newKeySet();
    private final Set<HttpURLConnection> media = ConcurrentHashMap.newKeySet();
    private ValueCallback<Uri[]> fileCallback;
    private byte[] qrBytes;
    private JavaScriptReplyProxy qrReply;
    private String qrId;

    private final ActivityResultLauncher<Intent> audioDocuments = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            JavaScriptReplyProxy reply = audioImportReply; String id = audioImportId;
            audioImportReply = null; audioImportId = null;
            if (reply == null) return;
            Intent data = result.getData();
            if (result.getResultCode() != RESULT_OK || data == null) { respond(reply,id,json("cancelled",true),null); return; }
            network.execute(() -> {
                JSONArray tracks = LocalAudioDocuments.selected(this, data);
                JSONObject response = json("tracks",tracks);
                try { response.put("message", tracks.length() == 0 ? "没有可读取的音频；文件可能为空、失效或格式不支持，请重新选择" : "已取得系统文件读取权限"); }
                catch (Exception ignored) { }
                respond(reply,id,response,null);
            });
        });
    private final ActivityResultLauncher<Intent> qqWebLogin = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            qqActivityRunning = false;
            JavaScriptReplyProxy reply = qqLoginReply; String id = qqLoginId, nonce = qqRequestId;
            qqLoginReply = null; qqLoginId = null; qqRequestId = null;
            String returned = result.getData()==null?null:result.getData().getStringExtra(QQLoginActivity.EXTRA_REQUEST_ID);
            if (reply == null) { QQLoginActivity.discardCookie(this,returned); return; }
            if(nonce!=null&&!nonce.equals(returned)) { QQLoginActivity.discardCookie(this,returned); QQLoginActivity.discardCookie(this,nonce); respond(reply,id,null,"QQ 登录交接已失效，请重试"); return; }
            if (result.getResultCode() != RESULT_OK || nonce == null) { if(nonce!=null)QQLoginActivity.discardCookie(this,nonce); respond(reply,id,json("cancelled",true),null); return; }
            FutureTask<Void> validation = new FutureTask<>(() -> {
                requestThreads.put(id,Thread.currentThread());
                try {
                    String cookie = QQLoginActivity.takeCookie(this, nonce);
                    if (cookie == null || cookie.isEmpty() || Thread.currentThread().isInterrupted()) throw new IOException();
                    MusicApi.ApiResponse response = musicApi.route("/api/qq/login/cookie", "POST", json("cookie",cookie).toString());
                    if (Thread.currentThread().isInterrupted()) return null;
                    if (response.status >= 200 && response.status < 300 && response.body.optBoolean("verified") && response.body.optBoolean("saved"))
                        respond(reply,id,response.body,null);
                    else respond(reply,id,null,"QQ 会话未通过官方账号复核，请重试或使用 Cookie 备用入口");
                } catch (Exception ignored) { if(!Thread.currentThread().isInterrupted())respond(reply,id,null,"QQ 登录未完成，未保存新会话，请重试或使用 Cookie 备用入口"); }
                finally { active.remove(id);requestThreads.remove(id);qqValidations.remove(id); }
                return null;
            });
            qqValidations.add(id);active.put(id,validation);network.execute(validation);
        });

    private final ActivityResultLauncher<Intent> files = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            ValueCallback<Uri[]> callback = fileCallback; fileCallback = null;
            if (callback == null) return;
            List<Uri> selected = new ArrayList<>();
            Intent data = result.getData();
            if (result.getResultCode() == RESULT_OK && data != null) {
                ClipData clips = data.getClipData();
                if (clips != null) {
                    for (int i = 0; i < Math.min(clips.getItemCount(), 100); i++) {
                        Uri uri = clips.getItemAt(i).getUri();
                        if (isDocument(uri)) selected.add(uri);
                    }
                } else if (isDocument(data.getData())) selected.add(data.getData());
            }
            callback.onReceiveValue(selected.isEmpty() ? null : selected.toArray(new Uri[0]));
            if (selected.isEmpty()) cancelFiles();
            // Session-only grants: never persist temporary blob URLs as a library.
        });
    private final ActivityResultLauncher<Intent> saveImage = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            byte[] bytes = qrBytes; JavaScriptReplyProxy reply = qrReply; String id = qrId;
            qrBytes = null; qrReply = null; qrId = null;
            if (reply == null) return;
            Uri uri = result.getData() == null ? null : result.getData().getData();
            if (result.getResultCode() != RESULT_OK || uri == null) { respond(reply,id,json("cancelled",true),null); return; }
            network.execute(() -> {
                try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null || bytes == null) throw new IOException();
                    output.write(bytes); respond(reply,id,json("saved",true),null);
                } catch (Exception e) { respond(reply,id,null,"二维码保存失败，请重试"); }
            });
        });

    @Override @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        AppMusicApi sharedApi = AppMusicApi.get(this);
        transport = sharedApi.transport; musicApi = sharedApi.api;
        playback = new PlaybackGateway(this, this::publishPlayback);
        webView = new WebView(this);
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            webView.destroy(); webView = null;
            TextView message = new TextView(this);
            message.setText("请先更新 Android System WebView 或 Chrome，再打开 Mineradio。");
            message.setTextSize(20); message.setPadding(32,64,32,32); setContentView(message); return;
        }
        setContentView(webView);
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false); settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        WebViewCompat.addWebMessageListener(webView,"MineradioNative",Collections.singleton(ORIGIN),
            (view,message,source,isMainFrame,reply) -> {
                if (!isMainFrame || !ORIGIN.equals(source.toString())) return;
                String id = "";
                try {
                    JSONObject c = new JSONObject(message.getData()); id = c.getString("id");
                    switch (c.getString("type")) {
                        case "cancel":
                            String cancelled = c.optString("requestId");
                            qqValidations.remove(cancelled);
                            if (cancelled.equals(qqLoginId)) cancelQQLogin();
                            FutureTask<Void> old = active.remove(cancelled);
                            if (old != null) old.cancel(true);
                            Thread owner = requestThreads.get(cancelled);
                            if (owner != null) cancellation.execute(() -> {
                                if (requestThreads.get(cancelled) == owner) transport.cancel(owner);
                            });
                            break;
                        case "getConfig": stateReply=reply; respond(reply,id,config(),null); break;
                        case "playback":
                            final String playbackId = id;
                            playback.command(c.getJSONObject("command"),(snapshot,error)->respond(reply,playbackId,snapshot,error)); break;
                        case "pickAudio":
                            if (audioImportReply != null) { respond(reply,id,null,"文件选择器已打开"); break; }
                            audioImportReply=reply; audioImportId=id;
                            try { audioDocuments.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("audio/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)); }
                            catch (Exception ignored) { audioImportReply=null; audioImportId=null; respond(reply,id,null,"没有可用的系统文件选择器"); }
                            break;
                        case "openQQLogin":
                            if (qqActivityRunning) { respond(reply,id,null,"QQ 登录页尚未关闭，请稍后再试"); break; }
                            cancelQQValidations();
                            qqLoginReply=reply; qqLoginId=id; qqRequestId=UUID.randomUUID().toString();
                            try { qqActivityRunning=true; qqWebLogin.launch(new Intent(this,QQLoginActivity.class).putExtra(QQLoginActivity.EXTRA_REQUEST_ID,qqRequestId)); }
                            catch (Exception ignored) { qqActivityRunning=false; qqLoginReply=null; qqLoginId=null; qqRequestId=null; respond(reply,id,null,"QQ 登录页无法打开，请使用 Cookie 备用入口"); }
                            break;
                        case "saveConfig":
                            respond(reply,id,null,"此版本已内置在线接口，无需配置后端"); break;
                        case "saveQr": beginSave(c.optString("dataUri"),reply,id); break;
                        case "request":
                            if(c.optString("path").startsWith("/api/qq/logout"))cancelQQLogin();
                            final String requestId = id;
                            FutureTask<Void> task = new FutureTask<>(() -> {
                                requestThreads.put(requestId, Thread.currentThread());
                                try { request(c,reply,requestId); }
                                finally { active.remove(requestId); requestThreads.remove(requestId); }
                                return null;
                            });
                            active.put(id,task); network.execute(task); break;
                        default: respond(reply,id,null,"不支持的操作");
                    }
                } catch (Exception e) { respond(reply,id,null,"操作参数无效，请重试"); }
            });
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder().setDomain("mineradio.local")
            .addPathHandler("/",new WebViewAssetLoader.AssetsPathHandler(this)).build();
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!isApp(uri)) return null;
                if (uri.getPath() != null && uri.getPath().startsWith("/api/")) return proxy(request);
                if (uri.getPath() != null && uri.getPath().startsWith("/nodejs-project/")) return unavailable(404);
                WebResourceResponse response = loader.shouldInterceptRequest(uri);
                return response == null ? unavailable(404) : response;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isApp(uri) && "/index.html".equals(uri.getPath())) return false;
                if (request.isForMainFrame() && request.hasGesture() && "https".equals(uri.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW,uri)); }
                    catch (Exception e) { Toast.makeText(MainActivity.this,"无法打开链接",Toast.LENGTH_SHORT).show(); }
                }
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (!isApp(Uri.parse(view.getUrl() == null ? "" : view.getUrl()))) return false;
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/*","image/*","video/*","application/json"})
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE,params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { files.launch(intent); }
                catch (Exception e) { fileCallback=null; callback.onReceiveValue(null); cancelFiles();
                    Toast.makeText(MainActivity.this,"没有可用的系统文件选择器",Toast.LENGTH_LONG).show(); }
                return true;
            }
        });
        // Regular first script bootstraps before application initialization, never onPageFinished.
        webView.loadUrl(ORIGIN + "/index.html");
    }
    private static boolean isApp(Uri u) { return "https".equals(u.getScheme()) && "mineradio.local".equals(u.getHost()) && u.getPort() == -1; }
    private static boolean isDocument(Uri u) { return u != null && "content".equals(u.getScheme()); }
    private JSONObject config() {
        JSONObject c=json("mode","builtin");
        try { c.put("ready",true); c.put("nativePlayback",true); } catch (Exception ignored) { } return c;
    }
    private void request(JSONObject command,JavaScriptReplyProxy reply,String id) {
        try {
            String method=command.optString("method","GET");
            if (!"GET".equals(method) && !"POST".equals(method)) throw new IllegalArgumentException();
            String body=command.optString("body", "");
            if (body.length()>256*1024) throw new IllegalArgumentException();
            String path=command.getString("path");
            if (!path.startsWith("/api/") || path.contains("#") || path.contains("..")) throw new IllegalArgumentException();
            MusicApi.ApiResponse response=musicApi.route(path,method,body);
            JSONObject value=json("status",response.status);
            value.put("body",response.body.toString());
            if (!Thread.currentThread().isInterrupted()) respond(reply,id,value,null);
        } catch(Exception e) {
            if (!Thread.currentThread().isInterrupted()) respond(reply,id,null,"官方接口请求失败，请检查网络后重试；本地音乐可继续使用");
        }
    }
    private WebResourceResponse proxy(WebResourceRequest request) {
        HttpURLConnection c=null;
        try {
            if(!"GET".equals(request.getMethod()))return unavailable(405);
            Uri u=request.getUrl();
            if (!"/api/audio".equals(u.getPath()) && !"/api/cover".equals(u.getPath())) return unavailable(404);
            String range = null;
            for(Map.Entry<String,String> h:request.getRequestHeaders().entrySet())
                if("Range".equalsIgnoreCase(h.getKey()))range=h.getValue();
            c=MediaPolicy.open(u.getQueryParameter("url"),range); media.add(c);
            int status=c.getResponseCode();
            if(status>=300&&status<400){c.disconnect();return unavailable(502);}
            InputStream source=status>=400?c.getErrorStream():c.getInputStream();
            final HttpURLConnection owned=c;
            InputStream stream=source==null?new ByteArrayInputStream(new byte[0]):new FilterInputStream(source){
                @Override public void close()throws IOException{try{super.close();}finally{media.remove(owned);owned.disconnect();}}
            };
            String type=c.getContentType(),mime="application/json";
            if(type!=null&&(type.startsWith("image/")||type.startsWith("audio/")))mime=type.split(";")[0];
            Map<String,String> headers=new HashMap<>();
            headers.put("Cache-Control","no-store");headers.put("X-Content-Type-Options","nosniff");
            for(String name:new String[]{"Content-Range","Accept-Ranges","Content-Length"})
                if(c.getHeaderField(name)!=null)headers.put(name,c.getHeaderField(name));
            return new WebResourceResponse(mime,null,status,"Music resource",headers,stream);
        }catch(Exception e){if(c!=null){media.remove(c);c.disconnect();}return unavailable(503);}
    }
    private static JSONObject json(String key,Object value){JSONObject j=new JSONObject();try{j.put(key,value);}catch(Exception ignored){}return j;}
    private void respond(JavaScriptReplyProxy reply,String id,JSONObject result,String error){
        JSONObject message=json("id",id);try{message.put("result",result);message.put("error",error);}catch(Exception ignored){}
        runOnUiThread(()->{if(webView!=null&&!isFinishing())reply.postMessage(message.toString());});
    }
    private void publishPlayback(JSONObject state) {
        if (webView == null || stateReply == null || isFinishing()) return;
        JSONObject event=json("event","playback");
        try { event.put("state",state); stateReply.postMessage(event.toString()); }
        catch (Exception ignored) { /* A replaced WebView reconnects and requests a fresh snapshot. */ }
    }
    private void cancelQQLogin() {
        cancelQQValidations();
        String nonce=qqRequestId;qqRequestId=null;qqLoginReply=null;qqLoginId=null;
        if(nonce!=null){QQLoginActivity.cancel(this,nonce);QQLoginActivity.discardCookie(this,nonce);}
    }
    private void cancelQQValidations() {
        for(String id:qqValidations){
            FutureTask<Void> task=active.remove(id);if(task!=null)task.cancel(true);
            Thread owner=requestThreads.get(id);
            if(owner!=null)cancellation.execute(()->{if(requestThreads.get(id)==owner)transport.cancel(owner);});
        }
        qqValidations.clear();
    }
    @Override protected void onStart() { super.onStart(); if(playback!=null)playback.setVisible(true); if(webView!=null)webView.onResume(); }
    @Override protected void onStop() { if(playback!=null)playback.setVisible(false); if(webView!=null)webView.onPause(); super.onStop(); }
    private void beginSave(String data,JavaScriptReplyProxy reply,String id){
        try{
            if(qrBytes!=null||!data.startsWith("data:image/png;base64,")||data.length()>3*1024*1024)throw new IllegalArgumentException();
            byte[] bytes=Base64.decode(data.substring(data.indexOf(',')+1),Base64.DEFAULT);
            if(bytes.length<8||bytes[0]!=(byte)0x89||bytes[1]!=0x50||bytes[2]!=0x4e||bytes[3]!=0x47)throw new IllegalArgumentException();
            qrBytes=bytes;qrReply=reply;qrId=id;
            saveImage.launch(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/png").putExtra(Intent.EXTRA_TITLE,"Mineradio-网易云登录二维码.png"));
        }catch(Exception e){qrBytes=null;qrReply=null;qrId=null;respond(reply,id,null,"二维码保存失败，请刷新重试");}
    }
    private void cancelFiles(){if(webView!=null)webView.evaluateJavascript("window.dispatchEvent(new Event('android-file-picker-cancel'))",null);}
    private static WebResourceResponse unavailable(int status){
        return new WebResourceResponse("application/json","UTF-8",status,"Unavailable",Collections.singletonMap("Cache-Control","no-store"),
            new ByteArrayInputStream("{\"error\":\"RESOURCE_UNAVAILABLE\"}".getBytes(StandardCharsets.UTF_8)));
    }
    @Override protected void onDestroy(){
        cancelQQLogin();
        if(playback!=null){playback.close();playback=null;}stateReply=null;
        if(fileCallback!=null){fileCallback.onReceiveValue(null);fileCallback=null;}
        for(FutureTask<Void> task:active.values())task.cancel(true);
        for(Thread thread:requestThreads.values())transport.cancel(thread);
        for(HttpURLConnection c:media)c.disconnect();media.clear();active.clear();network.shutdownNow();cancellation.shutdownNow();
        if(webView!=null){webView.destroy();webView=null;}super.onDestroy();
    }
}
