package com.example.textonphoto

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

class CanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface DrawableElement {
        fun draw(canvas: Canvas, scale: Float, offsetX: Float, offsetY: Float)
        fun hitTest(touchX: Float, touchY: Float, scale: Float, offsetX: Float, offsetY: Float): Boolean
        var x: Float
        var y: Float
        var size: Float
    }

    data class TextElement(
        var text: String,
        override var x: Float,
        override var y: Float,
        override var size: Float,
        var typeface: Typeface,
        var fontName: String
    ) : DrawableElement {
        override fun draw(canvas: Canvas, scale: Float, offsetX: Float, offsetY: Float) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = size * scale
                this.typeface = typeface
                this.color = Color.BLACK
            }
            canvas.drawText(text, x * scale + offsetX, y * scale + offsetY, paint)
        }

        override fun hitTest(touchX: Float, touchY: Float, scale: Float, offsetX: Float, offsetY: Float): Boolean {
            val rect = Rect()
            val paint = Paint().apply { textSize = size * scale }
            paint.getTextBounds(text, 0, text.length, rect)
            val left = x * scale + offsetX
            val top = y * scale + offsetY - rect.height()
            val right = left + rect.width()
            val bottom = top + rect.height()
            return touchX in left..right && touchY in top..bottom
        }
    }

    data class ShapeElement(
        val type: ShapeType,
        override var x: Float,
        override var y: Float,
        override var size: Float
    ) : DrawableElement {
        enum class ShapeType { SQUARE, RECTANGLE, LINE, CIRCLE }

        override fun draw(canvas: Canvas, scale: Float, offsetX: Float, offsetY: Float) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f
                color = Color.BLACK
            }
            val s = size * scale
            val ox = x * scale + offsetX
            val oy = y * scale + offsetY
            when (type) {
                ShapeType.SQUARE -> canvas.drawRect(ox - s/2, oy - s/2, ox + s/2, oy + s/2, paint)
                ShapeType.RECTANGLE -> canvas.drawRect(ox - s/2, oy - s/4, ox + s/2, oy + s/4, paint)
                ShapeType.LINE -> canvas.drawLine(ox - s/2, oy, ox + s/2, oy, paint)
                ShapeType.CIRCLE -> canvas.drawCircle(ox, oy, s/2, paint)
            }
        }

        override fun hitTest(touchX: Float, touchY: Float, scale: Float, offsetX: Float, offsetY: Float): Boolean {
            val ox = x * scale + offsetX
            val oy = y * scale + offsetY
            val tolerance = 20f
            return touchX in (ox - tolerance)..(ox + tolerance) && touchY in (oy - tolerance)..(oy + tolerance)
        }
    }

    var elements = mutableListOf<DrawableElement>()
    var onElementSelected: ((Int) -> Unit)? = null
    var onCanvasTap: ((Float, Float) -> Unit)? = null

    private var scaleFactor = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragging = false
    private var draggedElementIndex = -1

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            scaleFactor = scaleFactor.coerceIn(0.1f, 5f)
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val idx = findElementAt(e.x, e.y)
            if (idx != -1) {
                onElementSelected?.invoke(idx)
            } else {
                onElementSelected?.invoke(-1)
                onCanvasTap?.invoke(e.x, e.y)
            }
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                draggedElementIndex = findElementAt(event.x, event.y)
                dragging = draggedElementIndex != -1
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && draggedElementIndex != -1) {
                    val dx = (event.x - lastTouchX) / scaleFactor
                    val dy = (event.y - lastTouchY) / scaleFactor
                    elements[draggedElementIndex].x += dx
                    elements[draggedElementIndex].y += dy
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                } else if (!scaleDetector.isInProgress) {
                    offsetX += event.x - lastTouchX
                    offsetY += event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                draggedElementIndex = -1
            }
        }
        return true
    }

    private fun findElementAt(touchX: Float, touchY: Float): Int {
        for (i in elements.indices.reversed()) {
            if (elements[i].hitTest(touchX, touchY, scaleFactor, offsetX, offsetY)) {
                return i
            }
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        for (element in elements) {
            element.draw(canvas, scaleFactor, offsetX, offsetY)
        }
    }
}
