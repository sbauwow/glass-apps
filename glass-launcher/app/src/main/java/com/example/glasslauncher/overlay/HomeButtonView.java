package com.example.glasslauncher.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Custom-drawn home button indicator for the floating overlay.
 * Draws a semi-transparent circle with a simple house icon inside.
 * Visual-only since Glass Explorer has no touchscreen.
 */
public class HomeButtonView extends View {

    private Paint circlePaint;
    private Paint iconPaint;
    private Paint strokePaint;

    public HomeButtonView(Context context) {
        super(context);
        init();
    }

    public HomeButtonView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public HomeButtonView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(Color.argb(136, 255, 255, 255)); // #88FFFFFF
        circlePaint.setStyle(Paint.Style.FILL);

        strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setColor(Color.argb(204, 255, 255, 255)); // #CCFFFFFF
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2f);

        iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        iconPaint.setColor(Color.WHITE);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(2.5f);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(cx, cy) - 2f;

        // Background circle
        canvas.drawCircle(cx, cy, radius, circlePaint);
        canvas.drawCircle(cx, cy, radius, strokePaint);

        // Draw a simple house icon centered in the circle
        float iconSize = radius * 0.55f;

        // Roof (triangle)
        Path roof = new Path();
        roof.moveTo(cx, cy - iconSize);                    // top
        roof.lineTo(cx - iconSize, cy);                     // bottom-left
        roof.lineTo(cx + iconSize, cy);                     // bottom-right
        roof.close();
        canvas.drawPath(roof, iconPaint);

        // House body (rectangle below roof)
        float bodyLeft = cx - iconSize * 0.65f;
        float bodyRight = cx + iconSize * 0.65f;
        float bodyTop = cy;
        float bodyBottom = cy + iconSize * 0.7f;

        Path body = new Path();
        body.moveTo(bodyLeft, bodyTop);
        body.lineTo(bodyLeft, bodyBottom);
        body.lineTo(bodyRight, bodyBottom);
        body.lineTo(bodyRight, bodyTop);
        canvas.drawPath(body, iconPaint);
    }
}
