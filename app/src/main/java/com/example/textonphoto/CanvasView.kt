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
        var visible: Boolean
        fun draw(canvas: Canvas, scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float)
        fun hitTest(touchX: Float, touchY: Float, scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float): Boolean
        fun clone(): CanvasElement
        fun resize(factor: Float)
        fun scaleFrom(fromW: Float, fromH: Float, toW: Float, toH: Float)
        fun getPreview(): String
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
        var underline: Boolean = false,
        override var visible: Boolean = true
    ) : CanvasElement {
        override fun draw(canvas: Canvas, scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float) {
            if (!visible) return
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
                val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = this@TextElement.color
                    strokeWidth = 4f * scaleX
                    style = Paint.Style.STROKE
                }
                val lineY = py + rect.height() * 0.35f
                canvas.drawLine(px, lineY, px + rect.width(), lineY, underlinePaint)
            }
            canvas.restore()

            if (locked) {
                val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.FILL }
                canvas.drawRect(px - 10f, py - textPaint.textSize - 10f, px + 10f, py - textPaint.textSize, lockPaint)
            }
        }

        override fun hitTest(touchX: Float, touchY: Float, scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float): Boolean {
            if (!visible) return false
            val px = x * scaleX + offsetX
            val py = y * scaleY + offsetY
            val paint = Paint().apply { typeface = this@TextElement.typeface; textSize = this@TextElement.size * scaleX }
            val rect = Rect()
            paint.getTextBounds(text, 0, text.length, rect)
            val angleRad = Math.toRadians(-rotation.toDouble())
            val dx = touchX - px
            val dy = touchY - py
            val rotX = (dx * cos(angleRad) - dy * sin(angleRad)).toFloat() + px
            val rotY = (dx * sin(angleRad) + dy * cos(angleRad)).toFloat() + py
            return rotX in (px)..(px + rect.width()) && rotY in (py - rect.height())..(py)
        }

        override fun clone() = copy()
        override fun resize(factor: Float) { size = (size * factor).coerceIn(5f, 500f) }
        override fun scaleFrom(fromW: Float, fromH: Float, toW: Float, toH: Float) {
            x = x * (toW / fromW)
            y = y * (toH / fromH)
            size = size * (toW / fromW)
        }
        override fun getPreview() = if (text.length > 15) text.take(15) + "…" else text
    }

    data class ImageElement(
        var bitmap: Bitmap,
        override var x: Float,
        override var y: Float,
        var width: Float,
        var height: Float,
        override var rotation: Float = 0f,
        override var locked: Boolean = false,
        var tintColor: Int? = null,
        override var visible: Boolean = true
    ) : CanvasElement {
        override fun draw(canvas: Canvas, scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float) {
            if (!visible) return
            val px = x * scaleX + offsetX
            val py = y * scaleY + offsetY
            val w = width * scaleX
            val h = height * scaleY

            canvas.save()
            canvas.rotate(rotation, px, py)
            val destRect = RectF(px - w / 2, py - h / 2, px + w / 2, py + h / 2)

            if (tintColor != null) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.colorFilter = PorterDuffColorFilter(tintColor!!, PorterDuff.Mode.SRC_ATOP)
                canvas.drawBitmap(bitmap, null, destRect, paint)
            } else {
                canvas.drawBitmap(bitmap, null, destRect, null)
            }
            canvas.restore()

            if (locked) {
                val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.FILL }
                canvas.drawRect(px - 10f, py - h / 2 - 15f, px + 10f, py - h / 2 + 5f, lockPaint)
            }
        }

        override fun hitTest(touchX: Float, touchY: Float, scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float): Boolean {
            if (!visible) return false
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

        override fun clone() = copy(bitmap = bitmap)
        override fun resize(factor: Float) {
            width = (width * factor).coerceIn(20f, 1000f)
            height = (height * factor).coerceIn(20f, 1000f)
        }
        override fun scaleFrom(fromW: Float, fromH: Float, toW: Float, toH: Float) {
            x = x * (toW / fromW)
            y = y * (toH / fromH)
            width = width * (toW / fromW)
            height = height * (toH / fromH)
        }
        override fun getPreview() = "🖼️ عکس"
    }

    var elements = mutableListOf<CanvasElement>()
    var onElementSelected: ((Int) -> Unit)? = null
    var selectedElementIndex: Int = -1
        set(value) { field = value; invalidate() }

    var designWidth = 1280f
    var designHeight = 720f

    private var scaleX = 1f
    private var scaleY = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private var draggingIndex = -1
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    fun changeCanvasSize(newWidth: Float, newHeight: Float) {
        if (newWidth == designWidth && newHeight == designHeight) return
        val oldW = designWidth
        val oldH = designHeight
        designWidth = newWidth
        designHeight = newHeight
        for (e in elements) {
            e.scaleFrom(oldW, oldH, newWidth, newHeight)
        }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val designRatio = designWidth / designHeight
        val viewRatio = w.toFloat() / h.toFloat()
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
        val touchX = event.x; val touchY = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val idx = findElementAt(touchX, touchY)
                if (idx != -1 && !elements[idx].locked) {
                    draggingIndex = idx
                    lastTouchX = touchX; lastTouchY = touchY
                    selectedElementIndex = idx
                    onElementSelected?.invoke(idx)
                } else {
                    draggingIndex = -1
                    selectedElementIndex = if (idx != -1) idx else -1
                    onElementSelected?.invoke(idx)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingIndex != -1) {
                    val dx = (touchX - lastTouchX) / scaleX
                    val dy = (touchY - lastTouchY) / scaleY
                    elements[draggingIndex].x += dx
                    elements[draggingIndex].y += dy
                    lastTouchX = touchX; lastTouchY = touchY
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
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)
        for (element in elements) {
            if (element.visible) element.draw(canvas, scaleX, scaleY, offsetX, offsetY)
        }
        if (selectedElementIndex in elements.indices) {
            val el = elements[selectedElementIndex]
            val box = getElementBoundingBox(el)
            if (box != null) {
                val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#FF6200EE"); style = Paint.Style.STROKE
                    strokeWidth = 2f; pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
                }
                canvas.drawRect(box, highlightPaint)
            }
        }
    }

    private fun getElementBoundingBox(element: CanvasElement): RectF? {
        return when (element) {
            is TextElement -> {
                val paint = Paint().apply { typeface = element.typeface; textSize = element.size * scaleX }
                val rect = Rect()
                paint.getTextBounds(element.text, 0, element.text.length, rect)
                val px = element.x * scaleX + offsetX; val py = element.y * scaleY + offsetY
                val left = px; val top = py - rect.height(); val right = px + rect.width(); val bottom = py
                val angle = Math.toRadians(element.rotation.toDouble())
                val corners = arrayOf(
                    floatArrayOf(left, top), floatArrayOf(right, top),
                    floatArrayOf(right, bottom), floatArrayOf(left, bottom)
                )
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
                var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE
                for (corner in corners) {
                    val dx = corner[0] - px; val dy = corner[1] - py
                    val rx = (dx * cos(angle) - dy * sin(angle)).toFloat() + px
                    val ry = (dx * sin(angle) + dy * cos(angle)).toFloat() + py
                    if (rx < minX) minX = rx; if (rx > maxX) maxX = rx
                    if (ry < minY) minY = ry; if (ry > maxY) maxY = ry
                }
                RectF(minX, minY, maxX, maxY)
            }
            is ImageElement -> {
                val px = element.x * scaleX + offsetX; val py = element.y * scaleY + offsetY
                val w = element.width * scaleX; val h = element.height * scaleY
                val left = px - w/2; val top = py - h/2; val right = px + w/2; val bottom = py + h/2
                val angle = Math.toRadians(element.rotation.toDouble())
                val corners = arrayOf(floatArrayOf(left, top), floatArrayOf(right, top), floatArrayOf(right, bottom), floatArrayOf(left, bottom))
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
                var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE
                for (corner in corners) {
                    val dx = corner[0] - px; val dy = corner[1] - py
                    val rx = (dx * cos(angle) - dy * sin(angle)).toFloat() + px
                    val ry = (dx * sin(angle) + dy * cos(angle)).toFloat() + py
                    if (rx < minX) minX = rx; if (rx > maxX) maxX = rx
                    if (ry < minY) minY = ry; if (ry > maxY) maxY = ry
                }
                RectF(minX, minY, maxX, maxY)
            }
            else -> null
        }
    }
}
