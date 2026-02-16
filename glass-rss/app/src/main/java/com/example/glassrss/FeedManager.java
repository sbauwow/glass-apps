package com.example.glassrss;

import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Loads feed URLs from config file, fetches all feeds in parallel,
 * and returns a merged, sorted list of FeedItems.
 */
public class FeedManager {

    private static final String TAG = "GlassRSS";
    private static final String CONFIG_FILE = "glass-rss-feeds.txt";

    private static final String DEFAULT_CONFIG =
            "# Glass RSS Feeds — one URL per line, # for comments\n" +
            "\n" +
            "# Tech\n" +
            "https://hnrss.org/frontpage\n" +
            "https://feeds.arstechnica.com/arstechnica/index\n" +
            "https://www.theverge.com/rss/index.xml\n" +
            "\n" +
            "# Finance\n" +
            "https://feeds.finance.yahoo.com/rss/2.0/headline?s=^GSPC&region=US&lang=en-US\n" +
            "\n" +
            "# SEC / Finance news\n" +
            "https://feeds.finance.yahoo.com/rss/2.0/headline?s=AAPL,MSFT,GOOG,NVDA&region=US&lang=en-US\n";

    /** Callback for feed fetch completion. */
    public interface FeedCallback {
        void onFeedsLoaded(List<FeedItem> items);
        void onError(String message);
    }

    /**
     * Fetch all configured feeds in parallel on a background thread.
     * Calls back on the calling thread (use Handler to post to main thread).
     */
    public static void fetchAll(final FeedCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<String> urls = loadFeedUrls();
                    if (urls.isEmpty()) {
                        callback.onError("No feed URLs configured");
                        return;
                    }

                    Log.d(TAG, "Fetching " + urls.size() + " feeds");

                    final List<FeedItem> allItems =
                            Collections.synchronizedList(new ArrayList<FeedItem>());
                    final CountDownLatch latch = new CountDownLatch(urls.size());

                    for (final String url : urls) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    InputStream stream = HttpUtil.fetchStream(url);
                                    List<FeedItem> items = FeedParser.parse(stream, null);
                                    stream.close();
                                    allItems.addAll(items);
                                } catch (Exception e) {
                                    Log.w(TAG, "Failed to fetch " + url + ": " + e.getMessage());
                                } finally {
                                    latch.countDown();
                                }
                            }
                        }).start();
                    }

                    latch.await();

                    Collections.sort(allItems);
                    Log.d(TAG, "Total items: " + allItems.size());
                    callback.onFeedsLoaded(allItems);

                } catch (Exception e) {
                    Log.e(TAG, "Feed fetch failed", e);
                    callback.onError(e.getMessage());
                }
            }
        }).start();
    }

    /** Load feed URLs from config file, creating default if needed. */
    private static List<String> loadFeedUrls() {
        File configFile = new File(Environment.getExternalStorageDirectory(), CONFIG_FILE);

        if (!configFile.exists()) {
            writeDefaultConfig(configFile);
        }

        List<String> urls = new ArrayList<String>();
        try {
            BufferedReader reader = new BufferedReader(new FileReader(configFile));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.length() > 0 && !line.startsWith("#")) {
                    urls.add(line);
                }
            }
            reader.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to read config", e);
            // Fall back to hardcoded defaults
            urls.add("https://hnrss.org/frontpage");
            urls.add("https://feeds.arstechnica.com/arstechnica/index");
            urls.add("https://www.theverge.com/rss/index.xml");
        }

        Log.d(TAG, "Loaded " + urls.size() + " feed URLs");
        return urls;
    }

    private static void writeDefaultConfig(File file) {
        try {
            FileWriter writer = new FileWriter(file);
            writer.write(DEFAULT_CONFIG);
            writer.close();
            Log.d(TAG, "Created default config: " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "Failed to write default config", e);
        }
    }
}
