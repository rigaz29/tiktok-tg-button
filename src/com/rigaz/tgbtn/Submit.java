package com.rigaz.tgbtn;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONObject;

/**
 * Sends a link to tikdl's intake endpoint.
 *
 * The body is signed as HMAC-SHA256(secret, timestamp + "." + body). The secret never leaves
 * the phone, so the request can go over plain HTTP: the server rejects anything unsigned,
 * stale or replayed.
 */
final class Submit {
    private Submit() {
    }

    /** Returns null on success, otherwise a short reason for the toast. */
    static String send(String link) {
        try {
            JSONObject json = new JSONObject();
            json.put("url", link);
            byte[] body = json.toString().getBytes("UTF-8");
            String ts = String.valueOf(System.currentTimeMillis() / 1000L);

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
                if (status == 202 || status == 200) return null;
                InputStream err = conn.getErrorStream();
                String reason = err == null ? "" : new JSONObject(readAll(err)).optString("error");
                return "HTTP " + status + (reason.length() > 0 ? " (" + reason + ")" : "");
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
        }
    }

    private static String sign(String ts, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Config.SECRET.getBytes("UTF-8"), "HmacSHA256"));
        mac.update((ts + ".").getBytes("UTF-8"));
        byte[] sig = mac.doFinal(body);
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
