package com.rigaz.tgbtn;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the fields the link needs. Aweme and User keep their field names in 47.6.3:
 * Aweme.aid, Aweme.author, Aweme.awemeType, Aweme.shareUrl, Aweme.isTikTokStory, User.uniqueId.
 */
final class AwemeReader {
    private static final int TYPE_IMAGE = 2;        // Aweme.isImage()
    private static final int TYPE_STORY_GROUP = 40; // the feed cell that holds a user's stories
    private static final int TYPE_LIVE = 101;       // Aweme.isLive()
    private static final int TYPE_PHOTO_MODE = 150; // Aweme.isPhotoMode()

    private AwemeReader() {
    }

    /** Returns null when there is no Aweme; throws with a readable reason for unsupported items. */
    static Post read(Object aweme) throws Exception {
        if (aweme == null) return null;
        int type = intField(aweme, "awemeType");
        if (type == TYPE_LIVE) throw new Exception("siaran LIVE tidak bisa diunduh");
        // The group's own aid belongs to no post. The story on screen comes from the pager
        // inside this cell, so getting here means that pager has nothing yet.
        if (type == TYPE_STORY_GROUP) throw new Exception("story belum siap, coba lagi");

        String aid = stringField(aweme, "aid");
        if (aid == null || aid.length() == 0) throw new Exception("ID video kosong");

        Object author = field(aweme, "author");
        String uniqueId = author == null ? null : stringField(author, "uniqueId");
        boolean story = Boolean.TRUE.equals(optField(aweme, "isTikTokStory"));
        List<String> images = imageUrls(aweme);
        boolean photo = type == TYPE_PHOTO_MODE || type == TYPE_IMAGE || !images.isEmpty();

        Post p = new Post();
        p.id = aid;
        p.author = uniqueId;
        Object desc = optField(aweme, "desc");
        if (desc instanceof String) p.desc = (String) desc;
        Object time = optField(aweme, "createTime");
        if (time instanceof Long) p.createTime = (Long) time;
        p.kind = story ? Post.STORY : photo ? Post.PHOTO : Post.VIDEO;
        if (uniqueId != null && uniqueId.length() > 0) {
            p.link = "https://www.tiktok.com/@" + uniqueId + (photo ? "/photo/" : "/video/") + aid;
        } else {
            // No username: fall back to the link the share sheet would copy.
            String share = stringField(aweme, "shareUrl");
            if (share == null || share.length() == 0) throw new Exception("link video tidak tersedia");
            p.link = share;
        }
        if (photo) p.images = images;
        else if (story) p.video = videoUrl(aweme);
        return p;
    }

    private static String videoUrl(Object aweme) {
        Object video;
        try {
            video = aweme.getClass().getMethod("getVideo").invoke(aweme);
        } catch (Exception e) {
            return null;
        }
        if (video == null) return null;
        for (String name : new String[]{"h264PlayAddrValue", "downloadNoWatermarkAddr", "playAddrValue"}) {
            String url = firstUrl(optField(video, name));
            if (url != null) return url;
        }
        return null;
    }

    private static List<String> imageUrls(Object aweme) {
        List<String> out = new ArrayList<String>();
        Object info = optField(aweme, "photoModeImageInfo");
        Object list = info == null ? null : optField(info, "imageList");
        if (!(list instanceof List) || ((List<?>) list).isEmpty()) list = optField(aweme, "imageInfos");
        if (!(list instanceof List)) return out;
        for (Object item : (List<?>) list) {
            for (String name : new String[]{"displayImageNoWatermark", "labelLarge", "targetMultiRateImageUrl",
                    "ownerWatermarkImage", "userWatermarkImage"}) {
                String url = firstUrl(optField(item, name));
                if (url != null) {
                    out.add(url);
                    break;
                }
            }
        }
        return out;
    }

    /** First https entry of a UrlModel. Photos list HEIC first, so a later format is preferred. */
    private static String firstUrl(Object urlModel) {
        Object list = urlModel == null ? null : optField(urlModel, "urlList");
        if (!(list instanceof List)) return null;
        String heic = null;
        for (Object entry : (List<?>) list) {
            if (!(entry instanceof String) || !((String) entry).startsWith("https://")) continue;
            String url = (String) entry;
            if (!url.contains(".heic")) return url;
            if (heic == null) heic = url;
        }
        return heic;
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

    private static Object optField(Object obj, String name) {
        try {
            return field(obj, name);
        } catch (Exception e) {
            return null;
        }
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
