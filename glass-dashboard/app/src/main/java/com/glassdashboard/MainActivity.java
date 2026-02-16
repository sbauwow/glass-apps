package com.glassdashboard;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ViewFlipper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity {

    private static final String TAG = "GlassDashboard";
    private static final long REFRESH_FAST_MS = 5 * 60 * 1000;   // 5 min for sports/stocks
    private static final long REFRESH_SLOW_MS = 15 * 60 * 1000;  // 15 min for news

    private static final String PREFS_NAME = "dashboard_prefs";
    private static final String PREF_SYMBOLS = "symbols";
    private static final String DEFAULT_SYMBOLS = "AAPL,MSFT,GOOGL,AMZN,TSLA,META,NVDA,SPY,QQQ,DIA";

    private static final String[] ESPN_URLS = {
        "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard",
        "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard",
        "https://site.api.espn.com/apis/site/v2/sports/baseball/mlb/scoreboard",
        "https://site.api.espn.com/apis/site/v2/sports/hockey/nhl/scoreboard"
    };
    private static final String[] LEAGUE_NAMES = { "NFL", "NBA", "MLB", "NHL" };

    private static final String NEWS_URL =
            "https://www.toptal.com/developers/feed2json/convert?url=https://news.google.com/rss?hl=en-US";

    private static final String YAHOO_BASE =
            "https://query1.finance.yahoo.com/v7/finance/quote?symbols=";

    private ViewFlipper viewFlipper;
    private TextView statusText;
    private LinearLayout sportsContainer;
    private LinearLayout newsContainer;
    private LinearLayout stocksContainer;
    private View dot0, dot1, dot2;
    private View dotContainer;

    private Handler handler;
    private PowerManager.WakeLock wakeLock;
    private String symbols;

    private float touchDownX;
    private float touchDownY;
    private long touchDownTime;

    // Auto-refresh runnables
    private final Runnable refreshSportsStocks = new Runnable() {
        @Override
        public void run() {
            fetchSports();
            fetchStocks();
            handler.postDelayed(this, REFRESH_FAST_MS);
        }
    };

    private final Runnable refreshNews = new Runnable() {
        @Override
        public void run() {
            fetchNews();
            handler.postDelayed(this, REFRESH_SLOW_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = (TextView) findViewById(R.id.statusText);
        viewFlipper = (ViewFlipper) findViewById(R.id.viewFlipper);
        sportsContainer = (LinearLayout) findViewById(R.id.sportsContainer);
        newsContainer = (LinearLayout) findViewById(R.id.newsContainer);
        stocksContainer = (LinearLayout) findViewById(R.id.stocksContainer);
        dot0 = findViewById(R.id.dot0);
        dot1 = findViewById(R.id.dot1);
        dot2 = findViewById(R.id.dot2);
        dotContainer = findViewById(R.id.dotContainer);

        handler = new Handler(Looper.getMainLooper());

        // Load symbols from intent or prefs
        String intentSymbols = getIntent().getStringExtra("symbols");
        if (intentSymbols != null && !intentSymbols.isEmpty()) {
            symbols = intentSymbols.toUpperCase().replaceAll("\\s+", "");
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(PREF_SYMBOLS, symbols).apply();
        } else {
            symbols = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(PREF_SYMBOLS, DEFAULT_SYMBOLS);
        }

        // WakeLock
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, TAG + ":wake");

        // Start all fetches
        fetchSports();
        fetchNews();
        fetchStocks();

        // Schedule auto-refresh
        handler.postDelayed(refreshSportsStocks, REFRESH_FAST_MS);
        handler.postDelayed(refreshNews, REFRESH_SLOW_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        wakeLock.acquire();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (wakeLock.isHeld()) wakeLock.release();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(refreshSportsStocks);
        handler.removeCallbacks(refreshNews);
    }

    // ========== DATA FETCHING ==========

    private void fetchSports() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<LeagueData> leagues = new ArrayList<LeagueData>();
                final CountDownLatch latch = new CountDownLatch(ESPN_URLS.length);

                for (int i = 0; i < ESPN_URLS.length; i++) {
                    final int idx = i;
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                String json = HttpUtil.fetchUrl(ESPN_URLS[idx]);
                                JSONObject obj = new JSONObject(json);
                                JSONArray events = obj.getJSONArray("events");
                                if (events.length() > 0) {
                                    LeagueData ld = new LeagueData();
                                    ld.name = LEAGUE_NAMES[idx];
                                    ld.games = new ArrayList<GameData>();
                                    int limit = Math.min(events.length(), 4);
                                    for (int g = 0; g < limit; g++) {
                                        JSONObject event = events.getJSONObject(g);
                                        JSONArray competitions = event.getJSONArray("competitions");
                                        JSONObject comp = competitions.getJSONObject(0);
                                        JSONArray competitors = comp.getJSONArray("competitors");

                                        JSONObject home = null, away = null;
                                        for (int c = 0; c < competitors.length(); c++) {
                                            JSONObject team = competitors.getJSONObject(c);
                                            if ("home".equals(team.getString("homeAway"))) {
                                                home = team;
                                            } else {
                                                away = team;
                                            }
                                        }

                                        if (home != null && away != null) {
                                            GameData gd = new GameData();
                                            gd.awayAbbr = away.getJSONObject("team").getString("abbreviation");
                                            gd.homeAbbr = home.getJSONObject("team").getString("abbreviation");
                                            gd.awayScore = away.optString("score", "0");
                                            gd.homeScore = home.optString("score", "0");

                                            // Status
                                            JSONObject status = comp.getJSONObject("status");
                                            JSONObject statusType = status.getJSONObject("type");
                                            gd.statusId = statusType.optInt("id", 1);
                                            gd.statusText = statusType.optString("shortDetail", "");
                                            gd.games = ld.games; // not used
                                            ld.games.add(gd);
                                        }
                                    }
                                    synchronized (leagues) {
                                        leagues.add(ld);
                                    }
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "ESPN fetch failed for " + LEAGUE_NAMES[idx], e);
                            } finally {
                                latch.countDown();
                            }
                        }
                    }).start();
                }

                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Log.e(TAG, "Latch interrupted", e);
                }

                // Sort leagues in standard order
                final List<LeagueData> sorted = new ArrayList<LeagueData>();
                for (String name : LEAGUE_NAMES) {
                    for (LeagueData ld : leagues) {
                        if (name.equals(ld.name)) {
                            sorted.add(ld);
                            break;
                        }
                    }
                }

                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        renderSports(sorted);
                    }
                });
            }
        }).start();
    }

    private void fetchNews() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String json = HttpUtil.fetchUrl(NEWS_URL);
                    JSONObject obj = new JSONObject(json);
                    final JSONArray items = obj.getJSONArray("items");
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            renderNews(items);
                        }
                    });
                } catch (final Exception e) {
                    Log.e(TAG, "News fetch failed", e);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            showError("News: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void fetchStocks() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = YAHOO_BASE + symbols;
                    String json = HttpUtil.fetchUrl(url);
                    JSONObject obj = new JSONObject(json);
                    final JSONArray results = obj.getJSONObject("quoteResponse")
                            .getJSONArray("result");
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            renderStocks(results);
                        }
                    });
                } catch (final Exception e) {
                    Log.e(TAG, "Stocks fetch failed", e);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            showError("Stocks: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    // ========== RENDERING ==========

    private void showContent() {
        statusText.setVisibility(View.GONE);
        viewFlipper.setVisibility(View.VISIBLE);
        dotContainer.setVisibility(View.VISIBLE);
    }

    private void showError(String msg) {
        if (viewFlipper.getVisibility() != View.VISIBLE) {
            statusText.setText(msg);
            statusText.setVisibility(View.VISIBLE);
        }
        Log.e(TAG, msg);
    }

    private void renderSports(List<LeagueData> leagues) {
        sportsContainer.removeAllViews();

        if (leagues.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No games today");
            empty.setTextColor(0xFF999999);
            empty.setTextSize(18);
            empty.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            sportsContainer.addView(empty);
        } else {
            for (int i = 0; i < leagues.size(); i++) {
                LeagueData league = leagues.get(i);

                // League header
                TextView header = new TextView(this);
                header.setText(league.name);
                header.setTextColor(0xFFFFFFFF);
                header.setTextSize(16);
                header.setTypeface(Typeface.DEFAULT_BOLD);
                if (i > 0) {
                    header.setPadding(0, dp(10), 0, dp(2));
                } else {
                    header.setPadding(0, 0, 0, dp(2));
                }
                sportsContainer.addView(header);

                // Games
                for (GameData game : league.games) {
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
                    row.setPadding(0, dp(1), 0, dp(1));

                    // Score text: "KC 24 - 21 BUF"
                    TextView scoreView = new TextView(this);
                    String scoreText = game.awayAbbr + " " + game.awayScore
                            + " - " + game.homeScore + " " + game.homeAbbr;
                    scoreView.setText(scoreText);
                    scoreView.setTextSize(17);
                    // statusId: 1=scheduled, 2=in-progress, 3=final
                    boolean isFinal = (game.statusId == 3);
                    scoreView.setTextColor(isFinal ? 0xFF999999 : 0xFFFFFFFF);
                    scoreView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                    LinearLayout.LayoutParams scoreParams = new LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    scoreView.setLayoutParams(scoreParams);
                    row.addView(scoreView);

                    // Status text
                    TextView statusView = new TextView(this);
                    statusView.setText(game.statusText);
                    statusView.setTextSize(14);
                    statusView.setTextColor(0xFF999999);
                    statusView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                    statusView.setGravity(Gravity.RIGHT);
                    row.addView(statusView);

                    sportsContainer.addView(row);
                }
            }
        }

        showContent();
    }

    private void renderNews(JSONArray items) {
        newsContainer.removeAllViews();

        // Header
        TextView header = new TextView(this);
        header.setText("TOP STORIES");
        header.setTextColor(0xFFFFFFFF);
        header.setTextSize(16);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0, 0, 0, dp(8));
        newsContainer.addView(header);

        int limit = Math.min(items.length(), 8);
        for (int i = 0; i < limit; i++) {
            try {
                JSONObject item = items.getJSONObject(i);
                String title = item.optString("title", "");
                String pubDate = item.optString("date_published", "");

                // Headline
                TextView headline = new TextView(this);
                headline.setText(cleanTitle(title));
                headline.setTextColor(0xFFFFFFFF);
                headline.setTextSize(17);
                headline.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                headline.setMaxLines(2);
                headline.setPadding(0, dp(4), 0, 0);
                newsContainer.addView(headline);

                // Timestamp
                TextView time = new TextView(this);
                time.setText(relativeTime(pubDate));
                time.setTextColor(0xFF999999);
                time.setTextSize(13);
                time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                time.setPadding(0, 0, 0, dp(6));
                newsContainer.addView(time);

            } catch (Exception e) {
                Log.e(TAG, "News item parse error", e);
            }
        }

        showContent();
    }

    private void renderStocks(JSONArray results) {
        stocksContainer.removeAllViews();

        for (int i = 0; i < results.length(); i++) {
            try {
                JSONObject q = results.getJSONObject(i);
                String symbol = q.optString("symbol", "??");
                double price = q.optDouble("regularMarketPrice", 0);
                double change = q.optDouble("regularMarketChange", 0);
                double changePct = q.optDouble("regularMarketChangePercent", 0);

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                row.setPadding(0, dp(2), 0, dp(2));

                int color = change >= 0 ? 0xFF4CAF50 : 0xFFF44336;
                String sign = change >= 0 ? "+" : "";

                // Symbol
                TextView symView = new TextView(this);
                symView.setText(symbol);
                symView.setTextColor(0xFFFFFFFF);
                symView.setTextSize(16);
                symView.setTypeface(Typeface.MONOSPACE);
                symView.setWidth(dp(60));
                row.addView(symView);

                // Price
                TextView priceView = new TextView(this);
                priceView.setText(String.format("%.2f", price));
                priceView.setTextColor(0xFFFFFFFF);
                priceView.setTextSize(16);
                priceView.setTypeface(Typeface.MONOSPACE);
                priceView.setWidth(dp(80));
                priceView.setGravity(Gravity.RIGHT);
                row.addView(priceView);

                // Change
                TextView changeView = new TextView(this);
                changeView.setText(String.format("%s%.2f", sign, change));
                changeView.setTextColor(color);
                changeView.setTextSize(16);
                changeView.setTypeface(Typeface.MONOSPACE);
                changeView.setWidth(dp(75));
                changeView.setGravity(Gravity.RIGHT);
                row.addView(changeView);

                // Percent
                TextView pctView = new TextView(this);
                pctView.setText(String.format("%s%.2f%%", sign, changePct));
                pctView.setTextColor(color);
                pctView.setTextSize(16);
                pctView.setTypeface(Typeface.MONOSPACE);
                LinearLayout.LayoutParams pctParams = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                pctView.setLayoutParams(pctParams);
                pctView.setGravity(Gravity.RIGHT);
                row.addView(pctView);

                stocksContainer.addView(row);
            } catch (Exception e) {
                Log.e(TAG, "Stock item parse error", e);
            }
        }

        showContent();
    }

    // ========== PAGE SWITCHING ==========

    private void showPage(int page) {
        viewFlipper.setDisplayedChild(page);
        updateDots(page);
    }

    private void nextPage() {
        int current = viewFlipper.getDisplayedChild();
        int next = (current + 1) % viewFlipper.getChildCount();
        viewFlipper.setDisplayedChild(next);
        updateDots(next);
    }

    private void prevPage() {
        int current = viewFlipper.getDisplayedChild();
        int prev = (current - 1 + viewFlipper.getChildCount()) % viewFlipper.getChildCount();
        viewFlipper.setDisplayedChild(prev);
        updateDots(prev);
    }

    private void updateDots(int activePage) {
        dot0.setBackgroundResource(activePage == 0 ? R.drawable.dot_active : R.drawable.dot_inactive);
        dot1.setBackgroundResource(activePage == 1 ? R.drawable.dot_active : R.drawable.dot_inactive);
        dot2.setBackgroundResource(activePage == 2 ? R.drawable.dot_active : R.drawable.dot_inactive);
    }

    private void refreshCurrentPage() {
        int page = viewFlipper.getDisplayedChild();
        switch (page) {
            case 0: fetchNews(); break;
            case 1: fetchSports(); break;
            case 2: fetchStocks(); break;
        }
    }

    // ========== INPUT HANDLING ==========

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                touchDownTime = System.currentTimeMillis();
                break;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - touchDownX;
                float dy = event.getY() - touchDownY;
                long dt = System.currentTimeMillis() - touchDownTime;

                if (Math.abs(dy) > 100 && Math.abs(dy) > Math.abs(dx)) {
                    // Swipe down = exit
                    finish();
                    return true;
                }
                if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy)) {
                    // Horizontal swipe
                    if (dx < 0) {
                        nextPage();
                    } else {
                        prevPage();
                    }
                    return true;
                }
                if (dt > 800 && Math.abs(dx) < 50 && Math.abs(dy) < 50) {
                    // Long press (no movement) = exit
                    finish();
                    return true;
                }
                if (Math.abs(dx) < 50 && Math.abs(dy) < 50) {
                    // Tap = refresh current page
                    refreshCurrentPage();
                    return true;
                }
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_BACK:
                    finish();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    nextPage();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    prevPage();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    refreshCurrentPage();
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ========== HELPERS ==========

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private static String cleanTitle(String title) {
        // Google News appends " - Source Name"
        int dash = title.lastIndexOf(" - ");
        if (dash > 0) {
            return title.substring(0, dash);
        }
        return title;
    }

    private static String relativeTime(String isoDate) {
        if (isoDate == null || isoDate.isEmpty()) return "";
        try {
            // ISO 8601: "2024-01-15T10:30:00+00:00" or "2024-01-15T10:30:00Z"
            String clean = isoDate.replace("Z", "+00:00");
            // Simple parse — extract date/time parts
            long pubMs = parseIso8601(clean);
            long nowMs = System.currentTimeMillis();
            long diffMin = (nowMs - pubMs) / (60 * 1000);

            if (diffMin < 1) return "just now";
            if (diffMin < 60) return diffMin + " min ago";
            long diffHr = diffMin / 60;
            if (diffHr < 24) return diffHr + (diffHr == 1 ? " hour ago" : " hours ago");
            long diffDay = diffHr / 24;
            return diffDay + (diffDay == 1 ? " day ago" : " days ago");
        } catch (Exception e) {
            return "";
        }
    }

    private static long parseIso8601(String s) throws Exception {
        // Parse "2024-01-15T10:30:00+00:00" manually for API 19 compat
        // Strip timezone for SimpleDateFormat
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));

        String datePart = s;
        // Remove timezone offset
        int plusIdx = s.lastIndexOf('+');
        int minusIdx = s.lastIndexOf('-');
        // The date has dashes too, so look after the T
        int tIdx = s.indexOf('T');
        if (tIdx > 0) {
            if (plusIdx > tIdx) {
                datePart = s.substring(0, plusIdx);
            } else if (minusIdx > tIdx + 1) {
                datePart = s.substring(0, minusIdx);
            }
        }
        // Truncate fractional seconds
        int dotIdx = datePart.indexOf('.');
        if (dotIdx > 0) {
            datePart = datePart.substring(0, dotIdx);
        }

        return sdf.parse(datePart).getTime();
    }

    // ========== DATA CLASSES ==========

    private static class LeagueData {
        String name;
        List<GameData> games;
    }

    private static class GameData {
        String awayAbbr, homeAbbr;
        String awayScore, homeScore;
        int statusId; // 1=scheduled, 2=in-progress, 3=final
        String statusText;
        List<GameData> games; // unused field for sync
    }
}
