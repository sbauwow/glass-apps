#!/usr/bin/env python3
"""Send test notifications to Glass notify server."""
import socket
import struct
import json
import sys
import time

HOST = sys.argv[1] if len(sys.argv) > 1 else "localhost"
PORT = 9876

def send_notification(sock, app, title, text):
    payload = json.dumps({
        "app": app,
        "title": title,
        "text": text,
        "time": int(time.time() * 1000)
    }).encode("utf-8")
    sock.sendall(struct.pack(">I", len(payload)) + payload)
    print(f"Sent: {app} - {title}: {text}")

def main():
    print(f"Connecting to {HOST}:{PORT}...")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect((HOST, PORT))
    print("Connected!")

    notifications = [
        ("Messages", "John", "Hey, are you free tonight?"),
        ("Gmail", "Alice Smith", "Meeting moved to 3pm"),
        ("Slack", "#general", "Deployment complete"),
        ("Phone", "Mom", "Incoming call"),
        ("Calendar", "Reminder", "Team standup in 5 minutes"),
    ]

    for app, title, text in notifications:
        send_notification(sock, app, title, text)
        time.sleep(2)

    print("\nAll test notifications sent. Press Enter to disconnect.")
    input()
    sock.close()

if __name__ == "__main__":
    main()
