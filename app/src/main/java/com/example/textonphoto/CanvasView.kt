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

    // اینترفیس عناصر قابل رسم
    interface DrawableElement {
        fun draw(canvas: Canvas, scale: Float, offsetX: Float, offsetY: Float)
        fun hitTest(touchX: Float, touchY: Float, scale: Float, offsetX: Float, offsetY: Float): Boolean
        var x: Float   // موقعیت در فضای طراحی
        var y: Float
        var size: Float
    }

    // فقط عنصر متن (اشکال حذف شدند)
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

    // ابعاد ثابت طراحی (1280x720)
    var designWidth = 1280f
    var designHeight = 720f

    var elements = mutableListOf<DrawableElement>()
    var onElementSelected: ((Int) -> Unit)? = null
    var onCanvasTap: ((Float, Float) -> Unit)? = null  // مختصات در فضای طراحی

    // ماتریس تبدیل برای نمایش در View
    private var viewScale = 1f
    private var viewOffsetX = 0f
    private var viewOffsetY = 0f

    // برای جابجایی و بزرگنمایی با ژست
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragging = false
    private var draggedElementIndex = -1

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            viewScale *= detector.scaleFactor
            viewScale = viewScale.coerceIn(0.2f, 5f)
            // تنظیم offset برای حفظ مرکز
            val focusX = detector.focusX
            val focusY = detector.focusY
            // محاسبه مجدد offset برای بزرگنمایی حول نقطه فوکوس
            viewOffsetX = focusX - (focusX - viewOffsetX) * (detector.scaleFactor)
            viewOffsetY = focusY - (focusY - viewOffsetY) * (detector.scaleFactor)
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val (designX, designY) = screenToDesign(e.x, e.y)
            val idx = findElementAt(e.x, e.y)
            if (idx != -1) {
                onElementSelected?.invoke(idx)
            } else {
                onElementSelected?.invoke(-1)
                onCanvasTap?.invoke(designX, designY)
            }
            return true
        }
    })

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val desiredRatio = designWidth / designHeight
        val actualRatio = w.toFloat() / h.toFloat()

        val newW: Int
        val newH: Int
        if (actualRatio > desiredRatio) {
            // عرض زیاد است، بر اساس ارتفاع مقیاس می‌دهیم
            newH = h
            newW = (h * desiredRatio).toInt()
        } else {
            // ارتفاع زیاد است، بر اساس عرض مقیاس می‌دهیم
            newW = w
            newH = (w / desiredRatio).toInt()
        }
        setMeasuredDimension(newW, newH)

        // محاسبه scale و offset اولیه
        viewScale = newW.toFloat() / designWidth
        viewOffsetX = 0f
        viewOffsetY = 0f
    }

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
                    val dx = (event.x - lastTouchX) / viewScale
                    val dy = (event.y - lastTouchY) / viewScale
                    elements[draggedElementIndex].x += dx
                    elements[draggedElementIndex].y += dy
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                } else if (!scaleDetector.isInProgress) {
                    viewOffsetX += event.x - lastTouchX
                    viewOffsetY += event.y - lastTouchY
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

    private fun screenToDesign(screenX: Float, screenY: Float): Pair<Float, Float> {
        val designX = (screenX - viewOffsetX) / viewScale
        val designY = (screenY - viewOffsetY) / viewScale
        return designX to designY
    }

    private fun findElementAt(screenX: Float, screenY: Float): Int {
        for (i in elements.indices.reversed()) {
            // استفاده از hitTest با مختصات صفحه
            if (elements[i].hitTest(screenX, screenY, viewScale, viewOffsetX, viewOffsetY)) {
                return i
            }
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)

        // ترجمه و مقیاس برای نمایش
        canvas.save()
        canvas.translate(viewOffsetX, viewOffsetY)
        canvas.scale(viewScale, viewScale)

        for (element in elements) {
            element.draw(canvas, 1f, 0f, 0f) // مختصات در فضای طراحی
        }
        canvas.restore()
    }
}
