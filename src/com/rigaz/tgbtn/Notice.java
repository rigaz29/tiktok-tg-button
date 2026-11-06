package com.rigaz.tgbtn;

/** What the status bubble shows. */
final class Notice {
    static final int INFO = 0;
    static final int OK = 1;
    static final int FAIL = 2;

    final String title;
    final String subtitle;
    final String detail;
    final int tone;
    final boolean progress;

    Notice(String title, String subtitle, String detail, int tone, boolean progress) {
        this.title = title;
        this.subtitle = subtitle;
        this.detail = detail;
        this.tone = tone;
        this.progress = progress;
    }
}
