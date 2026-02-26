# Testing Patterns

**Analysis Date:** 2026-02-26

## Test Framework

**Runner:**
- Python: No formal test runner detected (uses manual test scripts)
- Java/Kotlin (Android): Gradle test framework (via build.gradle)
- No Jest, Vitest, Pytest, or JUnit configuration files found

**Assertion Library:**
- Manual assertion logic in test scripts (no unittest or pytest decorators)
- Android: Standard Android testing libraries available but not used

**Run Commands:**
```bash
# Python: Direct execution of test scripts
python3 test_notify.py [host]
python3 test_flipper_ble.py /dev/ttyACM0

# Android: Build via Gradle
./gradlew build                # Full build
./gradlew assembleDebug        # Debug APK
./gradlew assembleRelease      # Release APK (no minification)
```

## Test File Organization

**Location:**
- Python test files co-located with source: `glass-notify/test_notify.py`, `glass-flipper/test_flipper_ble.py`
- No separate test directories
- Manual test scripts, not automated

**Naming:**
- Python: `test_*.py` convention (e.g., `test_notify.py`, `test_flipper_ble.py`)
- Android: No test files found (tests not integrated)

**Structure:**
```
glass-apps/
├── glass-notify/
│   └── test_notify.py        # Manual test client
├── glass-flipper/
│   └── test_flipper_ble.py   # Manual test client
└── glass-monitor/
    └── glass_monitor.py      # Production code only (no tests)
```

## Test Structure

**Python test pattern:**
```python
#!/usr/bin/env python3
"""Test script: [description]"""
import socket
import struct
import json
import time

def main():
    # Setup (connect to server)
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect((HOST, PORT))

    # Execute (send test data)
    for item in test_items:
        send_data(sock, item)
        time.sleep(2)

    # Teardown
    sock.close()

if __name__ == "__main__":
    main()
```

**Patterns:**
- Setup: Connect to service/device via socket or serial
- Teardown: Close connections in finally or on exit
- Assertion: Manual inspection of output (print statements, logging)

## Mocking

**Framework:** Not used

**Patterns:**
- No mocking libraries (no unittest.mock, Mockito, etc.)
- Tests use real connections: actual TCP sockets, actual serial ports
- Integration-style testing: test scripts connect to running servers

**What to Mock:**
- Network requests would require mocking in unit tests (not done)
- Serial device communication could use serial port mocks (not done)

**What NOT to Mock:**
- Actual service behavior tested end-to-end
- Real protocol parsing (e.g., protobuf varint decoding in test_flipper_ble.py)

**Example (no mocking):**
```python
def main():
    port = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyACM0"
    ser = serial.Serial(port, 230400, timeout=2)  # REAL serial port

    # Send real command
    ser.write(msg)

    # Parse real response
    while True:
        chunk = ser.read(1024)  # Read from actual device
        # ... real protocol decoding
```

## Fixtures and Factories

**Test Data:**
- Python: Hardcoded test data in test functions
- Notification test: `notifications` list with app, title, text tuples
- Flipper test: Hardcoded protobuf encoding functions

```python
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
```

**Location:**
- Inline in test scripts
- No separate fixture files

## Coverage

**Requirements:** Not enforced

**View Coverage:**
- No coverage tracking detected
- No .coverage files or coverage configuration

## Test Types

**Unit Tests:**
- Not formally implemented
- No unit test framework found
- Manual protocol-level testing in scripts

**Integration Tests:**
- Manual test scripts that connect to running services
- `test_notify.py`: Sends notifications to glass-notify server over TCP
- `test_flipper_ble.py`: Connects to Flipper device over serial and validates frame parsing
- Glass Clawd: Manual HTML UI available at `http://localhost:8080/` for web testing

**E2E Tests:**
- Not formalized
- E2E happens through real device connections (actual Glass devices)
- Manual validation: Human uses Glass UI to verify functionality

## Common Patterns

**Async Testing:**
- Python uses asyncio for server but tests are synchronous
- Server's `capture_loop()` and `handle_client()` both async but test sends frames via real network
- No async/await testing patterns found

```python
async def handle_client(broadcaster, reader, writer):
    """Serve MJPEG stream to one HTTP client."""
    # ...
    while True:
        await broadcaster.event.wait()
        # ...
```

**Error Testing:**
- Manual error injection: disconnect socket, send invalid data, test recovery
- Flipper test catches exceptions: `try: ... except KeyboardInterrupt: ...`
- Notify test relies on server error responses in protocol

```python
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
                break  # Incomplete message
            # ...
except KeyboardInterrupt:
    print(f"\nStopped. {frame_count} frames received.")
finally:
    ser.close()
```

## Test Organization Philosophy

**Current state:**
- Minimal testing infrastructure
- Manual/integration-focused approach
- Protocol-level validation of key components
- Real device/service connections for testing

**Limitations:**
- No automated test runner
- No CI/CD integration
- No regression testing
- Manual verification required
- Coverage gaps: Business logic, edge cases, error scenarios

**Recommended direction (if formalizing):**
- Add pytest for Python services
- Add JUnit tests for Android classes (services, activities)
- Separate unit tests (with mocks) from integration tests
- Add GitHub Actions CI pipeline
- Target 70%+ code coverage for new code

---

*Testing analysis: 2026-02-26*
