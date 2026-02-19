#!/usr/bin/env python3
"""Stream Linux audio to Google Glass over Bluetooth RFCOMM."""
import socket, struct, json, sys, time, threading, signal, subprocess

SERVICE_UUID = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
RECONNECT_DELAY = 3
CHUNK_SIZE = 4096   # ~93ms at 22.05kHz mono 16-bit — small chunks for smoother flow
SAMPLE_RATE = 22050
CHANNELS = 1
HEARTBEAT_INTERVAL = 15

# Frame types
TYPE_CONFIG    = 0x01
TYPE_AUDIO     = 0x02
TYPE_COMMAND   = 0x03
TYPE_HEARTBEAT = 0x04


def send_frame(sock, frame_type, body=b""):
    """Send a framed message: [4-byte BE length][1-byte type][body]."""
    payload_len = 1 + len(body)
    header = struct.pack(">IB", payload_len, frame_type)
    sock.sendall(header + body)


def recv_frame(sock):
    """Receive a framed message. Returns (type, body)."""
    header = b""
    while len(header) < 4:
        chunk = sock.recv(4 - len(header))
        if not chunk:
            raise ConnectionError("Connection closed")
        header += chunk
    payload_len = struct.unpack(">I", header)[0]
    if payload_len <= 0 or payload_len > 65536:
        raise ValueError(f"Invalid frame length: {payload_len}")

    # Read type byte
    type_buf = b""
    while len(type_buf) < 1:
        chunk = sock.recv(1 - len(type_buf))
        if not chunk:
            raise ConnectionError("Connection closed")
        type_buf += chunk
    frame_type = type_buf[0]

    # Read body
    body_len = payload_len - 1
    body = b""
    while len(body) < body_len:
        chunk = sock.recv(body_len - len(body))
        if not chunk:
            raise ConnectionError("Connection closed during read")
        body += chunk
    return frame_type, body


def find_channel(addr):
    """Find RFCOMM channel via SDP lookup (PyBluez, sdptool, or channel scan)."""
    # Try PyBluez
    try:
        import bluetooth
        services = bluetooth.find_service(uuid=SERVICE_UUID, address=addr)
        if services:
            ch = services[0]["port"]
            print(f"SDP found channel {ch}")
            return ch
    except ImportError:
        pass
    except Exception as e:
        print(f"PyBluez SDP lookup failed: {e}")

    # Try sdptool
    try:
        import re
        result = subprocess.run(
            ["sdptool", "browse", addr], capture_output=True, text=True, timeout=10)
        blocks = result.stdout.split("\n\n")
        for block in blocks:
            if SERVICE_UUID in block.lower():
                m = re.search(r"Channel:\s*(\d+)", block)
                if m:
                    ch = int(m.group(1))
                    print(f"sdptool found channel {ch}")
                    return ch
    except FileNotFoundError:
        pass
    except Exception as e:
        print(f"sdptool lookup failed: {e}")

    # Fallback: try connecting to channels 1-30
    print("Scanning RFCOMM channels 1-30...")
    found = []
    for ch in range(1, 31):
        try:
            s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
            s.settimeout(3)
            s.connect((addr, ch))
            s.close()
            print(f"  Channel {ch}: open")
            found.append(ch)
        except socket.timeout:
            pass
        except OSError as e:
            if e.errno == 111:  # Connection refused
                pass
            elif e.errno == 104:  # Connection reset — service exists
                print(f"  Channel {ch}: open (reset)")
                found.append(ch)
    if found:
        ch = found[0]
        print(f"Using channel {ch}")
        return ch
    return None


def get_default_monitor():
    """Get the PulseAudio monitor source for the default sink."""
    try:
        result = subprocess.run(
            ["pactl", "get-default-sink"], capture_output=True, text=True, timeout=5)
        sink = result.stdout.strip()
        if sink:
            return sink + ".monitor"
    except Exception:
        pass
    return None


def reader_thread(sock, stop_event, paused_event):
    """Read COMMAND frames from Glass (pause/resume)."""
    while not stop_event.is_set():
        try:
            frame_type, body = recv_frame(sock)
            if frame_type == TYPE_COMMAND:
                cmd_json = json.loads(body.decode("utf-8"))
                cmd = cmd_json.get("cmd", "")
                if cmd == "pause":
                    print("\n[Glass] Paused")
                    paused_event.set()
                elif cmd == "resume":
                    print("\n[Glass] Resumed")
                    paused_event.clear()
            elif frame_type == TYPE_HEARTBEAT:
                pass
        except (ConnectionError, OSError):
            if not stop_event.is_set():
                print("\nConnection lost (reader).")
            break
        except Exception as e:
            if not stop_event.is_set():
                print(f"\nRead error: {e}")
            break


