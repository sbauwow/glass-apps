package com.example.glasslauncher.service;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * System-wide dialog navigator for Google Glass.
 *
 * When a system dialog appears, shows a transparent overlay that captures
 * Glass touchpad gestures and translates them into accessibility actions:
 *   - Swipe right/left: cycle selection between clickable elements
 *   - Tap: click the selected element
 *   - Swipe down: dismiss (back)
 *
 * The overlay draws a cyan highlight around the currently selected element.
 */
public class DialogNavigator {

    private static final String TAG = "DialogNav";

    // Gesture thresholds (relaxed for Glass touchpad)
    private static final float SWIPE_THRESHOLD = 100f;
    private static final long TAP_TIMEOUT_MS = 500;

    // Highlight style
    private static final int HIGHLIGHT_COLOR = 0xFF00BCD4; // Cyan
    private static final float HIGHLIGHT_STROKE_WIDTH = 6f;

    private final AccessibilityService service;
    private final Handler handler;
    private final WindowManager windowManager;

    private OverlayView overlayView;
    private boolean showing = false;
    private long clickTime = 0; // debounce: ignore events shortly after a click

    private final List<NodeEntry> clickableNodes = new ArrayList<NodeEntry>();
    private int selectedIndex = -1;

    /** Holds a clickable node and its screen bounds. */
    private static class NodeEntry {
        final AccessibilityNodeInfo node;
        final Rect bounds;

        NodeEntry(AccessibilityNodeInfo node, Rect bounds) {
            this.node = node;
            this.bounds = bounds;
        }
    }

    public DialogNavigator(AccessibilityService service) {
        this.service = service;
        this.handler = new Handler(Looper.getMainLooper());
        this.windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean isShowing() {
        return showing;
    }

    /**
     * Scan the node tree for clickable elements and show the overlay.
     */
    public void show(AccessibilityNodeInfo root) {
        // Ignore dialog events right after a click (the dialog is dismissing)
        if (clickTime > 0 && System.currentTimeMillis() - clickTime < 1000) {
            Log.d(TAG, "Ignoring dialog event during click cooldown");
            return;
        }

        if (showing) {
            // Rescan nodes (dialog content may have changed)
            rescan(root);
            return;
        }

        scanClickableNodes(root);
        if (clickableNodes.isEmpty()) {
            Log.d(TAG, "No clickable nodes found in dialog");
            return;
        }

        // Default selection: last element (typically OK/positive button)
        selectedIndex = clickableNodes.size() - 1;

        // Create and show overlay
        overlayView = new OverlayView(service);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        try {
            windowManager.addView(overlayView, params);
            showing = true;
            Log.d(TAG, "Dialog overlay shown with " + clickableNodes.size() + " elements");
        } catch (Exception e) {
            Log.e(TAG, "Failed to show overlay", e);
        }
    }

    /**
     * Remove the overlay and release node references.
     */
    public void hide() {
        if (!showing) return;
        showing = false;

        if (overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove overlay", e);
            }
            overlayView = null;
        }

        recycleNodes();
        selectedIndex = -1;

        Log.d(TAG, "Dialog overlay hidden");
    }

    private void rescan(AccessibilityNodeInfo root) {
        recycleNodes();
        scanClickableNodes(root);
        if (clickableNodes.isEmpty()) {
            hide();
            return;
        }

        // Clamp selection
        if (selectedIndex >= clickableNodes.size()) {
            selectedIndex = clickableNodes.size() - 1;
        }
        if (overlayView != null) {
            overlayView.invalidate();
        }
    }

    private void recycleNodes() {
        for (NodeEntry entry : clickableNodes) {
            try { entry.node.recycle(); } catch (Exception e) { /* already recycled */ }
        }
        clickableNodes.clear();
    }

    /**
     * BFS traversal to find all clickable, visible nodes.
     */
    private void scanClickableNodes(AccessibilityNodeInfo root) {
        Queue<AccessibilityNodeInfo> queue = new LinkedList<AccessibilityNodeInfo>();
        queue.add(root);

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;

            if (node.isClickable() && node.isVisibleToUser()) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                // Skip tiny or zero-size nodes
                if (bounds.width() > 10 && bounds.height() > 10) {
                    clickableNodes.add(new NodeEntry(
                            AccessibilityNodeInfo.obtain(node), bounds));
                }
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }

