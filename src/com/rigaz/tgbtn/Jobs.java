package com.rigaz.tgbtn;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Follows every post the user sent, from the tap until the server is done with it, and decides
 * what the button and the status bubble show. Only the network calls leave the main thread.
 */
final class Jobs {
    private static final long POLL_MS = 1_500;
    private static final long GIVE_UP_MS = 6 * 60_000;
    private static final long DEDUPE_MS = 15_000;
    private static final long HOLD_OK_MS = 6_000;
    private static final long HOLD_FAIL_MS = 10_000;
    private static final long HOLD_NOTICE_MS = 3_500;
    private static final int MAX_MISSES = 6;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private static final class Job {
        final Post post;
        final long startedAt = SystemClock.uptimeMillis();
        String id;
        String chat;
        String state = "sending";
        String detail;
        int misses;
        boolean muted;

        Job(Post post) {
            this.post = post;
        }
    }

    private static final List<Job> active = new ArrayList<Job>();
    /** A result on show for a few seconds; it wins over the progress of running jobs. */
    private static Notice spotlight;
    private static String lastLink;
    private static long lastSentAt;
    private static boolean polling;

    private static final Runnable RENDER = new Runnable() {
        @Override
        public void run() {
            spotlight = null;
            render();
        }
    };

    private static final Runnable POLL = new Runnable() {
        @Override
        public void run() {
            final List<Job> batch = new ArrayList<Job>();
            for (Job j : active) {
                if (j.id != null) batch.add(j);
            }
            if (batch.isEmpty()) {
                polling = false;
                return;
            }
            WORKER.execute(new Runnable() {
                @Override
                public void run() {
                    for (final Job j : batch) {
                        JSONObject state = null;
                        boolean reached = true;
                        try {
                            state = Submit.status(j.id);
                        } catch (Exception e) {
                            reached = false;
                            Log.w(Ttg.LOG, "status failed: " + e);
                        }
                        final JSONObject s = state;
                        final boolean ok = reached;
                        MAIN.post(new Runnable() {
                            @Override
                            public void run() {
                                onStatus(j, s, ok);
                            }
                        });
                    }
                    MAIN.postDelayed(POLL, POLL_MS);
                }
            });
        }
    };

    private Jobs() {
    }

