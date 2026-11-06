package com.rigaz.tgbtn;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import java.lang.ref.WeakReference;

/**
 * Entry point called from the patched TikTok code.
 *
 * init(Application) is injected at the start of AwemeHostApplication.onCreate. It puts the
 * floating button on the feed and video-detail screens; a tap reads the Aweme on screen
 * (AwemeLocator) and hands it to Jobs, which posts it to tikdl and reports the progress.
 */
public final class Ttg {
    static final String LOG = "tgbtn";

    private static final String[] HOST_ACTIVITIES = {
            "com.ss.android.ugc.aweme.main.MainActivity",
            "com.ss.android.ugc.aweme.detail.ui.DetailActivity",
            "com.ss.android.ugc.aweme.story.feed.immersive.gallery.StoryGalleryActivity",
    };

    private static boolean installed;
    private static int started;
    private static WeakReference<Activity> host = new WeakReference<Activity>(null);

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
                    host = new WeakReference<Activity>(a);
                    FloatButton.attach(a);
                    FloatButton.place(a);
                    Jobs.render();
                } catch (Throwable t) {
                    Log.w(LOG, "attach failed", t);
                }
            }

            @Override
            public void onActivityPaused(Activity a) {
                if (host.get() == a) host.clear();
            }

            @Override
            public void onActivityStarted(Activity a) {
                started++;
            }

            @Override
            public void onActivityStopped(Activity a) {
                started--;
                // Nothing left on screen means the app went to the background. The button is
                // back at its default spot the next time the app is opened.
                if (started <= 0 && !a.isChangingConfigurations()) {
                    started = 0;
                    FloatButton.resetPosition();
                }
            }

            @Override
            public void onActivityCreated(Activity a, Bundle b) {
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

    /** The screen the button is showing on right now, if any. */
    static Activity host() {
        return host.get();
    }

    /** Walks the superclasses: playlists, series and story pushes all subclass DetailActivity. */
    private static boolean isHost(Activity a) {
        for (Class<?> c = a.getClass(); c != null && c != Activity.class; c = c.getSuperclass()) {
            for (String h : HOST_ACTIVITIES) {
                if (h.equals(c.getName())) return true;
            }
        }
        return false;
    }

    static void onButtonClick(Activity activity) {
        Post post;
        try {
            post = AwemeReader.read(AwemeLocator.find(activity));
        } catch (Throwable t) {
            Log.w(LOG, "read failed", t);
            String reason = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            Jobs.notice(new Notice("Tidak bisa dikirim", null, reason, Notice.FAIL, false));
            return;
        }
        if (post == null) {
            Jobs.notice(new Notice("Tidak ada video di layar", null, null, Notice.INFO, false));
            return;
        }
        Log.i(LOG, post.kind + " " + post.link + " media=" + (post.video != null ? "video"
                : post.images != null ? post.images.size() + " images" : "none"));
        Jobs.submit(post);
    }
}
