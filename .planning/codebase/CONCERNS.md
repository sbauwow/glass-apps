# Codebase Concerns

**Analysis Date:** 2026-02-26

## Tech Debt

**Hardcoded Server URLs:**
- Issue: Multiple apps have hardcoded IP addresses and ports embedded directly in source code, making them unusable without recompilation for different environments.
- Files:
  - `glass-clawd/app/src/main/java/com/example/glassclawd/MainActivity.java` (line 46: hardcoded `http://192.168.0.196:8080`)
- Impact: Apps cannot connect to servers on different networks without code modifications. Deployments require source edits and rebuilds. Makes testing across multiple environments difficult.
- Fix approach: Move server URLs to SharedPreferences with intent-based overrides, allow command-line configuration via adb shell intents, or implement a configuration activity.

**Manual JSON Parsing Without Library:**
- Issue: `glass-clawd` uses handwritten JSON string extraction (lines 437-464 in MainActivity.java) to avoid dependencies, with minimal error handling and no support for nested objects or arrays.
- Files: `glass-clawd/app/src/main/java/com/example/glassclawd/MainActivity.java` (methods `extractJsonString`, `escapeJs`)
- Impact: Brittle parsing logic breaks on edge cases (escaped quotes, nested JSON, malformed input). Difficult to maintain. High risk of parsing errors that fail silently.
- Fix approach: Use a lightweight JSON library or move parsing to the server side; if dependencies must be avoided, at least add comprehensive error handling and test harnesses.

**Broad Exception Handling:**
- Issue: Multiple catch blocks use generic `Exception` rather than specific exception types. Error recovery logic logs and continues without distinguishing between recoverable and non-recoverable errors.
- Files:
  - `glass-clawd/app/src/main/java/com/example/glassclawd/MainActivity.java` (lines 354, 430, 584 - catches `Exception`)
  - `glass-display/app/src/main/java/com/glassdisplay/MjpegView.java` (line 107 - catches `Exception`)
- Impact: Difficult to debug errors. Code continues in invalid states (e.g., recording thread might not cleanly stop). Hard to distinguish network failures from audio hardware failures.
- Fix approach: Catch specific exceptions (`IOException`, `IllegalArgumentException`, etc.) and handle each appropriately. Log stack traces to understand failure modes.

**Unsafe Thread State Management:**
- Issue: `glass-clawd` MainActivity uses non-atomic flags (`isRecording`, `touchTracking`) accessed from multiple threads (UI thread, recording thread, gesture handler) without synchronization.
- Files: `glass-clawd/app/src/main/java/com/example/glassclawd/MainActivity.java` (lines 59-67)
- Impact: Race conditions possible. Recording thread might stop abruptly while UI thread thinks it's still running. Touch events might trigger duplicate recordings.
- Fix approach: Use `AtomicBoolean` or synchronize access. Ensure recording thread checks volatile flags frequently. Use `CountDownLatch` or `Thread.join()` for clean shutdown.

**Missing Resource Cleanup:**
- Issue: Multiple classes don't properly clean up resources in error paths or when exceptions occur.
- Files:
  - `glass-clawd/app/src/main/java/com/example/glassclawd/MainActivity.java` (AudioRecord may leak on exception)
  - `glass-display/app/src/main/java/com/glassdisplay/MjpegView.java` (connection cleanup in try-finally is correct, but pattern not universal)
- Impact: Leaked streams, connections, or audio hardware locks device into a bad state. Subsequent recordings fail until app restart.
- Fix approach: Enforce try-finally or try-with-resources for all I/O. Use @NonNull annotations to catch null-dereference bugs at compile time.

## Known Bugs

**Server URL Hardcoding Blocks Multi-Device Deployment:**
- Symptoms: glass-clawd app fails to connect when server is not at 192.168.0.196:8080. User receives "Network error" after tapping to record.
- Files: `glass-clawd/app/src/main/java/com/example/glassclawd/MainActivity.java` (line 46)
- Trigger: Launch app on network other than 192.168.0.X
- Workaround: Edit source, update IP, rebuild APK, reinstall. Or proxy 192.168.0.196 traffic via host firewall rules.

**MJPEG Content-Length Parser Insufficient:**
- Symptoms: glass-display may hang or crash if MJPEG stream sends Content-Length header with extra whitespace or non-numeric characters.
- Files: `glass-display/app/src/main/java/com/glassdisplay/MjpegView.java` (line 153 - Integer.parseInt without bounds check)
- Trigger: Stream from malformed MJPEG source or header injection attack
- Workaround: Ensure upstream MJPEG server is RFC-compliant. Set firewall rules to block untrusted streams.

