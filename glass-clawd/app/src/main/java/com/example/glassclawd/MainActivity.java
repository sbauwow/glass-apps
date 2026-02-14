package com.example.glassclawd;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
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

import java.util.ArrayList;

/**
 * Glass Clawd — Claude chat via a proxy server, with native Glass voice input.
 *
 * The Glass touchpad is classified as SOURCE_TOUCHSCREEN by the kernel,
 * so touch events go directly to the top-most View. A transparent overlay
 * View sits above the WebView to intercept all touch events for gesture
 * detection. The WebView renders the chat UI but never receives touch input.
 *
 * Triggers:
 * - Touchpad tap: launch voice recognition
 * - Camera button / D-pad center: launch voice recognition
 * - Swipe down: go back / exit
 */
public class MainActivity extends Activity {

    private static final String TAG = "GlassClawd";
    private static final String CLAUDE_URL = "http://192.168.0.196:8080/";
    private static final int SPEECH_REQUEST_CODE = 100;

    private WebView webView;
    private TextView voiceStatus;

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

        webView.loadUrl(CLAUDE_URL);
        Log.d(TAG, "onCreate complete");

        // Auto-launch voice recognition after the page loads
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "Page loaded, auto-launching voice");
                launchVoiceRecognition();
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
                    launchVoiceRecognition();
                }
            });
        }
    }

    // --- Voice recognition via native Activity ---

    private void launchVoiceRecognition() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Claude");
        try {
            startActivityForResult(intent, SPEECH_REQUEST_CODE);
            Log.d(TAG, "Voice recognition launched");
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "Speech recognition activity not found", e);
            showVoiceStatus("No voice");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SPEECH_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> results = data.getStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS);
                if (results != null && !results.isEmpty()) {
                    String text = results.get(0);
                    Log.d(TAG, "Voice recognized: " + text);
                    sendToChat(text);
                }
            } else {
                Log.d(TAG, "Voice cancelled or failed, resultCode=" + resultCode);
            }
            hideVoiceStatus();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** Inject text into the proxy chat and send it, then re-listen after response. */
    private void sendToChat(final String text) {
        String escaped = text.replace("\\", "\\\\")
                             .replace("'", "\\'")
                             .replace("\n", "\\n");

        // Call sendMessage and poll for response completion, then re-launch voice
        String js = "(function() {" +
                "var el = document.getElementById('input');" +
                "if (el) { el.value = ''; }" +
                "sendMessage('" + escaped + "');" +
                "})()";

        webView.evaluateJavascript(js, null);
        showVoiceStatus("Thinking...");

        // Poll the page to check when Claude responds, then re-launch voice
        pollForResponse();
    }

    private int pollCount = 0;

    private void pollForResponse() {
        pollCount = 0;
        pollStep();
    }

    private void pollStep() {
        pollCount++;
        if (pollCount > 60) {
            // Timeout after ~30s, re-launch voice anyway
            hideVoiceStatus();
            launchVoiceRecognition();
            return;
        }

        webView.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Check if the 'sending' JS variable is false (response received)
                webView.evaluateJavascript("sending", new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if ("false".equals(value)) {
                            Log.d(TAG, "Response received, re-launching voice");
                            hideVoiceStatus();
                            // Small delay to let user see the response
                            webView.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    launchVoiceRecognition();
                                }
                            }, 2000);
                        } else {
                            pollStep();
                        }
                    }
                });
            }
        }, 500);
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

                // Swipe down: go back
                if (Math.abs(dy) > 50 && dy > 0 && Math.abs(dy) > Math.abs(dx)) {
                    Log.d(TAG, "Gesture: swipe down");
                    if (webView.canGoBack()) {
                        webView.goBack();
                    } else {
                        finish();
                    }
                    break;
                }

                // Swipe left/right: scroll chat
                if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy)) {
                    Log.d(TAG, "Gesture: swipe horizontal");
                    webView.scrollBy(dx > 0 ? -100 : 100, 0);
                    break;
                }

                // Tap: launch voice
                Log.d(TAG, "Gesture: tap -> voice");
                launchVoiceRecognition();
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
                launchVoiceRecognition();
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

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
