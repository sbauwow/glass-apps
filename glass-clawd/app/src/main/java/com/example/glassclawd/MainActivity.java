package com.example.glassclawd;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Glass Clawd — Claude chat via a proxy server, with local audio recording
 * and server-side Whisper transcription.
 *
 * The Glass touchpad is classified as SOURCE_TOUCHSCREEN by the kernel,
 * so touch events go directly to the top-most View. A transparent overlay
 * View sits above the WebView to intercept all touch events for gesture
 * detection. The WebView renders the chat UI but never receives touch input.
 *
 * Triggers:
 * - Touchpad tap: toggle recording
 * - Camera button / D-pad center: toggle recording
 * - Swipe down: go back / exit
 */
public class MainActivity extends Activity {

    private static final String TAG = "GlassClawd";
    private static final String SERVER_URL = "http://192.168.0.196:8080";

    // Audio recording parameters (optimal for Whisper)
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int MAX_RECORD_SECONDS = 30;

    private WebView webView;
    private TextView voiceStatus;

    // Audio recording state
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread;
    private ByteArrayOutputStream pcmBuffer;

    // Touchpad gesture tracking
    private float touchDownX;
    private float touchDownY;
    private long touchDownTime;
    private boolean touchTracking = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        voiceStatus = (TextView) findViewById(R.id.voice_status);
        webView = (WebView) findViewById(R.id.webview);

        setupWebView();