**JSON Response Parsing Crashes on Escaped Quotes:**
- Symptoms: glass-clawd displays empty chat response or crashes when Claude response contains escaped quotes or backslashes.
- Files: `glass-clawd/app/src/main/java/com/example/glassclawd/MainActivity.java` (method `extractJsonString`, lines 437-464)
- Trigger: Send input that produces response with `\"` or `\\` in the reply text
- Workaround: Use web interface instead of glass-clawd, or ensure input doesn't trigger such responses.

## Security Considerations

**Unencrypted Network Traffic:**
- Risk: All HTTP connections (glass-clawd, glass-display, glass-monitor, glass-stream) use plain HTTP, not HTTPS. Traffic can be intercepted, inspected, or modified by network attackers.
- Files:
  - `glass-clawd/app/src/main/java/com/example/glassclawd/MainActivity.java` (line 46 - `http://`)
  - `glass-display/app/src/main/java/com/glassdisplay/MainActivity.java` (line 98 - `http://` protocol)
  - `glass-monitor/glass_monitor.py` (server serves HTTP by default)
  - `glass-stream/app/src/main/java/com/example/glassstream/MainActivity.java` (HTTP server)
- Current mitigation: Apps assume trusted local network (WiFi). No authentication required.
- Recommendations:
  1. Add optional HTTPS support with self-signed cert
  2. Implement API key or token authentication for server endpoints
  3. Document network security assumptions clearly
  4. For production: use TLS with certificate pinning

**API Key Exposed in Server Environment:**
- Risk: `glass-clawd/server/.env` file contains `ANTHROPIC_API_KEY` in plaintext on disk and in environment.
- Files: `glass-clawd/server/server.py` (lines 28-43 load .env; line 65 reads from `os.environ`)
- Current mitigation: File not included in git, permissions set to 600.
- Recommendations:
  1. Use a secure secrets manager (e.g., HashiCorp Vault, AWS Secrets Manager)
  2. Never pass secrets via environment on shared systems
  3. Rotate API key regularly
  4. Add monitoring for API key usage anomalies

**Weak Session Management in glass-clawd Server:**
- Risk: Single global conversation list (line 53 in server.py) is shared across all connected clients. No user isolation.
- Files: `glass-clawd/server/server.py` (lines 52-53)
- Current mitigation: Assumes single trusted user on private network.
- Recommendations:
  1. Implement per-client session tokens
  2. Add rate limiting on API calls
  3. Implement request signing with HMAC-SHA256 (like refinemirror.com)

**Missing Input Validation on Media Streams:**
- Risk: MjpegView (glass-display) reads Content-Length from untrusted MJPEG stream and allocates memory without bounds checking.
- Files: `glass-display/app/src/main/java/com/glassdisplay/MjpegView.java` (lines 157-160: allocates `new byte[contentLength]` with only max 1MB check)
- Current mitigation: 1MB max frame size is hardcoded check (line 157)
- Recommendations:
  1. Document max frame size limit in comments
  2. Add configurable frame size limits
  3. Implement timeout if frame read takes too long
  4. Validate Content-Length is numeric before parsing

## Performance Bottlenecks

**Synchronous Audio Recording Blocks UI Thread:**
- Problem: glass-clawd recording thread uses blocking `audioRecord.read()` but doesn't return control to main loop frequently. UI may freeze during long recordings.
- Files: `glass-clawd/app/src/main/java/com/example/glassclawd/MainActivity.java` (lines 186-213)
- Cause: Recording happens on separate thread, but 30-second max record timeout (line 52) with no intermediate progress updates.
- Improvement path:
  1. Add periodic progress callbacks (e.g., every 500ms)
  2. Allow cancellation mid-record
  3. Monitor recording thread health; restart if unresponsive

**Frame Parsing in MjpegView Byte-by-Byte:**
- Problem: `readLine()` method (glass-display, line 198-212) reads one byte at a time from socket, very slow on high-latency networks.
- Files: `glass-display/app/src/main/java/com/glassdisplay/MjpegView.java` (line 201: `is.read()`)
- Cause: Simple implementation prioritizes clarity over performance.
- Improvement path:
  1. Use BufferedInputStream wrapper
  2. Implement sliding window buffer for header parsing
  3. Benchmark on slow WiFi (2G/3G latency)

