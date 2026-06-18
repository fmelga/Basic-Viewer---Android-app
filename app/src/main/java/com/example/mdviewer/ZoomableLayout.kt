package com.example.mdviewer

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Wraps a single child (a vertically-scrolling RecyclerView) and adds pinch-to-zoom
 * plus horizontal panning, while leaving vertical scrolling to the child so the user
 * can still move between pages while zoomed in.
 *
 * Model: the child is scaled about its top-left. Only a horizontal translation is kept
 * (vertical navigation = the RecyclerView's own scroll). Zoom can be disabled (e.g. while
 * signing) so coordinate mapping sees untransformed pages.
 */
class ZoomableLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val minScale = 1f
    private val maxScale = 5f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var scale = 1f
    private var transX = 0f

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var panning = false

    var zoomEnabled = true

    private val scaleDetector =
        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (scale * detector.scaleFactor).coerceIn(minScale, maxScale)
                // Keep the horizontal focal point anchored while scaling.
                transX = detector.focusX - (detector.focusX - transX) * (newScale / scale)
                scale = newScale
                clampAndApply()
                return true
            }
        })

    private fun child() = if (childCount > 0) getChildAt(0) else null

    fun reset() {
        scale = 1f
        transX = 0f
        clampAndApply()
    }

    private fun clampAndApply() {
        val c = child() ?: return
        val minX = width - c.width * scale
        transX = transX.coerceIn(minOf(minX, 0f), 0f)
        c.pivotX = 0f
        c.pivotY = 0f
        c.scaleX = scale
        c.scaleY = scale
        c.translationX = transX
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!zoomEnabled) return false
        scaleDetector.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                panning = false
            }
            MotionEvent.ACTION_MOVE -> {
                // Steal the gesture only for a clearly-horizontal drag while zoomed,
                // so vertical scrolling keeps flowing to the RecyclerView.
                if (scale > 1f && !panning) {
                    val dx = abs(ev.x - downX)
                    val dy = abs(ev.y - downY)
                    if (dx > touchSlop && dx > dy) panning = true
                }
            }
        }
        return scaleDetector.isInProgress || panning
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!zoomEnabled) return false
        scaleDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.x
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && scale > 1f) {
                    transX += ev.x - lastX
                    clampAndApply()
                }
                lastX = ev.x
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> panning = false
        }
        return true
    }
}
