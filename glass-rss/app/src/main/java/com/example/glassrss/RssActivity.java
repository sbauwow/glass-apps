package com.example.glassrss;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Main RSS reader activity for Google Glass.
 * Displays feed items as horizontally swipeable cards.
 */
public class RssActivity extends Activity {

    private static final String TAG = "GlassRSS";

    private static final float SWIPE_THRESHOLD = 100f;
    private static final long TAP_TIMEOUT_MS = 300;
    private static final long LONG_PRESS_MS = 800;
    private static final long REFRESH_INTERVAL_MS = 15 * 60 * 1000; // 15 min

    private HorizontalScrollView scrollView;
    private LinearLayout cardContainer;
    private TextView statusText;
    private TextView statusSource;
    private TextView statusPosition;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<FeedItem> items = new ArrayList<FeedItem>();
    private int selectedIndex = 0;

    // Gesture tracking
    private float downX, downY;
    private long downTime;

    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault());

    private static String relativeTime(long timestamp) {
        if (timestamp <= 0) return "";
        long diff = System.currentTimeMillis() - timestamp;
        if (diff < 0) return "now";
        long mins = diff / 60000;
        if (mins < 1) return "now";
        if (mins < 60) return mins + "m ago";
        long hours = mins / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        if (days < 7) return days + "d ago";
        return new SimpleDateFormat("MMM d", Locale.US).format(new java.util.Date(timestamp));
    }

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            loadFeeds();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rss);

        scrollView = (HorizontalScrollView) findViewById(R.id.scroll_view);
        cardContainer = (LinearLayout) findViewById(R.id.card_container);
        statusText = (TextView) findViewById(R.id.status_text);
        statusSource = (TextView) findViewById(R.id.status_source);
        statusPosition = (TextView) findViewById(R.id.status_position);

        statusText.setVisibility(View.VISIBLE);
        statusText.setText(R.string.loading);

        loadFeeds();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshRunnable);
    }

    private void loadFeeds() {
        FeedManager.fetchAll(new FeedManager.FeedCallback() {
            @Override
            public void onFeedsLoaded(final List<FeedItem> feedItems) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        items = feedItems;
                        if (selectedIndex >= items.size()) {
                            selectedIndex = 0;
                        }
                        populateCards();
                    }
                });
            }

            @Override
            public void onError(final String message) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        statusText.setVisibility(View.VISIBLE);
                        statusText.setText("Error: " + message);
                    }
                });
            }
        });
    }

    private void populateCards() {
        cardContainer.removeAllViews();

        if (items.isEmpty()) {
            statusText.setVisibility(View.VISIBLE);
            statusText.setText(R.string.no_items);
            statusSource.setText("");
            statusPosition.setText("");
            return;
        }

        statusText.setVisibility(View.GONE);

        // Compute card width from actual screen pixels — Glass is hdpi (density
        // 240) so hard-coded dp values get scaled by 1.5x and overflow 640px.
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int marginPx = getResources().getDimensionPixelSize(R.dimen.card_margin);
        int padPx = getResources().getDimensionPixelSize(R.dimen.card_padding_h);
        int cardWidthPx = screenWidth - (2 * marginPx);
        int maxTextWidthPx = cardWidthPx - (2 * padPx);

        LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < items.size(); i++) {
            FeedItem item = items.get(i);
            View card = inflater.inflate(R.layout.item_card, cardContainer, false);

            // Override card width from XML with computed pixel value
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
            lp.width = cardWidthPx;
            card.setLayoutParams(lp);

            TextView source = (TextView) card.findViewById(R.id.card_source);
            TextView title = (TextView) card.findViewById(R.id.card_title);
            TextView date = (TextView) card.findViewById(R.id.card_date);
            TextView desc = (TextView) card.findViewById(R.id.card_desc);

            title.setMaxWidth(maxTextWidthPx);
            desc.setMaxWidth(maxTextWidthPx);

            source.setText(item.getSource());
            title.setText(item.getTitle());

            String timeStr = relativeTime(item.getTimestamp());
            if (timeStr.length() > 0) {
                date.setText(timeStr);
            } else {
                date.setVisibility(View.GONE);
            }

            String description = item.getDescription();
            if (description.length() > 0) {
                desc.setText(description);
            } else {
                desc.setVisibility(View.GONE);
            }

            cardContainer.addView(card);
        }

        highlightSelected();
    }

    private void highlightSelected() {
        if (items.isEmpty()) return;

        for (int i = 0; i < cardContainer.getChildCount(); i++) {
            View card = cardContainer.getChildAt(i);
            if (i == selectedIndex) {
                card.setBackgroundResource(R.drawable.bg_card_selected);
            } else {
                card.setBackgroundResource(R.drawable.bg_card);
            }
        }

        // Update status bar
        FeedItem current = items.get(selectedIndex);
        statusSource.setText(current.getSource());
        statusPosition.setText((selectedIndex + 1) + "/" + items.size());

        // Scroll to center selected card
        final View selected = cardContainer.getChildAt(selectedIndex);
        if (selected != null) {
            scrollView.post(new Runnable() {
                @Override
                public void run() {
                    int scrollX = selected.getLeft()
                            - (scrollView.getWidth() / 2)
                            + (selected.getWidth() / 2);
                    scrollView.smoothScrollTo(Math.max(0, scrollX), 0);
                }
            });
        }
    }

    private void navigateNext() {
        if (items.isEmpty()) return;
        selectedIndex = (selectedIndex + 1) % items.size();
        highlightSelected();
    }

    private void navigatePrev() {
        if (items.isEmpty()) return;
        selectedIndex = (selectedIndex - 1 + items.size()) % items.size();
        highlightSelected();
    }

    // --- Gesture handling ---

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                downTime = event.getEventTime();
                return true;

            case MotionEvent.ACTION_UP:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                long duration = event.getEventTime() - downTime;

                if (Math.abs(dx) > SWIPE_THRESHOLD || Math.abs(dy) > SWIPE_THRESHOLD) {
                    if (Math.abs(dx) > Math.abs(dy)) {
                        if (dx > 0) {
                            navigatePrev();
                        } else {
                            navigateNext();
                        }
                    } else if (dy > 0) {
                        // Swipe down — exit
                        finish();
                    }
                } else if (duration > LONG_PRESS_MS) {
                    // Long press — refresh
                    Log.d(TAG, "Long press — refreshing feeds");
                    statusText.setVisibility(View.VISIBLE);
                    statusText.setText(R.string.refresh);
                    loadFeeds();
                }
                return true;

            default:
                return true;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    navigateNext();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    navigatePrev();
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
