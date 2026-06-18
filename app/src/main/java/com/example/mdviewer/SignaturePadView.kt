package com.example.mdviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * A simple finger-drawing surface for capturing a handwritten signature.
 * Produces a trimmed, transparent-background bitmap via [exportTrimmedBitmap].
 */
class SignaturePadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.BLACK
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val path = Path()

    // Explicit bounds of everything drawn (RectF.isEmpty would mishandle zero-size rects).
    private var minX = Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var maxX = Float.MIN_VALUE
    private var maxY = Float.MIN_VALUE
    private var hasContent = false

    private var lastX = 0f
    private var lastY = 0f

    fun isEmpty(): Boolean = !hasContent

    fun clear() {
        path.reset()
        minX = Float.MAX_VALUE
        minY = Float.MAX_VALUE
        maxX = Float.MIN_VALUE
        maxY = Float.MIN_VALUE
        hasContent = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                path.moveTo(x, y)
                lastX = x
                lastY = y
                expandBounds(x, y)
                hasContent = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                // Quadratic smoothing between samples.
                path.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2)
                lastX = x
                lastY = y
                expandBounds(x, y)
            }
            MotionEvent.ACTION_UP -> path.lineTo(x, y)
            else -> return false
        }
        invalidate()
        return true
    }

    private fun expandBounds(x: Float, y: Float) {
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
    }

    /**
     * Returns the drawn signature cropped to its bounds (plus padding), on a
     * transparent background, or null if nothing was drawn.
     */
    fun exportTrimmedBitmap(padding: Int = 24): Bitmap? {
        if (isEmpty()) return null
        val margin = paint.strokeWidth / 2f + padding
        val left = (minX - margin).coerceAtLeast(0f)
        val top = (minY - margin).coerceAtLeast(0f)
        val right = (maxX + margin).coerceAtMost(width.toFloat())
        val bottom = (maxY + margin).coerceAtMost(height.toFloat())

        val w = (right - left).toInt().coerceAtLeast(1)
        val h = (bottom - top).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-left, -top)
        canvas.drawPath(path, paint)
        return bitmap
    }
}
