package com.glassbt;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class MessageProtocol {

    private static final int MAX_MESSAGE_SIZE = 65536;

    public static Message readMessage(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length <= 0 || length > MAX_MESSAGE_SIZE) {
            throw new IOException("Invalid message length: " + length);
        }
        byte[] data = new byte[length];
        in.readFully(data);
        String json = new String(data, "UTF-8");
        try {
            return Message.fromJson(json);
        } catch (JSONException e) {
            throw new IOException("Invalid JSON: " + e.getMessage());
        }
    }

    public static void writeMessage(DataOutputStream out, JSONObject msg) throws IOException {
        byte[] payload = msg.toString().getBytes("UTF-8");
        synchronized (out) {
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
        }
    }

    public static void sendText(DataOutputStream out, String text) throws IOException {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "text");
            msg.put("ts", System.currentTimeMillis());
            msg.put("from", "glass");
            msg.put("text", text);
            writeMessage(out, msg);
        } catch (JSONException e) {
            throw new IOException("JSON error: " + e.getMessage());
        }
    }

    public static void sendHeartbeat(DataOutputStream out) throws IOException {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "heartbeat");
            msg.put("ts", System.currentTimeMillis());
            writeMessage(out, msg);
        } catch (JSONException e) {
            throw new IOException("JSON error: " + e.getMessage());
        }
    }
}
