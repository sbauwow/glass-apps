package com.example.glassterm;

public class SshFavorite {
    String name;
    String user;
    String host;
    int port;

    SshFavorite(String name, String user, String host, int port) {
        this.name = name;
        this.user = user;
        this.host = host;
        this.port = port;
    }

    boolean isEmpty() {
        return host == null || host.isEmpty();
    }

    /**
     * Parse from pipe-delimited string: "name|user|host|port"
     */
    static SshFavorite parse(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] parts = s.split("\\|");
        if (parts.length != 4) return null;
        try {
            return new SshFavorite(parts[0], parts[1], parts[2], Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
