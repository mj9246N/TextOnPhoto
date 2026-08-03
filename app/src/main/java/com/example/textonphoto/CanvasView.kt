package com.example.textonphoto

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
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
        var fontName: String,
        var color: Int = Color.BLACK,
        var locked: Boolean = false
    ) : DrawableElement {
        override fun draw(canvas: Canvas, scale: Float, offsetX: Float, offsetY: Float) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = size * scale
                this.typeface = typeface
                this.color = color
            }
            canvas.drawText(text, x * scale + offsetX, y * scale + offsetY, paint)
            if (locked) {
                // نشان دادن قفل (یک مربع کوچک)
                val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.RED
                    this.style = Paint.Style.FILL
                }
                val lockSize = 12f * scale
                canvas.drawRect(
                    (x * scale + offsetX) - lockSize / 2,
                    (y * scale + offsetY) - lockSize / 2 - paint.textSize * scale * 0.7f,
                    (x * scale + offsetX) + lockSize / 2,
                    (y * scale + offsetY) - lockSize / 2 - paint.textSize * scale * 0.7f + lockSize,
                    lockPaint
                )
            }
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

    var designWidth = 1280f
    var designHeight = 720f

    var elements = mutableListOf<DrawableElement>()
    var onElementSelected: ((Int) -> Unit)? = null
    var onCanvasTap: ((Float, Float) -> Unit)? = null  // مختصات طراحی

    private var viewScale = 1f
    private var viewOffsetX = 0f
    private var viewOffsetY = 0f

    // برای جابجایی
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragging = false
    private var draggedElementIndex = -1

    // برای تشخیص ضربه
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private val tapThreshold = 200 // میلی‌ثانیه
    private val moveThreshold = 10f // پیکسل

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            viewScale *= detector.scaleFactor
            viewScale = viewScale.coerceIn(0.2f, 5f)
            val focusX = detector.focusX
            val focusY = detector.focusY
            viewOffsetX = focusX - (focusX - viewOffsetX) * (detector.scaleFactor)
            viewOffsetY = focusY - (focusY - viewOffsetY) * (detector.scaleFactor)
            invalidate()
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
            newH = h
            newW = (h * desiredRatio).toInt()
        } else {
            newW = w
            newH = (w / desiredRatio).toInt()
        }
        setMeasuredDimension(newW, newH)

        viewScale = newW.toFloat() / designWidth
        viewOffsetX = 0f
        viewOffsetY = 0f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                downTime = System.currentTimeMillis()
                downX = event.x
                downY = event.y

                draggedElementIndex = findElementAt(event.x, event.y)
                // اگر عنصر قفل باشد، نمی‌توان کشید
                if (draggedElementIndex != -1 && (elements[draggedElementIndex] as? TextElement)?.locked == true) {
                    draggedElementIndex = -1
                    dragging = false
                } else {
                    dragging = draggedElementIndex != -1
                }
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
                val upTime = System.currentTimeMillis()
                val dx = event.x - downX
                val dy = event.y - downY
                if (upTime - downTime < tapThreshold && Math.abs(dx) < moveThreshold && Math.abs(dy) < moveThreshold) {
                    // ضربهٔ ساده
                    handleTap(event.x, event.y)
                }
                dragging = false
                draggedElementIndex = -1
            }
        }
        return true
    }

    private fun handleTap(screenX: Float, screenY: Float) {
        val idx = findElementAt(screenX, screenY)
        if (idx != -1) {
            onElementSelected?.invoke(idx)
        } else {
            onElementSelected?.invoke(-1)
            // تبدیل به مختصات طراحی
            val designX = (screenX - viewOffsetX) / viewScale
            val designY = (screenY - viewOffsetY) / viewScale
            onCanvasTap?.invoke(designX.coerceIn(0f, designWidth), designY.coerceIn(0f, designHeight))
        }
    }

    private fun findElementAt(screenX: Float, screenY: Float): Int {
        for (i in elements.indices.reversed()) {
            if (elements[i].hitTest(screenX, screenY, viewScale, viewOffsetX, viewOffsetY)) {
                return i
            }
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)

        // رسم کادر بوم طراحی
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRect(0f, 0f, designWidth * viewScale + viewOffsetX, designHeight * viewScale + viewOffsetY, borderPaint)

        canvas.save()
        canvas.translate(viewOffsetX, viewOffsetY)
        canvas.scale(viewScale, viewScale)

        // رسم محدودهٔ طراحی با یک مستطیل محو (اختیاری)
        val bgPaint = Paint().apply {
            color = Color.argb(20, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRect(0f, 0f, designWidth, designHeight, bgPaint)

        for (element in elements) {
            element.draw(canvas, 1f, 0f, 0f)
        }
        canvas.restore()
    }
}
