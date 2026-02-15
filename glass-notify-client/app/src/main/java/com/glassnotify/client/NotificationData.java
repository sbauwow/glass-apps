package com.glassnotify.client;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

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

    public String toJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "notification");
            obj.put("app", app);
            obj.put("title", title);
            obj.put("text", text);
            obj.put("time", time);
            return obj.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    /** Returns length-prefixed wire format: [4 bytes big-endian length][UTF-8 JSON] */
    public byte[] toBytes() {
        try {
            byte[] json = toJson().getBytes("UTF-8");
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4 + json.length);
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(json.length);
            dos.write(json);
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /** Heartbeat packet */
    public static byte[] heartbeat() {
        try {
            byte[] json = "{}".getBytes("UTF-8");
            ByteArrayOutputStream baos = new ByteArrayOutputStream(6);
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(json.length);
            dos.write(json);
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
