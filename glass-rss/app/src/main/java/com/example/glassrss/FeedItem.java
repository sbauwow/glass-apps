package com.example.glassrss;

/**
 * A single RSS/Atom feed item.
 */
public class FeedItem implements Comparable<FeedItem> {

    private final String title;
    private final String description;
    private final String source;
    private final String link;
    private final long timestamp; // millis since epoch, 0 if unknown

    public FeedItem(String title, String description, String source, String link, long timestamp) {
        this.title = title != null ? title.trim() : "";
        this.description = description != null ? stripHtml(description.trim()) : "";
        this.source = source != null ? source.trim() : "";
        this.link = link != null ? link.trim() : "";
        this.timestamp = timestamp;
    }

    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getSource() { return source; }
    public String getLink() { return link; }
    public long getTimestamp() { return timestamp; }

    /** Sort newest first. */
    @Override
    public int compareTo(FeedItem other) {
        return Long.compare(other.timestamp, this.timestamp);
    }

    /** Strip HTML tags and decode common entities. */
    private static String stripHtml(String html) {
        String text = html.replaceAll("<[^>]+>", " ");
        text = text.replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"")
                   .replace("&#39;", "'")
                   .replace("&apos;", "'")
                   .replace("&#x27;", "'")
                   .replace("&nbsp;", " ");
        // Collapse whitespace
        text = text.replaceAll("\\s+", " ").trim();
        return text;
    }
}