    static void submit(final Post post) {
        for (Job j : active) {
            if (j.post.link.equals(post.link)) {
                notice(new Notice("Sedang diproses", post.label(), "Tunggu sampai selesai", Notice.INFO, false));
                return;
            }
        }
        if (post.link.equals(lastLink) && SystemClock.uptimeMillis() - lastSentAt < DEDUPE_MS) {
            notice(new Notice("Baru saja dikirim", post.label(), null, Notice.INFO, false));
            return;
        }
        final Job job = new Job(post);
        active.add(job);
        render();
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                final Submit.Result result = Submit.send(post);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        onSubmitted(job, result);
                    }
                });
            }
        });
    }

    /** Shows a message that belongs to no job, like "nothing on screen". */
    static void notice(Notice n) {
        show(n, HOLD_NOTICE_MS);
    }

    /** The user tapped the bubble: hide it until there is something new to say. */
    static void dismiss() {
        MAIN.removeCallbacks(RENDER);
        spotlight = null;
        for (Job j : active) j.muted = true;
        render();
    }

    static void render() {
        Activity host = Ttg.host();
        if (host == null) return;
        Notice n = spotlight;
        Job shown = null;
        for (int i = active.size() - 1; i >= 0 && shown == null; i--) {
            if (!active.get(i).muted) shown = active.get(i);
        }
        if (n == null && shown != null) n = describe(shown);

        int button = active.isEmpty() ? FloatButton.IDLE : FloatButton.BUSY;
        if (spotlight != null && spotlight.tone == Notice.OK) button = FloatButton.OK;
        if (spotlight != null && spotlight.tone == Notice.FAIL) button = FloatButton.FAIL;
        FloatButton.setState(host, button);
        if (n == null) StatusBubble.hide(host);
        else StatusBubble.show(host, n);
    }

    private static void onSubmitted(Job job, Submit.Result result) {
        if (result.error != null) {
            Log.w(Ttg.LOG, "submit failed for " + job.post.link + ": " + result.error);
            finish(job, new Notice("Gagal menghubungi server", job.post.label(), result.error, Notice.FAIL, false), HOLD_FAIL_MS);
            return;
        }
        job.chat = result.chat;
        remember(job);
        if (result.job == null) {
            // A server without progress reports: all we know is that it took the link.
            finish(job, new Notice("Terkirim ke server", job.post.label(), "Hasilnya muncul di grup", Notice.OK, false), HOLD_OK_MS);
            return;
        }
        job.id = result.job;
        job.state = "queued";
        render();
        if (!polling) {
            polling = true;
            MAIN.postDelayed(POLL, POLL_MS);
        }
    }

    private static void onStatus(Job job, JSONObject status, boolean reached) {
        if (!active.contains(job)) return;
        if (SystemClock.uptimeMillis() - job.startedAt > GIVE_UP_MS) {
            finish(job, new Notice("Masih diproses", job.post.label(), "Cek hasilnya di grup", Notice.INFO, false), HOLD_OK_MS);
            return;
        }
        if (!reached) {
            if (++job.misses >= MAX_MISSES) {
                finish(job, new Notice("Status tidak tersedia", job.post.label(), "Server tidak menjawab, cek grup", Notice.INFO, false), HOLD_OK_MS);
            }
            return;
        }
        job.misses = 0;
        if (status == null) {
            finish(job, new Notice("Status tidak tersedia", job.post.label(), "Server dimulai ulang, cek grup", Notice.INFO, false), HOLD_OK_MS);
            return;
        }
        String state = status.optString("state");
        if ("done".equals(state)) {
            String chat = status.optString("chat", job.chat);
            String title = chat == null || chat.length() == 0 ? "Terkirim ke grup" : "Terkirim ke " + chat;
            finish(job, new Notice(title, job.post.label(), summary(status), Notice.OK, false), HOLD_OK_MS);
        } else if ("failed".equals(state)) {
            forget(job);
            String reason = status.optString("error");
            finish(job, new Notice("Gagal diunduh", job.post.label(),
                    "Link tersimpan di grup" + (reason.length() > 0 ? "\n" + reason : ""), Notice.FAIL, false), HOLD_FAIL_MS);
        } else {
            job.state = state;
            job.detail = status.optString("detail", null);
            render();
        }
    }

    private static void finish(Job job, Notice n, long hold) {
        active.remove(job);
        show(n, hold);
    }

    private static void show(Notice n, long hold) {
        MAIN.removeCallbacks(RENDER);
        spotlight = n;
        render();
        MAIN.postDelayed(RENDER, hold);
    }

    private static void remember(Job job) {
        lastLink = job.post.link;
        lastSentAt = SystemClock.uptimeMillis();
    }

    /** A failed post must be sendable again right away. */
    private static void forget(Job job) {
        if (job.post.link.equals(lastLink)) lastLink = null;
    }

    private static Notice describe(Job j) {
        String others = active.size() > 1 ? (active.size() - 1) + " lainnya berjalan" : null;
        if ("sending".equals(j.state)) {
            return new Notice("Mengirim ke server…", j.post.label(), others, Notice.INFO, true);
        }
        if ("queued".equals(j.state)) {
            return new Notice("Masuk antrean", j.post.label(), join("Menunggu giliran di server", others), Notice.INFO, true);
        }
        return new Notice("Sedang diproses", j.post.label(), join(j.detail, others), Notice.INFO, true);
    }

    /** The result line: what arrived, how big, how it was fetched and how long it took. */
    private static String summary(JSONObject s) {
        List<String> parts = new ArrayList<String>();
        int count = s.optInt("count");
        int failed = s.optInt("failed");
        if (count > 0) parts.add(count + " gambar" + (failed > 0 ? " (" + failed + " gagal)" : ""));
        long size = s.optLong("size");
        if (size > 0) parts.add(String.format(new Locale("id"), "%.1f MB", size / 1048576.0));
        String method = s.optString("method");
        if (method.length() > 0) parts.add(method);
        int seconds = s.optInt("seconds");
        if (seconds > 0) parts.add(seconds + " detik");
        StringBuilder b = new StringBuilder();
        for (String p : parts) b.append(b.length() > 0 ? " · " : "").append(p);
        return b.length() > 0 ? b.toString() : null;
    }

    private static String join(String a, String b) {
        if (a == null || a.length() == 0) return b;
        return b == null ? a : a + " · " + b;
    }
}
