#!/usr/bin/env python3
"""
Test script: connect to Flipper Zero via USB serial, start screen stream,
and print frame info. For debugging the Flipper RPC side independently.

Usage:
    pip install pyserial
    python test_flipper_ble.py /dev/ttyACM0
"""
import sys
import struct
import time
import serial


def decode_varint(data, offset=0):
    """Decode a protobuf varint. Returns (value, bytes_consumed)."""
    result = 0
    shift = 0
    for i in range(10):
        if offset + i >= len(data):
            return None, 0
        b = data[offset + i]
        result |= (b & 0x7F) << shift
        if (b & 0x80) == 0:
            return result, i + 1
        shift += 7
    return None, 0


def encode_start_screen_stream():
    """Main { command_id=1, gui_start_screen_stream_request={} }"""
    # field 1 (command_id) = 1: 0x08 0x01
    # field 20 (embedded, length 0): 0xa2 0x01 0x00
    payload = bytes([0x08, 0x01, 0xa2, 0x01, 0x00])
    # varint length prefix
    return encode_varint(len(payload)) + payload


def encode_varint(value):
    """Encode an integer as a protobuf varint."""
    result = bytearray()
    while value > 0x7F:
        result.append((value & 0x7F) | 0x80)
        value >>= 7
    result.append(value & 0x7F)
    return bytes(result)


def find_screen_frame(data):
    """Find field 22 (gui_screen_frame) and extract XBM data bytes."""
    # Field 22 tag: (22 << 3) | 2 = 0xB2, then 0x01
    for i in range(len(data) - 1):
        if data[i] == 0xB2 and data[i + 1] == 0x01:
            pos = i + 2
            frame_len, consumed = decode_varint(data, pos)
            if frame_len is None:
                return None
            pos += consumed
            frame_end = pos + frame_len
            if frame_end > len(data):
                return None
            # Find field 1 (data) inside ScreenFrame: tag 0x0A
            for j in range(pos, frame_end):
                if data[j] == 0x0A:
                    data_len, dc = decode_varint(data, j + 1)
                    if data_len is None:
                        return None
                    start = j + 1 + dc
                    return data[start:start + data_len]
    return None


def main():
    port = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyACM0"
    baud = 230400

    print(f"Connecting to {port} at {baud} baud...")
    ser = serial.Serial(port, baud, timeout=2)
    time.sleep(0.5)

    # Start RPC session
    print("Starting RPC session...")
    ser.write(b"start_rpc_session\r")
    time.sleep(0.5)

    # Read until we get a newline (RPC ready)
    resp = ser.read(256)
    if b"\n" not in resp:
        print(f"No RPC ready response, got: {resp!r}")
        return
    print("RPC session active")

    # Send start screen stream
    msg = encode_start_screen_stream()
    print(f"Sending start_screen_stream ({len(msg)} bytes): {msg.hex()}")
    ser.write(msg)

    # Read frames
    buf = bytearray()
    frame_count = 0
    start_time = time.time()

    print("Waiting for frames...")
    try:
        while True:
            chunk = ser.read(1024)
            if not chunk:
                continue
            buf.extend(chunk)

            # Try to decode messages
            while len(buf) > 0:
                msg_len, consumed = decode_varint(buf, 0)
                if msg_len is None:
                    break
                total = consumed + msg_len
                if total > len(buf):
                    break

                # Extract message
                msg_data = bytes(buf[consumed:total])
                buf = buf[total:]

                xbm = find_screen_frame(msg_data)
                if xbm is not None:
                    frame_count += 1
                    elapsed = time.time() - start_time
                    fps = frame_count / elapsed if elapsed > 0 else 0
                    print(f"Frame {frame_count}: {len(xbm)} bytes, "
                          f"{fps:.1f} fps, "
                          f"first 8 bytes: {xbm[:8].hex()}")

    except KeyboardInterrupt:
        print(f"\nStopped. {frame_count} frames received.")
    finally:
        ser.close()


if __name__ == "__main__":
    main()
