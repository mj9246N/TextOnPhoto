package com.example.textonphoto

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class CanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface CanvasElement {
        var x: Float
        var y: Float
        var rotation: Float
        var locked: Boolean
        fun draw(canvas: Canvas, scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float)
        fun hitTest(touchX: Float, touchY: Float, scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float): Boolean
        fun clone(): CanvasElement
        fun resize(factor: Float) // برای تغییر اندازه یکنواخت
    }

    data class TextElement(
        var text: String,
        override var x: Float,
        override var y: Float,
        var size: Float,
        var typeface: Typeface,
        var fontName: String,
        var color: Int = Color.BLACK,
        override var locked: Boolean = false,
        override var rotation: Float = 0f,
        var underline: Boolean = false
    ) : CanvasElement {
        override fun draw(canvas: Canvas, scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float) {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = this@TextElement.typeface
                textSize = this@TextElement.size * scaleX
                color = this@TextElement.color
            }
            val px = x * scaleX + offsetX
            val py = y * scaleY + offsetY

            canvas.save()
            canvas.rotate(rotation, px, py)
            canvas.drawText(text, px, py, textPaint)

            if (underline) {
                val rect = Rect()
                textPaint.getTextBounds(text, 0, text.length, rect)
                // خط زیر با ضخامت بیشتر
                val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = this@TextElement.color
                    strokeWidth = 4f * scaleX  // ضخامت در فضای واقعی
                    style = Paint.Style.STROKE
                }
                val lineY = py + rect.height() * 0.15f
                canvas.drawLine(px, lineY, px + rect.width(), lineY, underlinePaint)
            }
            canvas.restore()

            if (locked) {
                val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.RED; style = Paint.Style.FILL
                }
                canvas.drawRect(px - 10f, py - textPaint.textSize - 10f, px + 10f, py - textPaint.textSize, lockPaint)
            }
        }

        override fun hitTest(touchX: Float, touchY: Float, scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float): Boolean {
            val px = x * scaleX + offsetX
            val py = y * scaleY + offsetY
            val paint = Paint().apply {
                typeface = this@TextElement.typeface
                textSize = this@TextElement.size * scaleX
            }
            val rect = Rect()
            paint.getTextBounds(text, 0, text.length, rect)
            val angleRad = Math.toRadians(-rotation.toDouble())
            val dx = touchX - px
            val dy = touchY - py
            val rotX = (dx * cos(angleRad) - dy * sin(angleRad)).toFloat() + px
            val rotY = (dx * sin(angleRad) + dy * cos(angleRad)).toFloat() + py
            return rotX in (px)..(px + rect.width()) && rotY in (py - rect.height())..(py)
        }

        override fun clone(): CanvasElement = copy()
        override fun resize(factor: Float) {
            size = (size * factor).coerceIn(5f, 500f)
        }
    }

    data class ImageElement(
        var bitmap: Bitmap,
        override var x: Float,
        override var y: Float,
        var width: Float,
        var height: Float,
        override var rotation: Float = 0f,
        override var locked: Boolean = false
    ) : CanvasElement {
        override fun draw(canvas: Canvas, scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float) {
            val px = x * scaleX + offsetX
            val py = y * scaleY + offsetY
            val w = width * scaleX
            val h = height * scaleY

            canvas.save()
            canvas.rotate(rotation, px, py)
            val destRect = RectF(px - w / 2, py - h / 2, px + w / 2, py + h / 2)
            canvas.drawBitmap(bitmap, null, destRect, null)
            canvas.restore()

            if (locked) {
                val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.RED; style = Paint.Style.FILL
                }
                canvas.drawRect(px - 10f, py - h / 2 - 15f, px + 10f, py - h / 2 + 5f, lockPaint)
            }
        }

        override fun hitTest(touchX: Float, touchY: Float, scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float): Boolean {
            val px = x * scaleX + offsetX
            val py = y * scaleY + offsetY
            val w = width * scaleX
            val h = height * scaleY
            val angleRad = Math.toRadians(-rotation.toDouble())
            val dx = touchX - px
            val dy = touchY - py
            val rotX = (dx * cos(angleRad) - dy * sin(angleRad)).toFloat() + px
            val rotY = (dx * sin(angleRad) + dy * cos(angleRad)).toFloat() + py
            return rotX in (px - w / 2)..(px + w / 2) && rotY in (py - h / 2)..(py + h / 2)
        }

        override fun clone(): CanvasElement = copy(bitmap = bitmap)
        override fun resize(factor: Float) {
            width = (width * factor).coerceIn(20f, 1000f)
            height = (height * factor).coerceIn(20f, 1000f)
        }
    }

    var elements = mutableListOf<CanvasElement>()
    var onElementSelected: ((Int) -> Unit)? = null

    val designWidth = 1280f
    val designHeight = 720f
    private var scaleX = 1f
    private var scaleY = 1f
    private var offsetX = 0f
    private var offsetY = 0f

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
                if (idx != -1 && !elements[idx].locked) {
                    draggingIndex = idx
                    lastTouchX = touchX
                    lastTouchY = touchY
                    onElementSelected?.invoke(idx)
                    performClick()
                } else {
                    draggingIndex = -1
                    onElementSelected?.invoke(if (idx != -1) idx else -1)
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
            if (elements[i].hitTest(touchX, touchY, scaleX, scaleY, offsetX, offsetY)) return i
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)

        for (element in elements) {
            element.draw(canvas, scaleX, scaleY, offsetX, offsetY)
        }
    }
}
