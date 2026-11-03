package com.ss.android.ugc.aweme.main;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import com.ss.android.ugc.aweme.common.widget.VerticalViewPager;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.profile.model.User;

/** Fake feed: a full-screen pager whose adapter returns Awemes; tapping the label pages forward. */
public class MainActivity extends Activity {
    public static final class FeedAdapter {
        private final Aweme[] items;

        FeedAdapter(Aweme[] items) {
            this.items = items;
        }

        public Aweme getItem(int i) {
            return items[i];
        }
    }

    private static Aweme aweme(String aid, String user, int type) {
        Aweme a = new Aweme();
        a.aid = aid;
        a.awemeType = type;
        a.author = new User();
        a.author.uniqueId = user;
        return a;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final Aweme[] items = {
                aweme("6718335390845095173", "scout2015", 0),
                aweme("7520000000000000000", "photo.user", 150),
        };
        final VerticalViewPager pager = new VerticalViewPager(this);
        pager.setAdapter(new FeedAdapter(items));
        final TextView label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setTextSize(22);
        label.setGravity(Gravity.CENTER);
        label.setBackgroundColor(0xFF202020);
        label.setText("item 0: @scout2015 video\n(tap to page)");
        label.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int next = (pager.getCurrentItem() + 1) % items.length;
                pager.setCurrentItem(next);
                label.setText("item " + next + ": @" + items[next].author.uniqueId
                        + (items[next].awemeType == 150 ? " photo" : " video") + "\n(tap to page)");
            }
        });
        pager.addView(label);
        setContentView(pager);
    }
}
