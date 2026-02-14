#!/usr/bin/env python3
"""
Glass Stream Viewer - View and record MJPEG stream from Glass.

Usage:
    python glass_viewer.py <glass-ip> [port]

Keys:
    r - Toggle recording (saves to glass_recording_TIMESTAMP.mp4)
    s - Save snapshot (saves to glass_snap_TIMESTAMP.jpg)
    q / ESC - Quit
"""

import sys
import time
import cv2
import numpy as np
import urllib.request


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    host = sys.argv[1]
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 8080
    url = f"http://{host}:{port}/stream"

    print(f"Connecting to {url}...")
    stream = urllib.request.urlopen(url)

    recording = False
    writer = None
    buf = b""

    cv2.namedWindow("Glass Stream", cv2.WINDOW_NORMAL)

    try:
        while True:
            chunk = stream.read(4096)
            if not chunk:
                break
            buf += chunk

            # Find JPEG boundaries
            start = buf.find(b"\xff\xd8")
            end = buf.find(b"\xff\xd9")

            if start != -1 and end != -1 and end > start:
                jpg = buf[start : end + 2]
                buf = buf[end + 2 :]

                frame = cv2.imdecode(np.frombuffer(jpg, dtype=np.uint8), cv2.IMREAD_COLOR)
                if frame is None:
                    continue

                # Draw recording indicator
                if recording:
                    cv2.circle(frame, (30, 30), 12, (0, 0, 255), -1)
                    cv2.putText(frame, "REC", (50, 38), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 255), 2)

                cv2.imshow("Glass Stream", frame)

                if recording and writer is not None:
                    writer.write(frame)

                key = cv2.waitKey(1) & 0xFF
                if key == ord("q") or key == 27:
                    break
                elif key == ord("r"):
                    if not recording:
                        ts = time.strftime("%Y%m%d_%H%M%S")
                        filename = f"glass_recording_{ts}.mp4"
                        h, w = frame.shape[:2]
                        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
                        writer = cv2.VideoWriter(filename, fourcc, 15.0, (w, h))
                        recording = True
                        print(f"Recording started: {filename}")
                    else:
                        recording = False
                        if writer:
                            writer.release()
                            writer = None
                        print("Recording stopped")
                elif key == ord("s"):
                    ts = time.strftime("%Y%m%d_%H%M%S")
                    filename = f"glass_snap_{ts}.jpg"
                    cv2.imwrite(filename, frame)
                    print(f"Snapshot saved: {filename}")
    except KeyboardInterrupt:
        pass
    finally:
        if writer:
            writer.release()
        cv2.destroyAllWindows()
        stream.close()
        print("Viewer closed")


if __name__ == "__main__":
    main()