def heartbeat_thread(sock, stop_event):
    """Send periodic heartbeats."""
    while not stop_event.is_set():
        try:
            time.sleep(HEARTBEAT_INTERVAL)
            if not stop_event.is_set():
                send_frame(sock, TYPE_HEARTBEAT)
        except (ConnectionError, OSError):
            break


def stream_audio(sock, monitor, stop_event, paused_event, sample_rate, channels):
    """Capture audio via parec and stream to Glass."""
    cmd = [
        "parec",
        "--device=" + monitor,
        "--rate=" + str(sample_rate),
        "--channels=" + str(channels),
        "--format=s16le",
    ]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)

    try:
        while not stop_event.is_set():
            chunk = proc.stdout.read(CHUNK_SIZE)
            if not chunk:
                break
            if paused_event.is_set():
                continue  # Drop audio while paused
            try:
                send_frame(sock, TYPE_AUDIO, chunk)
            except (ConnectionError, OSError):
                break
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            proc.kill()


def connect(addr, channel):
    """Connect to Glass RFCOMM server."""
    sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
    sock.connect((addr, channel))
    return sock


def stream_session(addr, channel, monitor, sample_rate=SAMPLE_RATE, channels=CHANNELS):
    """Run one streaming session with auto-reconnect."""
    while True:
        try:
            print(f"Connecting to {addr} channel {channel}...")
            sock = connect(addr, channel)
            print("Connected!")

            # Send CONFIG frame
            config = json.dumps({
                "sample_rate": sample_rate,
                "channels": channels,
                "encoding": "pcm_16bit_le",
            }).encode("utf-8")
            send_frame(sock, TYPE_CONFIG, config)
            print(f"Streaming: {sample_rate}Hz {channels}ch 16-bit from {monitor}")

            stop = threading.Event()
            paused = threading.Event()

            reader = threading.Thread(
                target=reader_thread, args=(sock, stop, paused), daemon=True)
            reader.start()

            hb = threading.Thread(
                target=heartbeat_thread, args=(sock, stop), daemon=True)
            hb.start()

            # Stream audio (blocks until disconnect or stop)
            stream_audio(sock, monitor, stop, paused, sample_rate, channels)

            stop.set()
            sock.close()

        except (ConnectionRefusedError, OSError) as e:
            print(f"Connection failed: {e}")

        print(f"Reconnecting in {RECONNECT_DELAY}s...")
        time.sleep(RECONNECT_DELAY)


def main():
    import argparse
    parser = argparse.ArgumentParser(
        description="GlassMusic — Stream Linux audio to Google Glass over Bluetooth")
    parser.add_argument("address", nargs="?", help="Glass Bluetooth MAC address")
    parser.add_argument("-c", "--channel", type=int,
                        help="RFCOMM channel (auto-detect if omitted)")
    parser.add_argument("-d", "--device", type=str,
                        help="PulseAudio monitor source (auto-detect if omitted)")
    parser.add_argument("--rate", type=int, default=SAMPLE_RATE,
                        help=f"Sample rate (default: {SAMPLE_RATE})")
    parser.add_argument("--channels", type=int, default=CHANNELS,
                        help=f"Channel count (default: {CHANNELS})")
    parser.add_argument("--scan", action="store_true",
                        help="Scan for nearby Bluetooth devices")
    args = parser.parse_args()

    if args.scan:
        try:
            import bluetooth
            print("Scanning for Bluetooth devices...")
            devices = bluetooth.discover_devices(
                duration=8, lookup_names=True, lookup_class=True)
            if not devices:
                print("No devices found.")
                return
            print(f"\nFound {len(devices)} device(s):")
            for addr, name, cls in devices:
                print(f"  {addr}  {name}  (class 0x{cls:06X})")
        except ImportError:
            print("PyBluez not installed. Use 'bluetoothctl devices' to list paired devices.")
        return

    if not args.address:
        parser.error("Bluetooth MAC address required (or use --scan)")

    sample_rate = args.rate
    channels = args.channels

    addr = args.address.upper()

    # Find RFCOMM channel
    channel = args.channel
    if channel is None:
        channel = find_channel(addr)
        if channel is None:
            print("Could not find RFCOMM channel. Try specifying with -c.")
            sys.exit(1)

    # Find audio monitor source
    monitor = args.device
    if monitor is None:
        monitor = get_default_monitor()
        if monitor is None:
            print("Could not detect PulseAudio monitor. Specify with -d.")
            sys.exit(1)
    print(f"Audio source: {monitor}")

    # Handle Ctrl+C
    signal.signal(signal.SIGINT, lambda *_: sys.exit(0))

    stream_session(addr, channel, monitor, sample_rate, channels)


if __name__ == "__main__":
    main()
