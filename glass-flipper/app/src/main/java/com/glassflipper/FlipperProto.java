package com.glassflipper;

/**
 * Hand-coded protobuf encoder/decoder for Flipper Zero RPC screen streaming.
 *
 * Flipper RPC uses varint-length-prefixed Main messages.
 * We only need two request types and one response type:
 *   - gui_start_screen_stream_request (field 20 of Main)
 *   - gui_stop_screen_stream_request  (field 21 of Main)
 *   - gui_screen_frame (field 22 of Main) → ScreenFrame.data = 1024 bytes XBM
 */
public class FlipperProto {

    /** Expected XBM frame size: 128x64 pixels / 8 bits = 1024 bytes. */
    public static final int XBM_FRAME_SIZE = 1024;

    /**
     * Encode Main { command_id=1, gui_start_screen_stream_request={} }
     *
     * Wire format:
     *   field 1 (command_id), varint: 0x08 0x01
     *   field 20 (gui_start_screen_stream_request), length-delimited: 0xa2 0x01 0x00
     *
     * Varint-length prefix: 0x05 (5 bytes payload)
     */
    public static byte[] encodeStartScreenStream() {
        return new byte[] {
            0x05,                   // varint length prefix: 5 bytes follow
            0x08, 0x01,             // field 1 (command_id) = 1
            (byte) 0xa2, 0x01, 0x00 // field 20 (embedded message, length 0)
        };
    }

    /**
     * Encode Main { command_id=2, gui_stop_screen_stream_request={} }
     *
     * Wire format:
     *   field 1 (command_id), varint: 0x08 0x02
     *   field 21 (gui_stop_screen_stream_request), length-delimited: 0xaa 0x01 0x00
     *
     * Varint-length prefix: 0x05 (5 bytes payload)
     */
    public static byte[] encodeStopScreenStream() {
        return new byte[] {
            0x05,                   // varint length prefix: 5 bytes follow
            0x08, 0x02,             // field 1 (command_id) = 2
            (byte) 0xaa, 0x01, 0x00 // field 21 (embedded message, length 0)
        };
    }

    /**
     * Decode a varint from buf starting at offset.
     * Returns [value, bytesConsumed] or null if not enough data.
     */
    public static long[] decodeVarint(byte[] buf, int offset, int len) {
        long result = 0;
        int shift = 0;
        for (int i = 0; i < 10 && offset + i < len; i++) {
            long b = buf[offset + i] & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return new long[] { result, i + 1 };
            }
            shift += 7;
        }
        return null; // incomplete varint
    }

    /**
     * Try to decode a complete Main response from buf[0..len).
     * Returns the XBM frame data (1024 bytes) if this is a screen frame,
     * or null if it's a different message type.
     *
     * Format: varint(messageLength) + Main { ... field 22 { field 1 = data } }
     */
    public static byte[] decodeMainResponse(byte[] buf, int offset, int len) {
        // We look for field 22 tag: wire type 2 (length-delimited), field 22
        // Tag = (22 << 3) | 2 = 178 = 0xb2, followed by varint 0x01 (multi-byte field tag)
        // Actually: field 22 tag as varint = (22 << 3) | 2 = 0xB2, then 0x01
        // Search for 0xB2 0x01 in the message body

        int end = offset + len;
        for (int i = offset; i < end - 1; i++) {
            if ((buf[i] & 0xFF) == 0xB2 && (buf[i + 1] & 0xFF) == 0x01) {
                // Found field 22 tag — next is varint length of ScreenFrame
                int pos = i + 2;
                long[] frameLen = decodeVarint(buf, pos, end);
                if (frameLen == null) return null;

                pos += (int) frameLen[1];
                int frameMsgEnd = pos + (int) frameLen[0];
                if (frameMsgEnd > end) return null;

                // Inside ScreenFrame, find field 1 (data): tag 0x0A, then varint length, then bytes
                return findBytesField(buf, pos, frameMsgEnd, 1);
            }
        }
        return null;
    }

    /**
     * Find a bytes/string field (wire type 2) with the given field number
     * in buf[start..end) and return its value.
     */
    private static byte[] findBytesField(byte[] buf, int start, int end, int fieldNumber) {
        int expectedTag = (fieldNumber << 3) | 2;
        for (int i = start; i < end; i++) {
            if ((buf[i] & 0xFF) == expectedTag) {
                int pos = i + 1;
                long[] dataLen = decodeVarint(buf, pos, end);
                if (dataLen == null) return null;
                pos += (int) dataLen[1];
                int dataEnd = pos + (int) dataLen[0];
                if (dataEnd > end) return null;

                byte[] data = new byte[(int) dataLen[0]];
                System.arraycopy(buf, pos, data, 0, data.length);
                return data;
            }
        }
        return null;
    }
}
