package com.rigaz.tgbtn;

import java.util.List;

/** What the button sends for the Aweme on screen. */
final class Post {
    static final String VIDEO = "Video";
    static final String PHOTO = "Foto";
    static final String STORY = "Story";

    String id;
    /** Canonical web link, e.g. https://www.tiktok.com/@user/video/123; tikdl accepts /video/ and /photo/. */
    String link;
    String author;
    String desc;
    long createTime;
    /** One of the constants above; this is shown to the user. */
    String kind;
    /** Direct media of a story. The web link can fail for those, so the server falls back to this. */
    String video;
    List<String> images;

    boolean isStory() {
        return STORY.equals(kind);
    }

    /** One line for the status bubble: who posted it, what it is, and how it starts. */
    String label() {
        StringBuilder b = new StringBuilder();
        if (author != null && author.length() > 0) b.append('@').append(author).append(" · ");
        b.append(kind);
        if (desc != null && desc.trim().length() > 0) b.append(" · ").append(desc.trim().replace('\n', ' '));
        return b.toString();
    }
}
