package com.glassnotify;

import org.json.JSONException;
import org.json.JSONObject;

public class NotificationData {
    public final String app;
    public final String title;
    public final String text;
    public final long time;

    public NotificationData(String app, String title, String text, long time) {
        this.app = app != null ? app : "";
        this.title = title != null ? title : "";
        this.text = text != null ? text : "";
        this.time = time;
    }

    public static NotificationData fromJson(String json) throws JSONException {
        JSONObject obj = new JSONObject(json);
        return new NotificationData(
                obj.optString("app", ""),
                obj.optString("title", ""),
                obj.optString("text", ""),
                obj.optLong("time", System.currentTimeMillis())
        );
    }

    public boolean isHeartbeat() {
        return app.isEmpty() && title.isEmpty() && text.isEmpty();
    }
}
