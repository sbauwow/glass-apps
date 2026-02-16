package com.example.glassrss;

import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.io.StringReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Parses RSS 2.0 and Atom feeds using XmlPullParser.
 */
public class FeedParser {

    private static final String TAG = "GlassRSS";
    private static final int MAX_ITEMS_PER_FEED = 20;

    // Common date formats in RSS/Atom feeds
    private static final SimpleDateFormat[] DATE_FORMATS;

    static {
        String[] patterns = {
            "EEE, dd MMM yyyy HH:mm:ss Z",     // RFC 822 (RSS)
            "EEE, dd MMM yyyy HH:mm:ss zzz",   // RFC 822 variant
            "yyyy-MM-dd'T'HH:mm:ssZ",          // ISO 8601 (Atom)
            "yyyy-MM-dd'T'HH:mm:ss'Z'",        // ISO 8601 UTC
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",      // ISO 8601 with millis
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",    // ISO 8601 with millis UTC
            "yyyy-MM-dd HH:mm:ss",              // Simple
            "yyyy-MM-dd",                        // Date only
        };
        DATE_FORMATS = new SimpleDateFormat[patterns.length];
        for (int i = 0; i < patterns.length; i++) {
            DATE_FORMATS[i] = new SimpleDateFormat(patterns[i], Locale.US);
            DATE_FORMATS[i].setTimeZone(TimeZone.getTimeZone("UTC"));
        }
    }

    /**
     * Parse a feed from an InputStream. Detects RSS vs Atom automatically.
     * @param input the XML input stream
     * @param sourceName display name for the feed source
     * @return list of parsed FeedItems
     */
    public static List<FeedItem> parse(InputStream input, String sourceName) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(false);
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(input, null);

        // Detect format by looking for first significant element
        boolean isAtom = false;
        boolean insideItem = false;
        String feedTitle = null;

        String title = null;
        String description = null;
        String link = null;
        String dateStr = null;
        String currentTag = null;
        StringBuilder textBuffer = new StringBuilder();

        List<FeedItem> items = new ArrayList<FeedItem>();

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                currentTag = parser.getName();

                if ("feed".equals(currentTag)) {
                    isAtom = true;
                } else if ("item".equals(currentTag) || "entry".equals(currentTag)) {
                    insideItem = true;
                    title = null;
                    description = null;
                    link = null;
                    dateStr = null;
                } else if (insideItem && "link".equals(currentTag) && isAtom) {
                    // Atom <link href="..." />
                    String href = parser.getAttributeValue(null, "href");
                    String rel = parser.getAttributeValue(null, "rel");
                    if (href != null && (rel == null || "alternate".equals(rel))) {
                        link = href;
                    }
                }
                textBuffer.setLength(0);

            } else if (eventType == XmlPullParser.TEXT) {
                textBuffer.append(parser.getText());

            } else if (eventType == XmlPullParser.END_TAG) {
                String tag = parser.getName();
                String text = textBuffer.toString().trim();

                if (!insideItem && "title".equals(tag) && feedTitle == null && text.length() > 0) {
                    feedTitle = text;
                }

                if (insideItem) {
                    if ("title".equals(tag)) {
                        title = text;
                    } else if ("description".equals(tag) || "summary".equals(tag) || "content".equals(tag)) {
                        if (description == null || text.length() > description.length()) {
                            description = text;
                        }
                    } else if ("link".equals(tag) && !isAtom) {
                        link = text;
                    } else if ("pubDate".equals(tag) || "published".equals(tag)
                            || "updated".equals(tag) || "dc:date".equals(tag)) {
                        if (dateStr == null) {
                            dateStr = text;
                        }
                    }
                }

                if ("item".equals(tag) || "entry".equals(tag)) {
                    insideItem = false;
                    if (title != null && title.length() > 0) {
                        String src = (sourceName != null && sourceName.length() > 0)
                                ? sourceName : (feedTitle != null ? feedTitle : "Unknown");
                        long ts = parseDate(dateStr);
                        items.add(new FeedItem(title, description, src, link, ts));
                    }
                    if (items.size() >= MAX_ITEMS_PER_FEED) {
                        break;
                    }
                }

                currentTag = null;
            }

            eventType = parser.next();
        }

        Log.d(TAG, "Parsed " + items.size() + " items from " +
                (feedTitle != null ? feedTitle : sourceName));
        return items;
    }

    /** Try all known date formats, return millis or 0 if unparseable. */
    static long parseDate(String dateStr) {
        if (dateStr == null || dateStr.length() == 0) return 0;

        // Normalize timezone offset format for ISO 8601
        // "+00:00" → "+0000"
        String normalized = dateStr.replaceAll("([+-]\\d{2}):(\\d{2})$", "$1$2");

        for (SimpleDateFormat fmt : DATE_FORMATS) {
            try {
                Date d = fmt.parse(normalized);
                if (d != null) return d.getTime();
            } catch (ParseException ignored) {}
        }

        Log.w(TAG, "Unparseable date: " + dateStr);
        return 0;
    }
}
