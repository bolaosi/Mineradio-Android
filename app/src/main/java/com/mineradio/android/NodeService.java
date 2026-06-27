package com.mineradio.android;

import android.content.Context;
import android.util.Log;
import java.util.concurrent.atomic.AtomicInteger;

public class NodeService {

    private static final String TAG = "MineradioNode";
    private volatile boolean running = false;
    private final AtomicInteger port = new AtomicInteger(0);

    public interface Callback {
        void onReady();
    }

    public void start(Context context, Callback callback) {
        // Node.js backend not available on CI build
        // Music will play via web app own audio handling
        port.set(0);
        running = false;
        Log.i(TAG, "Node.js backend disabled in this build");
        if (callback != null) callback.onReady();
    }

    public int getPort() { return port.get(); }
    public boolean isRunning() { return running; }
    public void stop() { running = false; }
}
