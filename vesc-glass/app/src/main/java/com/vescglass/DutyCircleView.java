package com.vescglass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Circular gauge that fills clockwise to show duty cycle percentage.
 * Draws a dim background ring and a colored foreground arc.
 * The percentage text is drawn centered inside the circle.
 */
public class DutyCircleView extends View {

    private static final float STROKE_WIDTH = 8f;
    private static final float START_ANGLE = -90f; // 12 o'clock

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private float duty; // 0–100
    private int fgColor = Color.WHITE;

    public DutyCircleView(Context context) {
        super(context);
        init();
    }

    public DutyCircleView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        bgPaint.setStyle(Paint.Style.STROKE);
        bgPaint.setStrokeWidth(STROKE_WIDTH);
        bgPaint.setColor(Color.parseColor("#333333"));
        bgPaint.setStrokeCap(Paint.Cap.ROUND);

        fgPaint.setStyle(Paint.Style.STROKE);
        fgPaint.setStrokeWidth(STROKE_WIDTH);
        fgPaint.setColor(fgColor);
        fgPaint.setStrokeCap(Paint.Cap.ROUND);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(64f);
        textPaint.setFakeBoldText(true);

        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(Color.parseColor("#9E9E9E"));
        labelPaint.setTextSize(14f);
    }

    public void setDuty(float pct, int color) {
        this.duty = Math.max(0, Math.min(100, pct));
        this.fgColor = color;
        fgPaint.setColor(color);
        textPaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        float pad = STROKE_WIDTH / 2f + 4f;
        float size = Math.min(w, h);
        float left = (w - size) / 2f + pad;
        float top = (h - size) / 2f + pad;
        arcRect.set(left, top, left + size - 2 * pad, top + size - 2 * pad);

        // Background ring
        canvas.drawArc(arcRect, 0, 360, false, bgPaint);

        // Foreground arc
        float sweep = duty / 100f * 360f;
        if (sweep > 0) {
            canvas.drawArc(arcRect, START_ANGLE, sweep, false, fgPaint);
        }

        // Percentage text centered
        float cx = w / 2f;
        float cy = h / 2f;
        String txt = String.format("%.0f", duty);
        float textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(txt, cx, textY, textPaint);

        // "%" label below
        canvas.drawText("%", cx, textY + textPaint.getTextSize() * 0.55f, labelPaint);
    }
}
