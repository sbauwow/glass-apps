package com.phoneinput

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class TouchpadView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onMove: ((dx: Int, dy: Int) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onFingerDown: (() -> Unit)? = null
    var onFingerUp: (() -> Unit)? = null

    var sensitivity = 1.5f

    private var lastX = 0f
    private var lastY = 0f
    private var accX = 0f
    private var accY = 0f
    private var touchX = 0f
    private var touchY = 0f
    private var touching = false
    private var totalMovement = 0f
    private var downTime = 0L

    private val TAP_MAX_MOVEMENT = 15f
    private val TAP_MAX_DURATION = 300L

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x554FC3F7.toInt()
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF444444.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF555555.toInt()
        textSize = 40f
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)

        if (!touching) {
            canvas.drawText("TOUCHPAD", width / 2f, height / 2f + labelPaint.textSize / 3, labelPaint)
        }

        if (touching) {
            canvas.drawCircle(touchX, touchY, 40f, dotPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                touchX = event.x
                touchY = event.y
                accX = 0f
                accY = 0f
                totalMovement = 0f
                touching = true
                downTime = System.currentTimeMillis()
                onFingerDown?.invoke()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val rawDx = (event.x - lastX) * sensitivity
                val rawDy = (event.y - lastY) * sensitivity
                totalMovement += Math.abs(rawDx) + Math.abs(rawDy)

                accX += rawDx
                accY += rawDy

                val intDx = accX.toInt()
                val intDy = accY.toInt()

                if (intDx != 0 || intDy != 0) {
                    // Send in chunks of max 127 if needed
                    var remainX = intDx
                    var remainY = intDy
                    while (remainX != 0 || remainY != 0) {
                        val sendX = remainX.coerceIn(-128, 127)
                        val sendY = remainY.coerceIn(-128, 127)
                        onMove?.invoke(sendX, sendY)
                        remainX -= sendX
                        remainY -= sendY
                    }
                    accX -= intDx
                    accY -= intDy
                }

                lastX = event.x
                lastY = event.y
                touchX = event.x
                touchY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val duration = System.currentTimeMillis() - downTime
                if (totalMovement < TAP_MAX_MOVEMENT && duration < TAP_MAX_DURATION) {
                    onTap?.invoke()
                } else {
                    onFingerUp?.invoke()
                }
                touching = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
