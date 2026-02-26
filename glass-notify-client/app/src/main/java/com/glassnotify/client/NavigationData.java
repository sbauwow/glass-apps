package com.glassnotify.client;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class NavigationData {
    public final String instruction;
    public final String distance;
    public final String eta;
    public final long time;

    public NavigationData(String instruction, String distance, String eta, long time) {
        this.instruction = instruction != null ? instruction : "";
        this.distance = distance != null ? distance : "";
        this.eta = eta != null ? eta : "";
        this.time = time;
    }

    public String toJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "nav");
            obj.put("instruction", instruction);
            obj.put("distance", distance);
            obj.put("eta", eta);
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

    /** Returns a nav_end packet signaling navigation has stopped */
    public static byte[] navEnd() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "nav_end");
            obj.put("time", System.currentTimeMillis());
            byte[] json = obj.toString().getBytes("UTF-8");
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4 + json.length);
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(json.length);
            dos.write(json);
            dos.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }
}
