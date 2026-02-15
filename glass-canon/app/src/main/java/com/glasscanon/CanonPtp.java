package com.glasscanon;

/**
 * PTP (Picture Transfer Protocol) packet codec for Canon cameras.
 *
 * PTP container format (all little-endian):
 *   uint32 length | uint16 type | uint16 code | uint32 transactionId | uint32[] params
 *
 * Canon vendor-specific extensions used:
 *   0x9110 SetDevicePropValueEx
 *   0x9114 SetRemoteMode
 *   0x9115 SetEventMode
 *   0x910F RemoteRelease (shutter)
 *   0x9153 GetViewFinderData (live view JPEG)
 */
public class CanonPtp {

    // PTP container types
    static final int TYPE_COMMAND  = 0x0001;
    static final int TYPE_DATA    = 0x0002;
    static final int TYPE_RESPONSE = 0x0003;

    // Standard PTP opcodes
    static final int OP_OPEN_SESSION  = 0x1002;
    static final int OP_CLOSE_SESSION = 0x1003;

    // Canon vendor opcodes
    static final int OP_SET_DEVICE_PROP  = 0x9110;
    static final int OP_SET_REMOTE_MODE  = 0x9114;
    static final int OP_SET_EVENT_MODE   = 0x9115;
    static final int OP_REMOTE_RELEASE   = 0x910F;
    static final int OP_GET_LIVE_VIEW    = 0x9153;

    // Canon device properties
    static final int PROP_EVF_OUTPUT_DEVICE = 0xD1B0;

    // PTP response codes
    static final int RESP_OK          = 0x2001;
    static final int RESP_DEVICE_BUSY = 0x2019;

    // JPEG markers
    private static final int JPEG_SOI = 0xFFD8;

    /**
     * Build a PTP command container.
     *
     * Layout: [uint32 length][uint16 TYPE_COMMAND][uint16 opcode][uint32 txnId][uint32... params]
     */
    static byte[] buildCommand(int opcode, int transactionId, int... params) {
        int length = 12 + params.length * 4; // header(12) + params
        byte[] buf = new byte[length];

        putLE32(buf, 0, length);
        putLE16(buf, 4, TYPE_COMMAND);
        putLE16(buf, 6, opcode);
        putLE32(buf, 8, transactionId);

        for (int i = 0; i < params.length; i++) {
            putLE32(buf, 12 + i * 4, params[i]);
        }

        return buf;
    }

    /**
     * Build a PTP data container for SetDevicePropValue.
     *
     * Layout: [uint32 length][uint16 TYPE_DATA][uint16 opcode][uint32 txnId][uint32 value]
     */
    static byte[] buildDataU32(int opcode, int transactionId, int value) {
        int length = 12 + 4; // header(12) + uint32 value
        byte[] buf = new byte[length];

        putLE32(buf, 0, length);
        putLE16(buf, 4, TYPE_DATA);
        putLE16(buf, 6, opcode);
        putLE32(buf, 8, transactionId);
        putLE32(buf, 12, value);

        return buf;
    }

    /**
     * Parse the container type from a PTP response.
     * Returns TYPE_COMMAND, TYPE_DATA, or TYPE_RESPONSE.
     */
    static int parseContainerType(byte[] data, int offset) {
        if (data.length - offset < 6) return -1;
        return getLE16(data, offset + 4);
    }

    /**
     * Parse the container length from a PTP header.
     */
    static int parseContainerLength(byte[] data, int offset) {
        if (data.length - offset < 4) return -1;
        return getLE32(data, offset);
    }

    /**
     * Parse the response code from a PTP response container.
     * Returns the response code (e.g., RESP_OK = 0x2001).
     */
    static int parseResponseCode(byte[] data, int offset) {
        if (data.length - offset < 8) return -1;
        return getLE16(data, offset + 6);
    }

    /**
     * Extract JPEG from GetViewFinderData response data.
     *
     * The live view data payload (after the 12-byte PTP data header) contains
     * one or more segments. We scan for the JPEG SOI marker (0xFF 0xD8) and
     * extract everything from there to the end of the data, since the JPEG
     * image is the primary payload.
     *
     * @param data   Raw data buffer (includes PTP data container header)
     * @param offset Start of the data payload (after PTP 12-byte header)
     * @param length Length of the data payload
     * @return JPEG bytes, or null if no JPEG found
     */
    static byte[] extractLiveViewJpeg(byte[] data, int offset, int length) {
        // Scan for JPEG SOI marker (0xFF 0xD8)
        int end = offset + length - 1;
        for (int i = offset; i < end; i++) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == 0xD8) {
                int jpegLen = offset + length - i;
                byte[] jpeg = new byte[jpegLen];
                System.arraycopy(data, i, jpeg, 0, jpegLen);
                return jpeg;
            }
        }
        return null;
    }

    // ---- Little-endian helpers ----

    static void putLE16(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    static void putLE32(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    static int getLE16(byte[] buf, int offset) {
        return (buf[offset] & 0xFF) | ((buf[offset + 1] & 0xFF) << 8);
    }

    static int getLE32(byte[] buf, int offset) {
        return (buf[offset] & 0xFF)
             | ((buf[offset + 1] & 0xFF) << 8)
             | ((buf[offset + 2] & 0xFF) << 16)
             | ((buf[offset + 3] & 0xFF) << 24);
    }
}
