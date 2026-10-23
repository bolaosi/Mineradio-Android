package com.mineradio.android;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;

import com.mineradio.android.music.HttpTransport;
import com.mineradio.android.music.MusicApi;
import com.mineradio.android.music.NeteaseClient;
import com.mineradio.android.music.QqLoginHandoff;
import com.mineradio.android.music.QqLoginPolicy;

import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Official QQ UI in :qqlogin only; the main app WebView never shares this cookie store. */
public final class QQLoginActivity extends Activity {
    public static final String EXTRA_REQUEST_ID = "qq_request_id";
    public static final String EXTRA_ACCOUNT_JSON = "qq_account_json";
    public static final String EXTRA_ERROR = "qq_error";
    public static final String ACTION_CANCEL = "com.mineradio.android.CANCEL_QQ_LOGIN";
    public static final String CONTROL_PERMISSION = "com.mineradio.android.permission.QQ_LOGIN_CONTROL";
    private static boolean dataDirectorySelected;
    private static final String LOGIN_CLICK = "(function(){var a=document.querySelectorAll('a,button');for(var i=0;i<a.length;i++){var s=(a[i].textContent||'').trim();var r=a[i].getBoundingClientRect();if(/^(登录|立即登录|QQ登录)$/.test(s)&&r.width>0&&r.height>0){a[i].click();return true;}}return false;})()";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final HttpTransport transport = new HttpTransport();
    private final List<WebView> windows = new ArrayList<>();
    private FrameLayout webContainer;
    private TextView status;
    private Button externalButton;
    private WebView primary;
    private CookieManager cookieManager;
    private String requestId = "", lastFingerprint = "";
    private Uri pendingExternal;
    private boolean finished, successful, verifying, receiverRegistered;
    private Future<?> verification;
    private volatile Thread verificationThread;
    private final Runnable poll = new Runnable() { public void run() { if (!finished) { inspectCookies(false); main.postDelayed(this, 1600); } } };
    private final BroadcastReceiver cancellation = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (ACTION_CANCEL.equals(intent.getAction()) && requestId.equals(intent.getStringExtra(EXTRA_REQUEST_ID))) cancelWith("QQ_LOGIN_CANCELLED");
        }
    };

    public static File handoffDirectory(Context context) { return new File(context.getNoBackupFilesDir(), "qq-login-handoff"); }
    public static String takeCookie(Context context, String requestId) throws Exception { return QqLoginHandoff.take(handoffDirectory(context), requestId, System.currentTimeMillis()); }
    public static void discardCookie(Context context, String requestId) {
        if (!QqLoginPolicy.validRequestId(requestId)) return;
        try { QqLoginHandoff.discard(handoffDirectory(context), requestId); } catch (Exception ignored) { }
    }
    public static void cancel(Context context, String requestId) {
        if (!QqLoginPolicy.validRequestId(requestId)) return;
        context.sendBroadcast(new Intent(ACTION_CANCEL).setPackage(context.getPackageName()).putExtra(EXTRA_REQUEST_ID, requestId), CONTROL_PERMISSION);
        discardCookie(context, requestId);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        requestId = getIntent().getStringExtra(EXTRA_REQUEST_ID);
        setResult(RESULT_CANCELED, result("QQ_LOGIN_CANCELLED"));
        if (!QqLoginPolicy.validRequestId(requestId)) { setResult(RESULT_CANCELED, result("INVALID_QQ_REQUEST")); finish(); return; }
        buildChrome();
        IntentFilter filter = new IntentFilter(ACTION_CANCEL);
        ContextCompat.registerReceiver(this, cancellation, filter, CONTROL_PERMISSION, main, ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
        if (Build.VERSION.SDK_INT < 28) {
            compatibility("当前 Android 版本无法可靠隔离 QQ 登录会话。请返回使用 Cookie 导入；网易云和本地音乐不受影响。", "QQ_AUTOMATIC_REQUIRES_ANDROID_9"); return;
        }
        if (!(getPackageName() + ":qqlogin").equals(Application.getProcessName())) {
            compatibility("QQ 登录进程隔离未启用，请返回使用 Cookie 导入。", "QQ_PROCESS_ISOLATION_REQUIRED"); return;
        }
        try {
            if (!dataDirectorySelected) { WebView.setDataDirectorySuffix("mineradio-qq-login"); dataDirectorySelected = true; }
            WebView.setWebContentsDebuggingEnabled(false);
            QqLoginHandoff.cleanExpired(handoffDirectory(this), System.currentTimeMillis());
            primary = createWebView(); webContainer.addView(primary);
            cookieManager = CookieManager.getInstance(); cookieManager.setAcceptCookie(true);
            // This clears only the suffix selected above in the dedicated process.
            cookieManager.removeAllCookies(ignored -> {
                if (finished) return;
                WebStorage.getInstance().deleteAllData();
                status.setText("请在 QQ 音乐官方页面点登录并完成授权；同屏可使用官网提供的 QQ 登录方式。扫码可用另一台设备。完成后自动检测，也可点验证。");
                primary.loadUrl(QqLoginPolicy.ENTRY_URL);
            });
            main.postDelayed(() -> { if (!finished) cancelWith("QQ_LOGIN_TIMEOUT"); }, 5 * 60 * 1000);
        } catch (Exception ignored) { compatibility("无法初始化隔离登录窗口，请返回使用 Cookie 导入。", "QQ_WEBVIEW_ISOLATION_FAILED"); }
    }
    private Intent result(String error) { Intent intent = new Intent().putExtra(EXTRA_REQUEST_ID, requestId); if (error != null) intent.putExtra(EXTRA_ERROR, error); return intent; }
    private void buildChrome() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(18, 27, 22));
        status = new TextView(this); status.setTextColor(Color.WHITE); status.setTextSize(14); status.setPadding(dp(14), dp(12), dp(14), dp(8)); status.setText("正在准备隔离的 QQ 官方登录窗口…"); root.addView(status);
        LinearLayout actions = new LinearLayout(this);
        Button show = button("显示官网登录", () -> {
            WebView current = topWindow();
            if (current != null && QqLoginPolicy.allowPage(current.getUrl()) && Uri.parse(current.getUrl()).getHost().equals("y.qq.com")) {
                current.evaluateJavascript(LOGIN_CLICK, value -> { if (!"true".equals(value)) status.setText("请在网页中点“登录”；也可缩放页面查找官网入口。不能绕过官方验证码或风险校验。"); });
            }
        });
        Button check = button("验证并完成", () -> inspectCookies(true));
        Button cancel = button("取消", () -> cancelWith("QQ_LOGIN_CANCELLED"));
        actions.addView(show, new LinearLayout.LayoutParams(0, dp(48), 1)); actions.addView(check, new LinearLayout.LayoutParams(0, dp(48), 1)); actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1)); root.addView(actions);
        externalButton = button("打开 QQ 并返回继续", this::openExternalQq); externalButton.setVisibility(View.GONE); root.addView(externalButton);
        webContainer = new FrameLayout(this); root.addView(webContainer, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
    }
    private Button button(String text, Runnable action) { Button button = new Button(this); button.setText(text); button.setTextSize(12); button.setOnClickListener(view -> action.run()); return button; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void compatibility(String text, String error) { status.setText(text); setResult(RESULT_CANCELED, result(error)); }
    private WebView topWindow() { return windows.isEmpty() ? null : windows.get(windows.size() - 1); }
    private WebView createWebView() {
        WebView view = new WebView(this); windows.add(view);
        WebSettings settings = view.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false); settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(true); settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setUseWideViewPort(true); settings.setLoadWithOverviewMode(true); settings.setBuiltInZoomControls(true); settings.setDisplayZoomControls(false);
        // Match the official desktop OAuth flow inspected in a fresh Chrome session.
        // A mobile UA can require an unavailable QQ WebView SDK bridge instead.
        settings.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        if (Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
        view.setDownloadListener((url, agent, disposition, type, length) -> status.setText("登录窗口不下载文件，请继续官网授权或返回使用 Cookie 导入。"));
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView source, WebResourceRequest request) { return navigate(source, request.getUrl().toString()); }
            @Override public boolean shouldOverrideUrlLoading(WebView source, String url) { return navigate(source, url); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView source, WebResourceRequest request) {
                boolean allowed = request.isForMainFrame() ? QqLoginPolicy.allowPage(request.getUrl().toString()) : QqLoginPolicy.allowResource(request.getUrl().toString());
                if (allowed) return null;
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", java.util.Collections.emptyMap(), new ByteArrayInputStream("Blocked outside QQ login policy".getBytes(StandardCharsets.UTF_8)));
            }
            @Override public void onPageFinished(WebView source, String url) {
                if (!QqLoginPolicy.allowPage(url) || finished) return;
                if ("y.qq.com".equals(Uri.parse(url).getHost()) && source == primary) main.postDelayed(() -> {
                    if (!finished && QqLoginPolicy.allowPage(source.getUrl()) && "y.qq.com".equals(Uri.parse(source.getUrl()).getHost())) source.evaluateJavascript(LOGIN_CLICK, null);
                }, 900);
                inspectCookies(false);
            }
            @Override public void onReceivedSslError(WebView source, SslErrorHandler handler, SslError error) { handler.cancel(); status.setText("QQ 官方页面的安全连接未通过验证，请检查网络后重试；不会跳过证书检查。"); }
            @Override public void onReceivedError(WebView source, WebResourceRequest request, WebResourceError error) { if (request.isForMainFrame() && !finished) status.setText("QQ 官方页面加载失败，请检查网络。可返回重试或使用 Cookie 导入。"); }
            @Override public void onReceivedHttpError(WebView source, WebResourceRequest request, WebResourceResponse response) { if (request.isForMainFrame() && !finished) status.setText("QQ 官方页面返回 HTTP " + response.getStatusCode() + "，尚未完成登录；请重试或使用 Cookie 导入。"); }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) { return true; }
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public boolean onCreateWindow(WebView source, boolean dialog, boolean gesture, Message resultMessage) {
                if (finished || !QqLoginPolicy.allowPage(source.getUrl()) || windows.size() >= 3) return false;
                WebView popup = createWebView(); webContainer.addView(popup);
                ((WebView.WebViewTransport) resultMessage.obj).setWebView(popup); resultMessage.sendToTarget(); return true;
            }
            @Override public void onCloseWindow(WebView window) { if (window != primary) { windows.remove(window); webContainer.removeView(window); window.destroy(); } }
        });
        return view;
    }
    private boolean navigate(WebView source, String url) {
        if (QqLoginPolicy.allowPage(url)) return false;
        if (QqLoginPolicy.allowPage(source.getUrl()) && QqLoginPolicy.allowQqApp(url)) {
            pendingExternal = Uri.parse(url); externalButton.setVisibility(View.VISIBLE);
            status.setText("官网请求打开已安装的 QQ。请点上方按钮确认，授权后返回；只有会话回到本窗口并通过官方验证才算完成。");
        } else status.setText("已阻止离开受限的 QQ 官方登录页面；尚未完成登录。请继续官网授权或使用 Cookie 导入。");
        return true;
    }
    private void openExternalQq() {
        if (pendingExternal == null || !QqLoginPolicy.allowQqApp(pendingExternal.toString())) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, pendingExternal).setPackage("com.tencent.mobileqq")); status.setText("请在 QQ 官方应用完成授权后返回本窗口，再点“验证并完成”。"); }
        catch (Exception ignored) { status.setText("未能打开 QQ 官方应用。请使用网页提供的账号/扫码登录，或返回 Cookie 导入。"); }
    }
    private void inspectCookies(boolean manual) {
        if (finished || verifying || cookieManager == null) return;
        try {
            List<String> values = new ArrayList<>(); for (String url : QqLoginPolicy.COOKIE_URLS) values.add(cookieManager.getCookie(url));
            final String candidate = QqLoginPolicy.mergeMusicCookies(values);
            if (!QqLoginPolicy.hasCandidate(candidate)) { if (manual) status.setText("尚未取得 QQ 音乐登录会话。请先完成官网登录/授权；打开网页或 QQ 应用本身不算登录完成。"); return; }
            String fingerprint = fingerprint(candidate); if (!manual && fingerprint.equals(lastFingerprint)) return; lastFingerprint = fingerprint;
            verifying = true; status.setText("正在用 QQ 官方当前账号接口验证此登录会话…");
            verification = executor.submit(() -> {
                verificationThread = Thread.currentThread();
                try {
                    Map<String, String> temporary = new LinkedHashMap<>();
                    MusicApi api = new MusicApi(new MusicApi.Store() { public String get(String key) { return temporary.get(key); } public void put(String key, String value) { temporary.put(key, value); } }, new NeteaseClient(transport), transport);
                    MusicApi.ApiResponse response = api.route("/api/qq/login/cookie", "POST", new JSONObject().put("cookie", candidate).toString());
                    if (Thread.currentThread().isInterrupted()) return;
                    main.post(() -> {
                        verifying = false;
                        if (finished) return;
                        if (response.status != 200 || !response.body.optBoolean("saved") || !response.body.optBoolean("verified") || !response.body.optBoolean("loggedIn")) {
                            status.setText("QQ 官方账号验证未通过（" + response.status + "），未保存登录。请完成官网授权后再验证；验证码和风险校验需要由本人在官网处理。"); return;
                        }
                        if (!manual && !response.body.optBoolean("playbackKeyReady")) { status.setText("QQ 账号已验证，但音乐播放授权尚未生成。请继续完成官网授权；或点“验证并完成”仅同步已验证账号。"); return; }
                        try {
                            JSONObject metadata = metadata(response.body);
                            String validated = temporary.get("qq.cookie");
                            QqLoginHandoff.write(handoffDirectory(this), requestId, validated, metadata, System.currentTimeMillis());
                            successful = true; finished = true; stopVerification();
                            setResult(RESULT_OK, result(null).putExtra(EXTRA_ACCOUNT_JSON, metadata.toString()));
                            eraseAndFinish();
                        } catch (Exception ignored) { status.setText("无法安全交接已验证会话，未向主界面报告成功。请返回重试。"); }
                    });
                } catch (Exception ignored) { main.post(() -> { verifying = false; if (!finished) status.setText("QQ 会话验证失败，请检查网络后重试；尚未保存登录。"); }); }
                finally { verificationThread = null; }
            });
        } catch (Exception ignored) { status.setText("无法读取此隔离登录窗口的有效会话，请重新登录或返回 Cookie 导入。"); }
    }
    private static JSONObject metadata(JSONObject source) throws Exception {
        JSONObject result = new JSONObject().put("provider", "qq").put("loggedIn", true).put("verified", true).put("userId", source.optString("userId"));
        for (String key : new String[]{"nickname", "avatar", "vipType", "vipLevel", "isVip", "isSvip", "playbackKeyReady", "profileSource"}) if (source.has(key)) result.put(key, source.opt(key));
        return result;
    }
    private static String fingerprint(String value) throws Exception { byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder out = new StringBuilder(); for (byte item : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", item & 255)); return out.toString(); }
    private void stopVerification() { main.removeCallbacks(poll); if (verification != null) verification.cancel(true); Thread worker = verificationThread; if (worker != null) transport.cancel(worker); }
    private void cancelWith(String error) { if (finished) return; finished = true; stopVerification(); discardCookie(this, requestId); setResult(RESULT_CANCELED, result(error)); eraseAndFinish(); }
    private void eraseAndFinish() {
        main.removeCallbacksAndMessages(null);
        if (cookieManager == null) { finish(); return; }
        final boolean[] returned = {false}; Runnable done = () -> { if (returned[0]) return; returned[0] = true; finish(); };
        try { WebStorage.getInstance().deleteAllData(); cookieManager.removeAllCookies(ignored -> { cookieManager.flush(); done.run(); }); main.postDelayed(done, 1200); }
        catch (Exception ignored) { done.run(); }
    }
    @Override protected void onResume() { super.onResume(); if (!finished && cookieManager != null) { main.removeCallbacks(poll); main.post(poll); } }
    @Override protected void onPause() { main.removeCallbacks(poll); super.onPause(); }
    @Override public void onBackPressed() { cancelWith("QQ_LOGIN_CANCELLED"); }
    @Override protected void onDestroy() {
        finished = true;
        stopVerification(); main.removeCallbacksAndMessages(null); executor.shutdownNow();
        if (receiverRegistered) { unregisterReceiver(cancellation); receiverRegistered = false; }
        if (!successful) discardCookie(this, requestId);
        if (webContainer != null) webContainer.removeAllViews();
        for (WebView view : new ArrayList<>(windows)) { view.stopLoading(); view.setWebChromeClient(null); view.setWebViewClient(new WebViewClient()); view.destroy(); } windows.clear();
        if (cookieManager != null) cookieManager.removeAllCookies(null);
        super.onDestroy();
    }
}
