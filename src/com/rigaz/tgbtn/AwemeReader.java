package com.rigaz.tgbtn;

import java.lang.reflect.Field;

/**
 * Reads the fields the link needs. Aweme and User keep their field names in 46.4.3:
 * Aweme.aid, Aweme.author, Aweme.awemeType, Aweme.shareUrl, User.uniqueId.
 */
final class AwemeReader {
    private static final int TYPE_IMAGE = 2;        // Aweme.isImage()
    private static final int TYPE_LIVE = 101;       // Aweme.isLive()
    private static final int TYPE_PHOTO_MODE = 150; // Aweme.isPhotoMode()

    private AwemeReader() {
    }

    /** Returns null when there is no Aweme; throws with a readable reason for unsupported items. */
    static Post read(Object aweme) throws Exception {
        if (aweme == null) return null;
        int type = intField(aweme, "awemeType");
        if (type == TYPE_LIVE) throw new Exception("siaran LIVE tidak bisa diunduh");

        String aid = stringField(aweme, "aid");
        if (aid == null || aid.length() == 0) throw new Exception("ID video kosong");

        Object author = field(aweme, "author");
        String uniqueId = author == null ? null : stringField(author, "uniqueId");

        Post p = new Post();
        p.id = aid;
        if (uniqueId != null && uniqueId.length() > 0) {
            p.link = "https://www.tiktok.com/@" + uniqueId
                    + (type == TYPE_PHOTO_MODE || type == TYPE_IMAGE ? "/photo/" : "/video/") + aid;
        } else {
            // No username: fall back to the link the share sheet would copy.
            String share = stringField(aweme, "shareUrl");
            if (share == null || share.length() == 0) throw new Exception("link video tidak tersedia");
            p.link = share;
        }
        return p;
    }

    private static Object field(Object obj, String name) throws Exception {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(obj.getClass().getName() + "." + name);
    }

    private static String stringField(Object obj, String name) throws Exception {
        Object v = field(obj, name);
        return v instanceof String ? (String) v : null;
    }

    private static int intField(Object obj, String name) throws Exception {
        Object v = field(obj, name);
        return v instanceof Integer ? (Integer) v : 0;
    }
}
