package com.example.textonphoto

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class CanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class TextElement(
        var text: String,
        var x: Float, // موقعیت در فضای طراحی 1280x720
        var y: Float,
        var size: Float,
        var typeface: Typeface,
        var fontName: String,
        var color: Int = Color.BLACK,
        var locked: Boolean = false
    )

    var elements = mutableListOf<TextElement>()
    var onElementSelected: ((Int) -> Unit)? = null

    private val designWidth = 1280f
    private val designHeight = 720f
    private var scaleX = 1f
    private var scaleY = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    // برای جابجایی
    private var draggingIndex = -1
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val viewRatio = w.toFloat() / h.toFloat()
        val designRatio = designWidth / designHeight

        val actualWidth: Float
        val actualHeight: Float
        if (viewRatio > designRatio) {
            actualHeight = h.toFloat()
            actualWidth = h.toFloat() * designRatio
        } else {
            actualWidth = w.toFloat()
            actualHeight = w.toFloat() / designRatio
        }
        setMeasuredDimension(actualWidth.toInt(), actualHeight.toInt())

        scaleX = actualWidth / designWidth
        scaleY = actualHeight / designHeight
        offsetX = 0f
        offsetY = 0f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val touchX = event.x
        val touchY = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val idx = findElementAt(touchX, touchY)
                if (idx != -1) {
                    // شروع کشیدن (در صورت قفل نبودن)
                    if (!elements[idx].locked) {
                        draggingIndex = idx
                        lastTouchX = touchX
                        lastTouchY = touchY
                    } else {
                        draggingIndex = -1
                    }
                    onElementSelected?.invoke(idx)
                    performClick()
                } else {
                    draggingIndex = -1
                    onElementSelected?.invoke(-1)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingIndex != -1) {
                    val dx = (touchX - lastTouchX) / scaleX
                    val dy = (touchY - lastTouchY) / scaleY
                    elements[draggingIndex].x += dx
                    elements[draggingIndex].y += dy
                    lastTouchX = touchX
                    lastTouchY = touchY
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingIndex = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findElementAt(touchX: Float, touchY: Float): Int {
        for (i in elements.indices.reversed()) {
            val e = elements[i]
            val paint = Paint().apply {
                typeface = e.typeface
                textSize = e.size * scaleX
            }
            val rect = Rect()
            paint.getTextBounds(e.text, 0, e.text.length, rect)
            val left = e.x * scaleX + offsetX
            val top = e.y * scaleY + offsetY - rect.height()
            val right = left + rect.width()
            val bottom = top + rect.height()
            if (touchX in left..right && touchY in top..bottom) return i
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)

        // حاشیهٔ بوم
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)

        for (e in elements) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = e.typeface
                textSize = e.size * scaleX
                color = e.color
            }
            val x = e.x * scaleX + offsetX
            val y = e.y * scaleY + offsetY
            canvas.drawText(e.text, x, y, paint)

            if (e.locked) {
                val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.RED
                    style = Paint.Style.FILL
                }
                val lockSize = 10f
                canvas.drawRect(x - lockSize, y - paint.textSize - lockSize, x + lockSize, y - paint.textSize, lockPaint)
            }
        }
    }
}