**Unbuffered HTML Streaming in glass-clawd Server:**
- Problem: Server renders large chat histories by injecting JavaScript that appends messages one-by-one via `evaluateJavascript()`. Hundreds of messages cause lag.
- Files: `glass-clawd/server/server.py` (no pagination implemented; all messages kept in memory, lines 52-53)
- Cause: Single global conversation list grows without bounds.
- Improvement path:
  1. Implement message pagination (fetch last N messages)
  2. Add server-side history truncation (keep last 100 messages)
  3. Compress old messages or move to persistent storage

**xrandr Virtual Monitor Creates/Destroys on Each Run:**
- Problem: glass-monitor calls `xrandr --setmonitor GLASS ...` and `xrandr --delmonitor GLASS` on every startup. Slow on systems with many monitors.
- Files: `glass-monitor/glass_monitor.py` (lines 68-90)
- Cause: No persistence of virtual monitor state across runs.
- Improvement path:
  1. Check if GLASS monitor already exists before creating
  2. Reuse existing monitor if dimensions match
  3. Add `--skip-monitor` flag to skip creation entirely

## Fragile Areas

**JSON Escape Handling in glass-clawd:**
- Files: `glass-clawd/app/src/main/java/com/example/glassclawd/MainActivity.java` (methods `extractJsonString`, `escapeJs`, lines 437-471)
- Why fragile: Handwritten escape logic doesn't handle all edge cases. Common issues:
  - `\"` inside a string not properly unescaped
  - `\\` sequences may be double-escaped
  - Unicode escape sequences (`\uXXXX`) not handled
  - Nested quotes in JavaScript template literals (`'...'` inside JavaScript)
- Safe modification:
  1. Add comprehensive unit tests for edge cases
  2. Use a JSON library with proper escape handling
  3. Test with Claude responses containing: quotes, backslashes, newlines, Unicode, HTML entities

**Thread Lifecycle in glass-clawd:**
- Files: `glass-clawd/app/src/main/java/com/example/glassclawd/MainActivity.java` (recording thread, network thread, lines 186-213, 264-269)
- Why fragile: Multiple threads with manual lifecycle management (flags, join with timeout). Hard to reason about cleanup on activity destroy.
- Safe modification:
  1. Use ExecutorService for thread pools
  2. Call shutdown() explicitly in onDestroy()
  3. Ensure onDestroy() waits for threads (with timeout) before returning
  4. Add unit tests for activity lifecycle edge cases (onCreate → onDestroy immediately, pause while recording, etc.)

**MJPEG Header Parsing in glass-display:**
- Files: `glass-display/app/src/main/java/com/glassdisplay/MjpegView.java` (lines 198-212)
- Why fragile: Line-by-line parsing with fixed 256-byte buffer. Breaks if:
  - Header line exceeds 256 bytes
  - Line endings are not standard (e.g., `\n` only, not `\r\n`)
  - MJPEG boundary markers are malformed
  - Server sends unexpected headers
- Safe modification:
  1. Use a more robust MJPEG parser or library
  2. Add extensive logging of raw header bytes for debugging
  3. Test with multiple MJPEG sources (ffmpeg, motion, mjpg-streamer)
  4. Add configurable buffer size with warnings for overflow

## Scaling Limits

**Single-Session In-Memory State in glass-clawd Server:**
- Current capacity: Conversation history limited only by Python process memory. Typical system: 100-500 messages before slowdown.
- Limit: At 1000+ messages, JavaScript evaluation becomes very slow (each message injected individually).
- Scaling path:
  1. Move conversation to SQLite database
  2. Implement per-client sessions with unique IDs
  3. Add pagination endpoints (e.g., `/messages?start=100&count=50`)
  4. Cache rendered HTML to avoid re-evaluation

**MJPEG Client Connections in glass-stream:**
- Current capacity: Depends on device CPU/memory. Estimated 2-5 simultaneous viewers before frame drop.
- Limit: Each client spawns a separate HTTP connection. No connection pooling.
- Scaling path:
  1. Benchmark on actual Glass device to find bottleneck
  2. Implement adaptive quality/FPS based on client count
  3. Add frame skipping if clients fall behind
  4. Document max recommended concurrent clients

**Virtual Monitor Count in glass-monitor:**
- Current capacity: xrandr can handle ~10 virtual monitors before slowdown.
- Limit: Each `--setmonitor` call adds 100-200ms overhead.
- Scaling path:
  1. Create GLASS monitor once, reuse across sessions
  2. Batch xrandr updates if multiple monitors needed
  3. Use alternate display protocol (e.g., Wayland) if X11 becomes bottleneck