        // Sort by position: top-to-bottom, then left-to-right
        Collections.sort(clickableNodes, new Comparator<NodeEntry>() {
            @Override
            public int compare(NodeEntry a, NodeEntry b) {
                int dy = a.bounds.top - b.bounds.top;
                if (Math.abs(dy) > 20) return dy; // different row
                return a.bounds.left - b.bounds.left; // same row, sort by X
            }
        });

        Log.d(TAG, "Found " + clickableNodes.size() + " clickable nodes");
        for (int i = 0; i < clickableNodes.size(); i++) {
            NodeEntry e = clickableNodes.get(i);
            CharSequence text = e.node.getText();
            CharSequence desc = e.node.getContentDescription();
            String label = text != null ? text.toString()
                    : (desc != null ? desc.toString() : e.node.getClassName().toString());
            Log.d(TAG, "  [" + i + "] " + label + " @ " + e.bounds);
        }
    }

    // ---- Public actions (called from ButtonRemapService for camera button) ----

    /** Click the currently selected element. Called by camera button press. */
    public void performClick() {
        clickSelected();
    }

    // ---- Gesture actions ----

    private void selectNext() {
        if (clickableNodes.isEmpty()) return;
        selectedIndex = (selectedIndex + 1) % clickableNodes.size();
        Log.d(TAG, "Selected: " + selectedIndex);
        if (overlayView != null) overlayView.invalidate();
    }

    private void selectPrev() {
        if (clickableNodes.isEmpty()) return;
        selectedIndex = (selectedIndex - 1 + clickableNodes.size()) % clickableNodes.size();
        Log.d(TAG, "Selected: " + selectedIndex);
        if (overlayView != null) overlayView.invalidate();
    }

    private void clickSelected() {
        if (selectedIndex < 0 || selectedIndex >= clickableNodes.size()) return;

        clickTime = System.currentTimeMillis();
        NodeEntry entry = clickableNodes.get(selectedIndex);
        try {
            boolean result = entry.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Log.d(TAG, "Clicked node " + selectedIndex + ": " + result);
        } catch (Exception e) {
            Log.w(TAG, "Click failed", e);
        }

        // Hide overlay immediately
        hide();
    }

    private void dismissDialog() {
        Log.d(TAG, "Dismissing dialog (back)");
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        hide();
    }

    // ---- Overlay View ----

    private class OverlayView extends View {

        private final Paint highlightPaint;

        // Gesture tracking
        private float downX, downY;
        private long downTime;

        OverlayView(Context context) {
            super(context);
            setBackgroundColor(Color.TRANSPARENT);

            highlightPaint = new Paint();
            highlightPaint.setColor(HIGHLIGHT_COLOR);
            highlightPaint.setStyle(Paint.Style.STROKE);
            highlightPaint.setStrokeWidth(HIGHLIGHT_STROKE_WIDTH);
            highlightPaint.setAntiAlias(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (selectedIndex >= 0 && selectedIndex < clickableNodes.size()) {
                Rect bounds = clickableNodes.get(selectedIndex).bounds;
                // Inflate slightly so the highlight doesn't overlap button content
                canvas.drawRect(
                        bounds.left - 4, bounds.top - 4,
                        bounds.right + 4, bounds.bottom + 4,
                        highlightPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    downTime = event.getEventTime();
                    return true;

                case MotionEvent.ACTION_UP:
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    long duration = event.getEventTime() - downTime;

                    Log.d(TAG, "Touch UP: dx=" + dx + " dy=" + dy + " duration=" + duration + "ms");

                    if (Math.abs(dx) > SWIPE_THRESHOLD || Math.abs(dy) > SWIPE_THRESHOLD) {
                        if (Math.abs(dx) > Math.abs(dy)) {
                            // Horizontal swipe
                            if (dx > 0) {
                                selectNext();
                            } else {
                                selectPrev();
                            }
                        } else if (dy > 0) {
                            // Swipe down = dismiss
                            dismissDialog();
                        }
                    } else if (duration < TAP_TIMEOUT_MS) {
                        // Tap = click selected
                        clickSelected();
                    } else {
                        Log.d(TAG, "Dead zone: not a swipe, not a tap (duration too long)");
                    }
                    return true;

                default:
                    return true;
            }
        }
    }
}
