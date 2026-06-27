package com.mineradio.android;

import android.content.Context;
import android.util.Log;

import org.nodejs.mobile.NodeJsMobile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

public class NodeService {

    private static final String TAG = "MineradioNode";
    private NodeJsMobile nodejs;
    private volatile boolean running = false;
    private final AtomicInteger port = new AtomicInteger(0);

    public interface Callback {
        void onReady();
    }

    public void start(Context context, Callback callback) {
        new Thread(() -> {
            try {
                // Extract Node.js project from assets to internal storage
                File nodeDir = new File(context.getFilesDir(), "nodejs-project");
                extractAssets(context, "nodejs-project", nodeDir);

                // Start Node.js with our server script
                nodejs = new NodeJsMobile(context, (msg) -> {
                    Log.d(TAG, "NodeJS: " + msg);
                    // Parse port from startup message
                    if (msg != null && msg.contains("listening on port")) {
                        try {
                            String[] parts = msg.split("port ");
                            if (parts.length > 1) {
                                port.set(Integer.parseInt(parts[1].trim()));
                            }
                        } catch (Exception e) {
                            port.set(3000);
                        }
                        running = true;
                        if (callback != null) callback.onReady();
                    }
                });

                File scriptFile = new File(nodeDir, "server.js");
                nodejs.startScript(scriptFile.getAbsolutePath());
                running = true;

            } catch (Exception e) {
                Log.e(TAG, "Failed to start Node.js", e);
            }
        }).start();
    }

    public int getPort() {
        return port.get();
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        if (nodejs != null) {
            try { nodejs.stop(); } catch (Exception ignored) {}
            nodejs = null;
            running = false;
        }
    }

    private void extractAssets(Context context, String assetPath, File destDir) throws Exception {
        if (destDir.exists()) return;

        String[] assets = context.getAssets().list(assetPath);
        if (assets == null || assets.length == 0) return;

        destDir.mkdirs();
        for (String asset : assets) {
            String fullPath = assetPath + "/" + asset;
            String[] subAssets = context.getAssets().list(fullPath);
            if (subAssets != null && subAssets.length > 0) {
                extractAssets(context, fullPath, new File(destDir, asset));
            } else {
                File outFile = new File(destDir, asset);
                try (InputStream in = context.getAssets().open(fullPath);
                     FileOutputStream out = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }
            }
        }
    }
}
