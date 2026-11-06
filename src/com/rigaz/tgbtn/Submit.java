package com.rigaz.tgbtn;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import android.util.Log;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Talks to tikdl's intake endpoint.
 *
 * Requests are signed as HMAC-SHA256(secret, timestamp + "." + payload), where the payload is the
 * body of a submit or "status:<id>" for a status read. The secret never leaves the phone, so the
 * request can go over plain HTTP: the server rejects anything unsigned or stale, and a submit
 * that is replayed.
 */
final class Submit {
    /** What the server said about a submit. A null job means it does not report progress. */
    static final class Result {
        String error;
        String job;
        String chat;
    }

    private Submit() {
    }

    static Result send(Post post) {
        Result result = new Result();
        try {
            byte[] body = body(post).toString().getBytes("UTF-8");
            String ts = timestamp();

            HttpURLConnection conn = (HttpURLConnection) new URL(Config.SERVER_URL + "/submit").openConnection();
            try {
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(15_000);
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setFixedLengthStreamingMode(body.length);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-Timestamp", ts);
                conn.setRequestProperty("X-Signature", sign(ts, body));
                OutputStream out = conn.getOutputStream();
                out.write(body);
                out.close();

                int status = conn.getResponseCode();
                if (status == 202 || status == 200) {
                    JSONObject reply = new JSONObject(readAll(conn.getInputStream()));
                    result.job = reply.optString("job", null);
                    result.chat = reply.optString("chat", null);
                    return result;
                }
                InputStream err = conn.getErrorStream();
                String reason = err == null ? "" : new JSONObject(readAll(err)).optString("error");
                result.error = "HTTP " + status + (reason.length() > 0 ? " (" + reason + ")" : "");
            } finally {
                conn.disconnect();
            }
        } catch (IOException e) {
            Log.w(Ttg.LOG, "submit failed", e);
            result.error = "Cek internet dan pastikan bot di server berjalan";
        } catch (Exception e) {
            result.error = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
        }
        return result;
    }

    /** Returns the job's state, or null when the server no longer knows it. */
    static JSONObject status(String job) throws Exception {
        String ts = timestamp();
        HttpURLConnection conn = (HttpURLConnection) new URL(Config.SERVER_URL + "/status/" + job).openConnection();
        try {
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(8_000);
            conn.setRequestProperty("X-Timestamp", ts);
            conn.setRequestProperty("X-Signature", sign(ts, ("status:" + job).getBytes("UTF-8")));
            int code = conn.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new IOException("HTTP " + code);
            return new JSONObject(readAll(conn.getInputStream()));
        } finally {
            conn.disconnect();
        }
    }

    private static JSONObject body(Post post) throws Exception {
        JSONObject json = new JSONObject();
        json.put("url", post.link);
        if (post.isStory() && (post.video != null || post.images != null)) {
            JSONObject media = new JSONObject();
            media.put("id", post.id);
            media.put("author", post.author);
            media.put("desc", post.desc);
            media.put("time", post.createTime);
            media.put("video", post.video);
            if (post.images != null) media.put("images", new JSONArray(post.images));
            json.put("media", media);
        }
        return json;
    }

    private static String timestamp() {
        return String.valueOf(System.currentTimeMillis() / 1000L);
    }

    private static String sign(String ts, byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Config.SECRET.getBytes("UTF-8"), "HmacSHA256"));
        mac.update((ts + ".").getBytes("UTF-8"));
        byte[] sig = mac.doFinal(payload);
        StringBuilder hex = new StringBuilder(sig.length * 2);
        for (byte b : sig) hex.append(String.format("%02x", b & 0xff));
        return hex.toString();
    }

    private static String readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } finally {
            in.close();
        }
    }
}
