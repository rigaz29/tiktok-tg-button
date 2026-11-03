package com.rigaz.tgbtn;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry point called from the patched TikTok code.
 *
 * init(Application) is injected at the start of AwemeHostApplication.onCreate. It puts the
 * floating button on the feed and video-detail screens; a tap reads the Aweme on screen
 * (AwemeLocator) and posts its link to tikdl's intake (Submit).
 */
public final class Ttg {
    static final String LOG = "tgbtn";

    private static final String[] HOST_ACTIVITIES = {
            "com.ss.android.ugc.aweme.main.MainActivity",
            "com.ss.android.ugc.aweme.detail.ui.DetailActivity",
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static volatile boolean busy;
    private static boolean installed;
    private static String lastLink;
    private static long lastSentAt;

    private Ttg() {
    }

    public static void init(Application app) {
        if (installed || app == null) return;
        installed = true;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity a) {
                if (!isHost(a)) return;
                try {
                    FloatButton.attach(a);
                    FloatButton.bringToFront(a);
                    FloatButton.setBusy(a, busy);
                } catch (Throwable t) {
                    Log.w(LOG, "attach failed", t);
                }
            }

            @Override
            public void onActivityCreated(Activity a, Bundle b) {
            }

            @Override
            public void onActivityStarted(Activity a) {
            }

            @Override
            public void onActivityPaused(Activity a) {
            }

            @Override
            public void onActivityStopped(Activity a) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity a, Bundle b) {
            }

            @Override
            public void onActivityDestroyed(Activity a) {
            }
        });
        Log.i(LOG, "installed");
    }

    private static boolean isHost(Activity a) {
        String name = a.getClass().getName();
        for (String h : HOST_ACTIVITIES) {
            if (h.equals(name)) return true;
        }
        return false;
    }

    static void onButtonClick(final Activity activity) {
        if (busy) return;
        final Post post;
        try {
            post = AwemeReader.read(AwemeLocator.find(activity));
        } catch (Throwable t) {
            Log.w(LOG, "read failed", t);
            toast(activity, "Video tidak terbaca: " + t.getMessage());
            return;
        }
        if (post == null) {
            toast(activity, "Tidak ada video di layar");
            return;
        }
        final String link = post.link;
        long now = System.currentTimeMillis();
        if (link.equals(lastLink) && now - lastSentAt < 15_000) {
            toast(activity, "Video ini baru saja dikirim");
            return;
        }
        busy = true;
        FloatButton.setBusy(activity, true);
        final Application app = activity.getApplication();
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                final String error = Submit.send(link);
                if (error != null) Log.w(LOG, "submit failed for " + link + ": " + error);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        FloatButton.setBusy(activity, false);
                        if (error == null) {
                            lastLink = link;
                            lastSentAt = System.currentTimeMillis();
                            toast(app, "Dikirim ke bot ✓ hasilnya muncul di grup");
                        } else {
                            toast(app, "Gagal kirim: " + error);
                        }
                    }
                });
            }
        });
    }

    static void toast(android.content.Context ctx, String text) {
        Toast.makeText(ctx.getApplicationContext(), text, Toast.LENGTH_SHORT).show();
    }
}
