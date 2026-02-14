package com.glassvnc;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Minimal RFB (Remote Framebuffer) protocol client.
 * Supports RFB 3.3/3.7/3.8, no auth or VNC password auth, raw encoding.
 */
public class RfbProto {

    private static final int ENCODING_RAW = 0;
    private static final int ENCODING_COPYRECT = 1;
    private static final int ENCODING_DESKTOP_SIZE = -223;

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;

    public int fbWidth;
    public int fbHeight;
    public String serverName;
    public int bpp;        // bytes per pixel
    public int depth;
    public boolean bigEndian;
    public boolean trueColor;
    public int redMax, greenMax, blueMax;
    public int redShift, greenShift, blueShift;

    public void connect(String host, int port) throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(10000);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    public void close() {
        try { if (socket != null) socket.close(); } catch (IOException e) { /* ignore */ }
        socket = null;
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    /**
     * Perform the RFB handshake: version negotiation, security, init.
     */
    public void handshake(String password) throws IOException {
        // Server sends version string "RFB 003.008\n"
        byte[] verBuf = new byte[12];
        in.readFully(verBuf);
        String serverVersion = new String(verBuf, "ASCII").trim();

        // We'll speak 3.3 if server is 3.3, otherwise 3.8
        int major = 3;
        int minor = 3;
        if (serverVersion.contains("003.007")) {
            minor = 7;
        } else if (serverVersion.contains("003.008")) {
            minor = 8;
        }

        String clientVersion = String.format("RFB %03d.%03d\n", major, minor);
        out.write(clientVersion.getBytes("ASCII"));
        out.flush();

        // Security negotiation
        if (minor >= 7) {
            // Server sends number of security types, then types
            int numTypes = in.readUnsignedByte();
            if (numTypes == 0) {
                // Connection failed, read reason
                int reasonLen = in.readInt();
                byte[] reason = new byte[reasonLen];
                in.readFully(reason);
                throw new IOException("VNC refused: " + new String(reason, "ASCII"));
            }
            byte[] types = new byte[numTypes];
            in.readFully(types);

            // Pick best: prefer None (1), then VNC Auth (2)
            int chosen = -1;
            for (byte t : types) {
                int ti = t & 0xFF;
                if (ti == 1) { chosen = 1; break; }
                if (ti == 2 && chosen < 0) chosen = 2;
            }
            if (chosen < 0) {
                throw new IOException("No supported security type");
            }

            out.write(chosen);
            out.flush();

            if (chosen == 2) {
                doVncAuth(password);
            }

            if (minor >= 8) {
                // SecurityResult
                int result = in.readInt();
                if (result != 0) {
                    // Try to read reason
                    try {
                        int reasonLen = in.readInt();
                        byte[] reason = new byte[Math.min(reasonLen, 1024)];
                        in.readFully(reason);
                        throw new IOException("Auth failed: " + new String(reason, "ASCII"));
                    } catch (IOException e2) {
                        throw new IOException("VNC authentication failed");
                    }
                }
            }
        } else {
            // RFB 3.3: server picks security type
            int secType = in.readInt();
            if (secType == 0) {
                throw new IOException("Connection refused by server");
            } else if (secType == 2) {
                doVncAuth(password);
                int result = in.readInt();
                if (result != 0) {
                    throw new IOException("VNC authentication failed");
                }
            }
            // secType == 1 means None, proceed
        }

        // ClientInit — shared flag = 1
        out.write(1);
        out.flush();

        // ServerInit
        fbWidth = in.readUnsignedShort();
        fbHeight = in.readUnsignedShort();

        // Pixel format (16 bytes)
        bpp = in.readUnsignedByte();           // bits-per-pixel
        depth = in.readUnsignedByte();         // depth
        bigEndian = in.readUnsignedByte() != 0;
        trueColor = in.readUnsignedByte() != 0;
        redMax = in.readUnsignedShort();
        greenMax = in.readUnsignedShort();
        blueMax = in.readUnsignedShort();
        redShift = in.readUnsignedByte();
        greenShift = in.readUnsignedByte();
        blueShift = in.readUnsignedByte();
        in.skipBytes(3); // padding

        // Server name
        int nameLen = in.readInt();
        byte[] nameBuf = new byte[nameLen];
        in.readFully(nameBuf);
        serverName = new String(nameBuf, "ASCII");
    }

    private void doVncAuth(String password) throws IOException {
        byte[] challenge = new byte[16];
        in.readFully(challenge);

        if (password == null || password.isEmpty()) {
            throw new IOException("Server requires password");
        }

        byte[] key = new byte[8];
        byte[] pwBytes = password.getBytes("ASCII");
        System.arraycopy(pwBytes, 0, key, 0, Math.min(pwBytes.length, 8));

        // VNC DES: reverse bits in each key byte
        for (int i = 0; i < 8; i++) {
            key[i] = reverseBits(key[i]);
        }

        byte[] response = desEncrypt(key, challenge);
        out.write(response);
        out.flush();
    }

    private static byte reverseBits(byte b) {
        int v = b & 0xFF;
        int r = 0;
        for (int i = 0; i < 8; i++) {
            r = (r << 1) | (v & 1);
            v >>= 1;
        }
        return (byte) r;
    }

    /**
     * DES-ECB encrypt 16 bytes of challenge with 8-byte key.
     * Minimal DES implementation for VNC auth only.
     */
    private static byte[] desEncrypt(byte[] key, byte[] data) {
        try {
            javax.crypto.spec.SecretKeySpec ks = new javax.crypto.spec.SecretKeySpec(key, "DES");
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("DES/ECB/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, ks);
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("DES encryption failed", e);
        }
    }

    /**
     * Request our preferred pixel format: 32bpp BGRA (matches Android Bitmap).
     */
    public void setPixelFormat() throws IOException {
        byte[] msg = new byte[20];
        msg[0] = 0; // SetPixelFormat
        // padding: 1,2,3
        msg[4] = 32;  // bpp
        msg[5] = 24;  // depth
        msg[6] = 0;   // big-endian = false
        msg[7] = 1;   // true-color = true
        // red-max = 255 (big-endian short)
        msg[8] = 0; msg[9] = (byte) 255;
        // green-max = 255
        msg[10] = 0; msg[11] = (byte) 255;
        // blue-max = 255
        msg[12] = 0; msg[13] = (byte) 255;
        // shifts: R=16, G=8, B=0 (=> ARGB in big-endian = BGRA in little-endian)
        msg[14] = 16; // red-shift
        msg[15] = 8;  // green-shift
        msg[16] = 0;  // blue-shift
        // padding: 17,18,19
        out.write(msg);
        out.flush();

        bpp = 32;
        depth = 24;
        bigEndian = false;
        trueColor = true;
        redMax = greenMax = blueMax = 255;
        redShift = 16;
        greenShift = 8;
        blueShift = 0;
    }

    /**
     * Tell server which encodings we support.
     */
    public void setEncodings() throws IOException {
        int[] encodings = { ENCODING_RAW, ENCODING_COPYRECT, ENCODING_DESKTOP_SIZE };
        ByteBuffer buf = ByteBuffer.allocate(4 + encodings.length * 4);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 2); // SetEncodings
        buf.put((byte) 0); // padding
        buf.putShort((short) encodings.length);
        for (int enc : encodings) {
            buf.putInt(enc);
        }
        out.write(buf.array());
        out.flush();
    }

    /**
     * Request a framebuffer update for the given region.
     */
    public void requestUpdate(int x, int y, int w, int h, boolean incremental) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(10);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 3); // FramebufferUpdateRequest
        buf.put((byte) (incremental ? 1 : 0));
        buf.putShort((short) x);
        buf.putShort((short) y);
        buf.putShort((short) w);
        buf.putShort((short) h);
        out.write(buf.array());
        out.flush();
    }

    /**
     * Read one server message. Returns message type.
     */
    public int readServerMessage() throws IOException {
        return in.readUnsignedByte();
    }

    /**
     * Read a FramebufferUpdate message (type 0 already consumed).
     * Calls the listener for each rectangle.
     */
    public void readFramebufferUpdate(int[] framebuffer) throws IOException {
        in.skipBytes(1); // padding
        int numRects = in.readUnsignedShort();

        for (int i = 0; i < numRects; i++) {
            int x = in.readUnsignedShort();
            int y = in.readUnsignedShort();
            int w = in.readUnsignedShort();
            int h = in.readUnsignedShort();
            int encoding = in.readInt();

            if (encoding == ENCODING_DESKTOP_SIZE) {
                fbWidth = w;
                fbHeight = h;
            } else if (encoding == ENCODING_COPYRECT) {
                int srcX = in.readUnsignedShort();
                int srcY = in.readUnsignedShort();
                copyRect(framebuffer, srcX, srcY, x, y, w, h);
            } else if (encoding == ENCODING_RAW) {
                readRawRect(framebuffer, x, y, w, h);
            } else {
                throw new IOException("Unsupported encoding: " + encoding);
            }
        }
    }

    private void readRawRect(int[] framebuffer, int x, int y, int w, int h) throws IOException {
        // 4 bytes per pixel (we requested 32bpp)
        int rowBytes = w * 4;
        byte[] rowBuf = new byte[rowBytes];

        for (int row = 0; row < h; row++) {
            in.readFully(rowBuf);
            int fbY = y + row;
            if (fbY >= fbHeight) continue;
            int offset = fbY * fbWidth + x;
            for (int col = 0; col < w; col++) {
                if (x + col >= fbWidth) continue;
                int idx = col * 4;
                // Server sends pixels as R=shift16, G=shift8, B=shift0 in big-endian
                // In our 32bpp little-endian format: byte order is B, G, R, A
                int b = rowBuf[idx] & 0xFF;
                int g = rowBuf[idx + 1] & 0xFF;
                int r = rowBuf[idx + 2] & 0xFF;
                framebuffer[offset + col] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    private void copyRect(int[] framebuffer, int srcX, int srcY, int dstX, int dstY, int w, int h) {
        // Copy within framebuffer, handle overlap
        if (dstY < srcY || (dstY == srcY && dstX < srcX)) {
            for (int row = 0; row < h; row++) {
                int si = (srcY + row) * fbWidth + srcX;
                int di = (dstY + row) * fbWidth + dstX;
                System.arraycopy(framebuffer, si, framebuffer, di, w);
            }
        } else {
            for (int row = h - 1; row >= 0; row--) {
                int si = (srcY + row) * fbWidth + srcX;
                int di = (dstY + row) * fbWidth + dstX;
                System.arraycopy(framebuffer, si, framebuffer, di, w);
            }
        }
    }

    /**
     * Skip a server message we don't care about.
     */
    public void skipServerCutText() throws IOException {
        in.skipBytes(3); // padding
        int len = in.readInt();
        in.skipBytes(len);
    }

    public void skipBell() {
        // No data to skip
    }

    public void skipSetColourMap() throws IOException {
        in.skipBytes(1); // padding
        int firstColor = in.readUnsignedShort();
        int numColors = in.readUnsignedShort();
        in.skipBytes(numColors * 6);
    }
}