        // Transparent overlay intercepts all touchscreen events (the Glass
        // touchpad is classified as SOURCE_TOUCHSCREEN by the kernel).
        View interceptor = findViewById(R.id.touch_interceptor);
        interceptor.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                handleGestureEvent(event);
                return true;
            }
        });

        webView.loadUrl(SERVER_URL + "/");
        Log.d(TAG, "onCreate complete");

        // Auto-start recording after the page loads
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "Page loaded, auto-starting recording");
                startRecording();
            }
        });
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setBuiltInZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        // JS bridge so the web page can trigger voice input
        webView.addJavascriptInterface(new VoiceBridge(), "GlassVoice");
    }

    /** JavaScript interface exposed to the web page. */
    private class VoiceBridge {
        @JavascriptInterface
        public void startVoice() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    toggleRecording();
                }
            });
        }
    }

    // --- Audio recording ---

    private void toggleRecording() {
        if (isRecording) {
            stopRecordingAndSend();
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        if (isRecording) return;

        // Check permission (should already be granted via manifest on Glass)
        if (checkCallingOrSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted");
            showVoiceStatus("No mic permission");
            return;
        }

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid buffer size: " + bufferSize);
            showVoiceStatus("Mic error");
            return;
        }

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to create AudioRecord", e);
            showVoiceStatus("Mic error");
            return;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized");
            audioRecord.release();
            audioRecord = null;
            showVoiceStatus("Mic error");
            return;
        }

        pcmBuffer = new ByteArrayOutputStream();
        isRecording = true;
        showVoiceStatus("Recording...");
        setVoiceStatusColor(true);

        audioRecord.startRecording();

        final int readBufSize = bufferSize;
        recordingThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buffer = new byte[readBufSize];
                int maxBytes = SAMPLE_RATE * 2 * MAX_RECORD_SECONDS; // 16-bit = 2 bytes/sample
                int totalBytes = 0;

                while (isRecording && totalBytes < maxBytes) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        pcmBuffer.write(buffer, 0, read);
                        totalBytes += read;
                    }
                }

                if (isRecording && totalBytes >= maxBytes) {
                    // Safety timeout reached
                    Log.d(TAG, "Max recording duration reached");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            stopRecordingAndSend();
                        }
                    });
                }
            }
        });
        recordingThread.start();
        Log.d(TAG, "Recording started");
    }

    private void stopRecordingAndSend() {
        if (!isRecording) return;
        isRecording = false;

        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error stopping AudioRecord", e);
            }
            audioRecord.release();
            audioRecord = null;
        }

        // Wait for recording thread to finish
        if (recordingThread != null) {
            try {
                recordingThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted waiting for recording thread", e);
            }
            recordingThread = null;
        }

        byte[] pcmData = pcmBuffer.toByteArray();
        pcmBuffer = null;

        if (pcmData.length < 1600) {
            // Less than 0.05s of audio, ignore
            Log.d(TAG, "Recording too short, ignoring");
            showVoiceStatus("Too short");
            webView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    hideVoiceStatus();
                    startRecording();
                }
            }, 1000);
            return;
        }

        Log.d(TAG, "Recording stopped, " + pcmData.length + " bytes PCM");
        showVoiceStatus("Sending...");
        setVoiceStatusColor(false);

        // Build WAV and send on background thread
        final byte[] wavData = buildWav(pcmData);
        new Thread(new Runnable() {
            @Override
            public void run() {
                sendVoiceToServer(wavData);
            }
        }).start();
    }

    /** Wrap raw PCM data in a WAV header. */
    private byte[] buildWav(byte[] pcmData) {
        int dataLen = pcmData.length;
        int fileLen = 36 + dataLen;

        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataLen);
        try {
            DataOutputStream w = new DataOutputStream(out);
            // RIFF header
            w.writeBytes("RIFF");
            w.writeInt(Integer.reverseBytes(fileLen));
            w.writeBytes("WAVE");
            // fmt chunk
            w.writeBytes("fmt ");
            w.writeInt(Integer.reverseBytes(16));          // chunk size
            w.writeShort(Short.reverseBytes((short) 1));   // PCM format
            w.writeShort(Short.reverseBytes((short) 1));   // mono
            w.writeInt(Integer.reverseBytes(SAMPLE_RATE));  // sample rate
            w.writeInt(Integer.reverseBytes(SAMPLE_RATE * 2)); // byte rate
            w.writeShort(Short.reverseBytes((short) 2));   // block align
            w.writeShort(Short.reverseBytes((short) 16));  // bits per sample
            // data chunk
            w.writeBytes("data");
            w.writeInt(Integer.reverseBytes(dataLen));
            w.write(pcmData);
            w.flush();
        } catch (IOException e) {
            Log.e(TAG, "Error building WAV", e);
        }
        return out.toByteArray();
    }

    /** Send WAV audio to server's /voice endpoint as multipart form data. */
    private void sendVoiceToServer(byte[] wavData) {
        String boundary = "----GlassClawd" + System.currentTimeMillis();
        String url = SERVER_URL + "/voice";

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000); // Whisper + Claude can take a while

            OutputStream os = conn.getOutputStream();

            // Write multipart body
            String header = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"audio\"; filename=\"recording.wav\"\r\n"
                    + "Content-Type: audio/wav\r\n\r\n";
            os.write(header.getBytes("UTF-8"));
            os.write(wavData);
            String footer = "\r\n--" + boundary + "--\r\n";
            os.write(footer.getBytes("UTF-8"));
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();
            InputStream is = (responseCode >= 200 && responseCode < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            ByteArrayOutputStream respBuf = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                respBuf.write(buf, 0, n);
            }
            is.close();

            final String responseBody = respBuf.toString("UTF-8");
            Log.d(TAG, "Server response (" + responseCode + "): " + responseBody);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    handleVoiceResponse(responseBody);
                }
            });

        } catch (final Exception e) {
            Log.e(TAG, "Error sending voice to server", e);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showVoiceStatus("Network error");
                    webView.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            hideVoiceStatus();
                            startRecording();
                        }
                    }, 2000);
                }
            });
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Handle the JSON response from /voice endpoint. */
    private void handleVoiceResponse(String responseBody) {
        try {
            // Manual JSON parsing (no org.json on API 19 Glass builds guaranteed)
            String transcription = extractJsonString(responseBody, "transcription");
            String reply = extractJsonString(responseBody, "reply");
            String error = extractJsonString(responseBody, "error");

            if (error != null && !error.isEmpty()) {
                Log.e(TAG, "Server error: " + error);
                showVoiceStatus("Error");
                webView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        hideVoiceStatus();
                        startRecording();
                    }
                }, 2000);
                return;
            }

            if (transcription == null || transcription.isEmpty()) {
                Log.d(TAG, "Empty transcription");
                showVoiceStatus("No speech");
                webView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        hideVoiceStatus();
                        startRecording();
                    }
                }, 1500);
                return;
            }

            // Display transcription and reply in the chat WebView
            String escapedTranscription = escapeJs(transcription);
            String escapedReply = escapeJs(reply != null ? reply : "");

            String js = "(function() {"
                    + "addMessage('user', '" + escapedTranscription + "');"
                    + "if ('" + escapedReply + "') {"
                    + "  addMessage('assistant', '" + escapedReply + "');"
                    + "}"
                    + "})()";
            webView.evaluateJavascript(js, null);

            hideVoiceStatus();

            // Re-start recording after a brief pause
            webView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startRecording();
                }
            }, 2000);

        } catch (Exception e) {
            Log.e(TAG, "Error parsing voice response", e);
            hideVoiceStatus();
            startRecording();
        }
    }

    /** Simple JSON string value extractor (avoids dependency on org.json). */
    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        idx = json.indexOf(":", idx + search.length());
        if (idx < 0) return null;
        idx = json.indexOf("\"", idx + 1);
        if (idx < 0) return null;
        int start = idx + 1;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"') { sb.append('"'); i++; }
                else if (next == '\\') { sb.append('\\'); i++; }
                else if (next == 'n') { sb.append('\n'); i++; }
                else if (next == 't') { sb.append('\t'); i++; }
                else { sb.append(c); }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String escapeJs(String text) {
        return text.replace("\\", "\\\\")
                   .replace("'", "\\'")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r");
    }

    // --- Touchpad gesture handling ---

    private void handleGestureEvent(MotionEvent event) {
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                touchDownTime = event.getEventTime();
                touchTracking = true;
                Log.d(TAG, "Touch DOWN at " + touchDownX + "," + touchDownY);
                break;

            case MotionEvent.ACTION_UP:
                if (!touchTracking) break;
                touchTracking = false;

                float dx = event.getX() - touchDownX;
                float dy = event.getY() - touchDownY;
                long duration = event.getEventTime() - touchDownTime;

                Log.d(TAG, "Touch UP dx=" + dx + " dy=" + dy + " dur=" + duration);

                // Vertical swipe: scroll chat
                if (Math.abs(dy) > 50 && Math.abs(dy) > Math.abs(dx)) {
                    int scrollAmount = (int) (dy * 2);
                    Log.d(TAG, "Gesture: scroll " + (dy > 0 ? "down" : "up"));
                    webView.scrollBy(0, scrollAmount);
                    break;
                }

                // Swipe left/right: scroll horizontal
                if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy)) {
                    Log.d(TAG, "Gesture: swipe horizontal");
                    webView.scrollBy(dx > 0 ? -100 : 100, 0);
                    break;
                }

                // Tap: toggle recording
                Log.d(TAG, "Gesture: tap -> toggle recording");
                toggleRecording();
                break;

            case MotionEvent.ACTION_MOVE:
                break;
        }
    }

    // --- Key handling ---

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d(TAG, "onKeyDown: " + keyCode);
        switch (keyCode) {
            case KeyEvent.KEYCODE_CAMERA:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                toggleRecording();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (webView.canGoBack()) {
                    webView.goBack();
                    return true;
                }
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    // --- Status indicator ---

    private void showVoiceStatus(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                voiceStatus.setText(text);
                voiceStatus.setVisibility(View.VISIBLE);
            }
        });
    }

    private void hideVoiceStatus() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                voiceStatus.setVisibility(View.GONE);
            }
        });
    }

    private void setVoiceStatusColor(final boolean recording) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (recording) {
                    voiceStatus.setTextColor(0xFFFF4444); // red
                    voiceStatus.setBackgroundResource(R.drawable.bg_voice_recording);
                } else {
                    voiceStatus.setTextColor(0xFFD97757); // orange
                    voiceStatus.setBackgroundResource(R.drawable.bg_voice_button);
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        // Stop any active recording
        isRecording = false;
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception e) { /* ignore */ }
            audioRecord.release();
            audioRecord = null;
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
