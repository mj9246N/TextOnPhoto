package com.example.textonphoto

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.*

class CanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class TextAlignment { LEFT, CENTER, RIGHT }

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

    data class TextSegment(
        val text: String,
        val underline: Boolean = false,
        val overline: Boolean = false
    )

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
        override var visible: Boolean = true,
        var alignment: TextAlignment = TextAlignment.RIGHT
    ) : CanvasElement {

        val lines: List<String>
            get() = text.split("\n")

        internal fun getDrawStartX(textWidth: Float, anchorX: Float): Float {
            return when (alignment) {
                TextAlignment.LEFT -> anchorX
                TextAlignment.CENTER -> anchorX - textWidth / 2f
                TextAlignment.RIGHT -> anchorX - textWidth
            }
        }

        internal fun parseLine(line: String): List<TextSegment> {
            val segments = mutableListOf<TextSegment>()
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '۷') {
                    val endIdx = line.indexOf('۷', i + 1)
                    if (endIdx != -1) {
                        val inner = line.substring(i + 1, endIdx)
                        segments.add(TextSegment(inner, underline = true))
                        i = endIdx + 1
                        continue
                    }
                } else if (c == '۶') {
                    val endIdx = line.indexOf('۶', i + 1)
                    if (endIdx != -1) {
                        val inner = line.substring(i + 1, endIdx)
                        segments.add(TextSegment(inner, overline = true))
                        i = endIdx + 1
                        continue
                    }
                }
                // else normal character
                val sb = StringBuilder()
                while (i < line.length && line[i] != '۷' && line[i] != '۶') {
                    sb.append(line[i])
                    i++
                }
                if (sb.isNotEmpty()) {
                    segments.add(TextSegment(sb.toString()))
                }
                // i is already at the next marker or end
            }
            return segments
        }

        private fun isRtl(text: String): Boolean {
            if (text.isEmpty()) return false
            val first = text[0]
            val dir = Character.getDirectionality(first)
            return dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                    dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
        }

        override fun draw(canvas: Canvas, scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float) {
            if (!visible) return

            val lineHeight = size * scaleX * 1.2f
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = this@TextElement.typeface
                textSize = this@TextElement.size * scaleX
                color = this@TextElement.color
            }
            val anchorX = x * scaleX + offsetX
            val anchorY = y * scaleY + offsetY

            canvas.save()
            canvas.rotate(rotation, anchorX, anchorY)

            for ((lineIdx, line) in lines.withIndex()) {
                val segments = parseLine(line)
                if (segments.isEmpty()) continue

                val rtl = isRtl(segments.first().text)

                // محاسبه عرض کل بدون تغییر ترتیب
                var totalWidth = 0f
                for (seg in segments) {
                    totalWidth += textPaint.measureText(seg.text)
                }

                val startX = getDrawStartX(totalWidth, anchorX)
                var currentX = if (rtl) anchorX else startX

                for (seg in segments) {
                    val segWidth = textPaint.measureText(seg.text)
                    val left = if (rtl) currentX - segWidth else currentX

                    canvas.drawText(seg.text, left, anchorY + lineIdx * lineHeight, textPaint)

                    val rect = Rect()
                    textPaint.getTextBounds(seg.text, 0, seg.text.length, rect)
                    if (seg.underline || underline) {
                        val linePaint = Paint().apply {
                            color = this@TextElement.color
                            strokeWidth = 4f * scaleX
                            style = Paint.Style.STROKE
                        }
                        val lineY = anchorY + lineIdx * lineHeight + rect.height() * 0.35f
                        canvas.drawLine(left, lineY, left + segWidth, lineY, linePaint)
                    }
                    if (seg.overline) {
                        val linePaint = Paint().apply {
                            color = this@TextElement.color
                            strokeWidth = 4f * scaleX
                            style = Paint.Style.STROKE
                        }
                        val lineY = anchorY + lineIdx * lineHeight - rect.height() * 1.0f - 4f * scaleX
                        canvas.drawLine(left, lineY, left + segWidth, lineY, linePaint)
                    }

                    currentX = if (rtl) left else left + segWidth
                }
            }

            canvas.restore()

            if (locked) {
                val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.FILL }
                val firstLineWidth = textPaint.measureText(lines.firstOrNull() ?: "")
                val firstStartX = getDrawStartX(firstLineWidth, anchorX)
                val top = anchorY - textPaint.textSize * 0.2f
                canvas.drawRect(firstStartX - 10f, top - 10f, firstStartX + 10f, top, lockPaint)
            }
        }

        override fun hitTest(touchX: Float, touchY: Float, scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float): Boolean {
            if (!visible) return false

            val lineHeight = size * scaleX * 1.2f
            val textPaint = Paint().apply { typeface = this@TextElement.typeface; textSize = this@TextElement.size * scaleX }
            val anchorX = x * scaleX + offsetX
            val anchorY = y * scaleY + offsetY

            var overallLeft = Float.MAX_VALUE
            var overallRight = Float.MIN_VALUE
            var overallTop = Float.MAX_VALUE
            var overallBottom = Float.MIN_VALUE

            for ((i, line) in lines.withIndex()) {
                val segments = parseLine(line)
                var totalWidth = 0f
                for (seg in segments) totalWidth += textPaint.measureText(seg.text)
                val startX = getDrawStartX(totalWidth, anchorX)
                val left = startX
                val top = anchorY + i * lineHeight - textPaint.textSize
                val right = startX + totalWidth
                val bottom = anchorY + i * lineHeight

                overallLeft = min(overallLeft, left)
                overallRight = max(overallRight, right)
                overallTop = min(overallTop, top)
                overallBottom = max(overallBottom, bottom)
            }

            val angleRad = Math.toRadians(-rotation.toDouble())
            val dx = touchX - anchorX
            val dy = touchY - anchorY
            val rotX = (dx * cos(angleRad) - dy * sin(angleRad)).toFloat() + anchorX
            val rotY = (dx * sin(angleRad) + dy * cos(angleRad)).toFloat() + anchorY

            return rotX in overallLeft..overallRight && rotY in overallTop..overallBottom
        }

        override fun clone() = copy()
        override fun resize(factor: Float) { size = (size * factor).coerceIn(5f, 500f) }
        override fun scaleFrom(fromW: Float, fromH: Float, toW: Float, toH: Float) {
            x = x * (toW / fromW)
            y = y * (toH / fromH)
            size = size * (toW / fromW)
        }
        override fun getPreview(): String {
            val firstLine = lines.firstOrNull() ?: ""
            return if (firstLine.length > 15) firstLine.take(15) + "…" else firstLine
        }
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
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    colorFilter = PorterDuffColorFilter(tintColor!!, PorterDuff.Mode.SRC_ATOP)
                }
                canvas.drawBitmap(bitmap, null, destRect, paint)
            } else {
                canvas.drawBitmap(bitmap, null, destRect, null)
            }
            canvas.restore()

            if (locked) {
                val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.RED; style = Paint.Style.FILL
                }
                canvas.drawRect(px - 10f, py - h / 2 - 15f, px + 10f, py - h / 2 + 5f, lockPaint)
            }
        }

        override fun hitTest(touchX: Float, touchY: Float, scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float): Boolean {
            if (!visible) return false
            val px = x * scaleX + offsetX
            val py = y * scaleY + offsetY
            val w = width * scaleX
            val h = height * scaleY
            val a = Math.toRadians(-rotation.toDouble())
            val dx = touchX - px
            val dy = touchY - py
            val rx = (dx * cos(a) - dy * sin(a)).toFloat() + px
            val ry = (dx * sin(a) + dy * cos(a)).toFloat() + py
            return rx in (px - w / 2)..(px + w / 2) && ry in (py - h / 2)..(py + h / 2)
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

    private var scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            return selectedElementIndex != -1 && !elements[selectedElementIndex].locked
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            val el = elements[selectedElementIndex]
            el.resize(factor)
            invalidate()
            return true
        }
    })

    fun changeCanvasSize(newWidth: Float, newHeight: Float) {
        if (newWidth == designWidth && newHeight == designHeight) return
        val oldW = designWidth
        val oldH = designHeight
        designWidth = newWidth
        designHeight = newHeight
        for (e in elements) e.scaleFrom(oldW, oldH, newWidth, newHeight)
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
        scaleGestureDetector.onTouchEvent(event)
        if (scaleGestureDetector.isInProgress) return true

        val tx = event.x; val ty = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val idx = findElementAt(tx, ty)
                if (idx != -1 && !elements[idx].locked) {
                    draggingIndex = idx
                    lastTouchX = tx; lastTouchY = ty
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
                    val dx = (tx - lastTouchX) / scaleX
                    val dy = (ty - lastTouchY) / scaleY
                    elements[draggingIndex].x += dx
                    elements[draggingIndex].y += dy
                    lastTouchX = tx; lastTouchY = ty
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { draggingIndex = -1; return true }
        }
        return super.onTouchEvent(event)
    }

    private fun findElementAt(tx: Float, ty: Float): Int {
        for (i in elements.indices.reversed()) {
            if (elements[i].hitTest(tx, ty, scaleX, scaleY, offsetX, offsetY)) return i
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        val border = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), border)
        for (e in elements) e.draw(canvas, scaleX, scaleY, offsetX, offsetY)
        if (selectedElementIndex in elements.indices) {
            val box = getBoundingBox(elements[selectedElementIndex])
            if (box != null) {
                val hp = Paint().apply {
                    color = Color.parseColor("#FF6200EE"); style = Paint.Style.STROKE
                    strokeWidth = 2f; pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
                }
                canvas.drawRect(box, hp)
            }
        }
    }

    private fun getBoundingBox(el: CanvasElement): RectF? {
        return when (el) {
            is TextElement -> {
                val lineHeight = el.size * scaleX * 1.2f
                val paint = Paint().apply { typeface = el.typeface; textSize = el.size * scaleX }
                val anchorX = el.x * scaleX + offsetX
                val anchorY = el.y * scaleY + offsetY

                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
                var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE

                for ((i, line) in el.lines.withIndex()) {
                    val segments = el.parseLine(line)
                    var totalWidth = 0f
                    for (seg in segments) totalWidth += paint.measureText(seg.text)
                    val startX = el.getDrawStartX(totalWidth, anchorX)
                    val left = startX
                    val top = anchorY + i * lineHeight - paint.textSize
                    val right = startX + totalWidth
                    val bottom = anchorY + i * lineHeight

                    minX = min(minX, left); maxX = max(maxX, right)
                    minY = min(minY, top); maxY = max(maxY, bottom)
                }

                val a = Math.toRadians(el.rotation.toDouble())
                val corners = arrayOf(
                    floatArrayOf(minX, minY), floatArrayOf(maxX, minY),
                    floatArrayOf(maxX, maxY), floatArrayOf(minX, maxY)
                )
                var rminX = Float.MAX_VALUE; var rminY = Float.MAX_VALUE
                var rmaxX = Float.MIN_VALUE; var rmaxY = Float.MIN_VALUE
                for (c in corners) {
                    val dx = c[0] - anchorX; val dy = c[1] - anchorY
                    val rx = (dx * cos(a) - dy * sin(a)).toFloat() + anchorX
                    val ry = (dx * sin(a) + dy * cos(a)).toFloat() + anchorY
                    rminX = min(rminX, rx); rmaxX = max(rmaxX, rx)
                    rminY = min(rminY, ry); rmaxY = max(rmaxY, ry)
                }
                RectF(rminX, rminY, rmaxX, rmaxY)
            }
            is ImageElement -> {
                val px = el.x * scaleX + offsetX; val py = el.y * scaleY + offsetY
                val w = el.width * scaleX; val h = el.height * scaleY
                val left = px - w / 2; val top = py - h / 2; val right = px + w / 2; val bottom = py + h / 2
                val a = Math.toRadians(el.rotation.toDouble())
                val corners = arrayOf(
                    floatArrayOf(left, top), floatArrayOf(right, top),
                    floatArrayOf(right, bottom), floatArrayOf(left, bottom)
                )
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
                var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE
                for (c in corners) {
                    val dx = c[0] - px; val dy = c[1] - py
                    val rx = (dx * cos(a) - dy * sin(a)).toFloat() + px
                    val ry = (dx * sin(a) + dy * cos(a)).toFloat() + py
                    minX = min(minX, rx); maxX = max(maxX, rx)
                    minY = min(minY, ry); maxY = max(maxY, ry)
                }
                RectF(minX, minY, maxX, maxY)
            }
            else -> null
        }
    }
}
