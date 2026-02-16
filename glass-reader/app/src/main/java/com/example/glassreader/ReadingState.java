package com.example.glassreader;

import android.content.Context;
import android.content.SharedPreferences;

public class ReadingState {

    private static final String PREFS_NAME = "glass_reader_state";
    private final SharedPreferences prefs;

    public ReadingState(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private String key(String filename, String suffix) {
        String sanitized = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        return sanitized + "_" + suffix;
    }

    public void savePosition(String filename, int page, float scrollOffset, int mode) {
        prefs.edit()
                .putInt(key(filename, "page"), page)
                .putFloat(key(filename, "scroll"), scrollOffset)
                .putInt(key(filename, "mode"), mode)
                .apply();
    }

    public int getPage(String filename) {
        return prefs.getInt(key(filename, "page"), 0);
    }

    public float getScrollOffset(String filename) {
        return prefs.getFloat(key(filename, "scroll"), 0f);
    }

    public int getMode(String filename) {
        return prefs.getInt(key(filename, "mode"), ReaderView.MODE_BOOK);
    }
}
