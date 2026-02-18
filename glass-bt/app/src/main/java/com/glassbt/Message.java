package com.glassbt;

import org.json.JSONException;
import org.json.JSONObject;

public class Message {
    public final String type;
    public final String id;
    public final long ts;
    public final JSONObject data;

    public Message(String type, long ts, JSONObject data) {
        this.type = type != null ? type : "";
        this.id = type + "_" + ts;
        this.ts = ts;
        this.data = data != null ? data : new JSONObject();
    }

    public static Message fromJson(String json) throws JSONException {
        JSONObject obj = new JSONObject(json);
        String type = obj.optString("type", "");
        long ts = obj.optLong("ts", System.currentTimeMillis());
        return new Message(type, ts, obj);
    }

    public String getText() {
        return data.optString("text", "");
    }

    public String getFrom() {
        return data.optString("from", "");
    }

    public String getCmd() {
        return data.optString("cmd", "");
    }

    public String getTitle() {
        return data.optString("title", "");
    }

    public String getApp() {
        return data.optString("app", "");
    }

    public boolean isHeartbeat() {
        return "heartbeat".equals(type);
    }

    public String getDisplayText() {
        if ("notification".equals(type)) {
            String title = getTitle();
            String text = getText();
            if (!title.isEmpty() && !text.isEmpty()) return title + ": " + text;
            if (!title.isEmpty()) return title;
            return text;
        }
        if ("command".equals(type)) {
            return "cmd: " + getCmd();
        }
        return getText();
    }

    public String getDisplayFrom() {
        if ("notification".equals(type)) return getApp();
        return getFrom();
    }
}
