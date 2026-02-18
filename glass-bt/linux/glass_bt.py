#!/usr/bin/env python3
"""Bluetooth RFCOMM client for GlassBT — bidirectional messaging."""
import socket, struct, json, sys, time, threading, signal

SERVICE_UUID = "5e3d4f8a-1b2c-3d4e-5f6a-7b8c9d0e1f2a"
RECONNECT_DELAY = 3

def send_msg(sock, msg):
    payload = json.dumps(msg).encode("utf-8")
    sock.sendall(struct.pack(">I", len(payload)) + payload)

def recv_msg(sock):
    header = b""
    while len(header) < 4:
        chunk = sock.recv(4 - len(header))
        if not chunk:
            raise ConnectionError("Connection closed")
        header += chunk
    length = struct.unpack(">I", header)[0]
    if length <= 0 or length > 65536:
        raise ValueError(f"Invalid message length: {length}")
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            raise ConnectionError("Connection closed during read")
        data += chunk
    return json.loads(data.decode("utf-8"))

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
        import subprocess, re
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
            if e.errno == 111:  # Connection refused — no service
                pass
            elif e.errno == 104:  # Connection reset — service exists
                print(f"  Channel {ch}: open (reset)")
                found.append(ch)
            else:
                pass
    if found:
        ch = found[0]
        print(f"Using channel {ch}")
        return ch
    return None

def scan_devices():
    """Discover nearby Bluetooth devices (requires PyBluez)."""
    try:
        import bluetooth
        print("Scanning for Bluetooth devices...")
        devices = bluetooth.discover_devices(duration=8, lookup_names=True, lookup_class=True)
        if not devices:
            print("No devices found.")
            return
        print(f"\nFound {len(devices)} device(s):")
        for addr, name, cls in devices:
            print(f"  {addr}  {name}  (class 0x{cls:06X})")
    except ImportError:
        print("PyBluez not installed. Install with: pip install pybluez")
        print("Or use 'bluetoothctl devices' to list paired devices.")

def reader_thread(sock, stop_event):
    """Read incoming messages and print them."""
    while not stop_event.is_set():
        try:
            msg = recv_msg(sock)
            msg_type = msg.get("type", "")
            if msg_type == "heartbeat":
                continue
            elif msg_type == "text":
                src = msg.get("from", "?")
                print(f"\n[{src}] {msg.get('text', '')}")
            elif msg_type == "command":
                print(f"\n[glass] cmd: {msg.get('cmd', '')} {json.dumps(msg.get('args', {}))}")
            elif msg_type == "notification":
                print(f"\n[{msg.get('app', '?')}] {msg.get('title', '')}: {msg.get('text', '')}")
            else:
                print(f"\n[?] {json.dumps(msg)}")
            print("> ", end="", flush=True)
        except (ConnectionError, OSError):
            if not stop_event.is_set():
                print("\nConnection lost.")
            break
        except Exception as e:
            if not stop_event.is_set():
                print(f"\nRead error: {e}")
            break

def connect(addr, channel):
    """Connect to Glass RFCOMM server."""
    sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
    sock.connect((addr, channel))
    return sock

def interactive(addr, channel):
    """Interactive session with auto-reconnect."""
    while True:
        try:
            print(f"Connecting to {addr} channel {channel}...")
            sock = connect(addr, channel)
            print("Connected! Type messages, or /cmd, /notify, /quit")
            print()

            stop = threading.Event()
            reader = threading.Thread(target=reader_thread, args=(sock, stop), daemon=True)
            reader.start()

            while True:
                try:
                    line = input("> ").strip()
                except EOFError:
                    line = "/quit"

                if not line:
                    continue

                if line == "/quit":
                    stop.set()
                    sock.close()
                    print("Bye.")
                    return

                try:
                    if line.startswith("/cmd "):
                        parts = line[5:].split(" ", 1)
                        msg = {
                            "type": "command",
                            "ts": int(time.time() * 1000),
                            "from": "linux",
                            "cmd": parts[0],
                            "args": json.loads(parts[1]) if len(parts) > 1 else {}
                        }
                    elif line.startswith("/notify "):
                        parts = line[8:].split("|", 2)
                        msg = {
                            "type": "notification",
                            "ts": int(time.time() * 1000),
                            "from": "linux",
                            "app": parts[0].strip() if len(parts) > 0 else "Linux",
                            "title": parts[1].strip() if len(parts) > 1 else "",
                            "text": parts[2].strip() if len(parts) > 2 else parts[0].strip()
                        }
                    else:
                        msg = {
                            "type": "text",
                            "ts": int(time.time() * 1000),
                            "from": "linux",
                            "text": line
                        }
                    send_msg(sock, msg)
                except (BrokenPipeError, OSError):
                    print("Send failed — connection lost.")
                    break

            stop.set()
            sock.close()

        except (ConnectionRefusedError, OSError) as e:
            print(f"Connection failed: {e}")

        print(f"Reconnecting in {RECONNECT_DELAY}s...")
        time.sleep(RECONNECT_DELAY)

def main():
    import argparse
    parser = argparse.ArgumentParser(description="GlassBT — Bluetooth RFCOMM client")
    parser.add_argument("address", nargs="?", help="Glass Bluetooth MAC address")
    parser.add_argument("-c", "--channel", type=int, help="RFCOMM channel (auto-detect if omitted)")
    parser.add_argument("--scan", action="store_true", help="Scan for nearby Bluetooth devices")
    args = parser.parse_args()

    if args.scan:
        scan_devices()
        return

    if not args.address:
        parser.error("Bluetooth MAC address required (or use --scan)")

    addr = args.address.upper()
    channel = args.channel
    if channel is None:
        channel = find_channel(addr)
        if channel is None:
            print("Could not find RFCOMM channel. Try specifying with -c.")
            sys.exit(1)

    interactive(addr, channel)

if __name__ == "__main__":
    main()