## Dependencies at Risk

**faster-whisper Model Download:**
- Risk: `glass-clawd/server/server.py` (line 244) downloads Whisper model on first run. Model sizes: 140MB (tiny) to 3GB (large).
- Impact: First server startup takes 5-30 minutes depending on model and network. No progress indication.
- Migration plan:
  1. Pre-download and cache models with `--models` flag
  2. Implement download progress callback
  3. Consider using OpenAI Whisper API instead (no local model needed, but requires API key)
  4. Document setup requirements clearly

**PIL/Pillow for Image Resize in glass-monitor:**
- Risk: `glass-monitor/glass_monitor.py` (line 118) uses `Image.resize()` which may fail on unsupported pixel formats from mss.
- Impact: Stream crashes with "unsupported image format" error.
- Migration plan:
  1. Add try-except with fallback to simple scaling
  2. Test with various monitor setups (Wayland, Xvfb, unusual resolutions)
  3. Add configuration flag to skip resizing for native 640x360 displays

**API Endpoint Changes in glass-dashboard:**
- Risk: Relies on undocumented ESPN API, Google News RSS feed2json service, Yahoo Finance quotes.
- Impact: If endpoints change or are rate-limited, dashboard stops working silently.
- Migration plan:
  1. Add fallback data sources (e.g., alternative sports APIs)
  2. Implement circuit breaker for failed endpoints
  3. Add configuration to disable individual data sources
  4. Monitor API health and log failures

## Missing Critical Features

**No Offline Mode:**
- Problem: Most Glass apps require network connectivity. If WiFi disconnects, apps fail silently.
- Blocks: Offline productivity (notes, reading, calculating).
- Solution approach:
  1. Implement local SQLite cache for frequently accessed data
  2. Add "last known state" display when network is down
  3. Queue operations and sync when reconnected

**No User Session Isolation (glass-clawd):**
- Problem: All users share the same conversation history.
- Blocks: Multi-user environments, shared Glass devices.
- Solution approach:
  1. Add per-device token/ID
  2. Implement server-side session management
  3. Clear conversation on app exit or after timeout

**No Configuration UI for Server Addresses:**
- Problem: All server URLs and ports are hardcoded.
- Blocks: Testing, deployment, multi-network scenarios.
- Solution approach:
  1. Add settings activity with text input for server host/port
  2. Persist to SharedPreferences
  3. Allow override via intent extras for automation

**Missing Test Coverage:**
- Problem: No unit tests visible for audio recording, JSON parsing, or MJPEG stream decoding.
- Blocks: Refactoring, regression detection, documentation.
- Solution approach:
  1. Add JUnit tests for Java code
  2. Add pytest tests for Python servers
  3. Aim for >70% line coverage on critical paths

## Test Coverage Gaps

**Audio Recording Edge Cases:**
- What's not tested:
  - Recording stops abruptly mid-session
  - Microphone permission revoked while recording
  - Device low on disk space
  - Audio buffer overflows
- Files: `glass-clawd/app/src/main/java/com/example/glassclawd/MainActivity.java` (lines 142-215)
- Risk: Audio cuts off, corrupts data, or crashes app silently
- Priority: High

**JSON Parsing Error Handling:**
- What's not tested:
  - Malformed JSON response from server
  - Missing "reply" or "transcription" fields
  - Null values in response
  - Response larger than expected (2MB+)
- Files: `glass-clawd/app/src/main/java/com/example/glassclawd/MainActivity.java` (lines 375-435)
- Risk: Crash with NullPointerException or IndexOutOfBoundsException
- Priority: High

**MJPEG Stream Disconnection Scenarios:**
- What's not tested:
  - Server closes connection mid-frame
  - Server sends malformed boundary markers
  - Network timeout at various points
  - Multiple reconnects in succession
- Files: `glass-display/app/src/main/java/com/glassdisplay/MjpegView.java` (lines 100-192)
- Risk: Stuck in reconnect loop, consuming battery
- Priority: Medium

**Python Server Exception Paths:**
- What's not tested:
  - API key missing or invalid
  - Whisper model load fails
  - Disk full when writing temp WAV file
  - HTTP client sends invalid multipart
- Files: `glass-clawd/server/server.py` (entire file)
- Risk: Unhandled exceptions crash server, no recovery
- Priority: Medium

---

*Concerns audit: 2026-02-26*
