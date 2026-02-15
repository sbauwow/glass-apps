package com.glassnotify.client;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class LocationData {
    public final double lat;
    public final double lon;
    public final double alt;
    public final float accuracy;
    public final long time;

    public LocationData(double lat, double lon, double alt, float accuracy, long time) {
        this.lat = lat;
        this.lon = lon;
        this.alt = alt;
        this.accuracy = accuracy;
        this.time = time;
    }

    public String toJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "location");
            obj.put("lat", lat);
            obj.put("lon", lon);
            obj.put("alt", alt);
            obj.put("acc", accuracy);
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
}
