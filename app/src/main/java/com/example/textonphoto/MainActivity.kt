package com.example.textonphoto

import android.app.AlertDialog
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var canvasView: CanvasView
    private var selectedIndex = -1
    private val fontMap = mutableMapOf<String, Typeface>()
    private val fontNames = mutableListOf<String>()
    private lateinit var tvSize: TextView
    private lateinit var tvRotation: TextView
    private lateinit var btnLock: Button
    private lateinit var btnStyle: Button

    private val undoStack = mutableListOf<List<CanvasView.CanvasElement>>()
    private val MAX_UNDO = 100

    private val openFontLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { loadFont(it) } }

    private val pickStickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { addSticker(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        canvasView = findViewById(R.id.canvasView)
        tvSize = findViewById(R.id.tvSize)
        tvRotation = findViewById(R.id.tvRotation)
        btnLock = findViewById(R.id.btnLock)
        btnStyle = findViewById(R.id.btnStyle)

        loadStoredFonts()
        pushUndo()

        canvasView.onElementSelected = { idx ->
            selectedIndex = idx
            updateUI()
        }

        findViewById<Button>(R.id.btnAddText).setOnClickListener { showAddTextDialog() }

        findViewById<Button>(R.id.btnEdit).setOnClickListener {
            if (selectedIndex == -1) return@setOnClickListener
            val el = canvasView.elements[selectedIndex]
            if (el is CanvasView.TextElement) {
                if (el.locked) { toast("قفل است"); return@setOnClickListener }
                showEditTextDialog(el)
            }
        }

        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            if (selectedIndex == -1) return@setOnClickListener
            pushUndo()
            val copy = canvasView.elements[selectedIndex].clone()
            copy.x += 30f; copy.y += 30f
            canvasView.elements.add(copy)
            selectedIndex = canvasView.elements.size - 1
            canvasView.invalidate()
            updateUI()
        }

        findViewById<Button>(R.id.btnDelete).setOnClickListener {
            if (selectedIndex == -1) return@setOnClickListener
            if (canvasView.elements[selectedIndex].locked) { toast("قفل است"); return@setOnClickListener }
            pushUndo()
            canvasView.elements.removeAt(selectedIndex)
            selectedIndex = -1
            canvasView.invalidate()
            updateUI()
        }

        findViewById<Button>(R.id.btnFont).setOnClickListener {
            if (selectedIndex == -1 || canvasView.elements[selectedIndex] !is CanvasView.TextElement) {
                toast("فقط برای متن")
                return@setOnClickListener
            }
            showFontPicker()
        }

        findViewById<Button>(R.id.btnColor).setOnClickListener {
            if (selectedIndex == -1) return@setOnClickListener
            val el = canvasView.elements[selectedIndex]
            if (el is CanvasView.TextElement) {
                if (el.locked) { toast("قفل است"); return@setOnClickListener }
                showColorPicker()
            }
        }

        btnStyle.setOnClickListener {
            if (selectedIndex == -1) return@setOnClickListener
            val el = canvasView.elements[selectedIndex]
            if (el is CanvasView.TextElement) {
                if (el.locked) { toast("قفل است"); return@setOnClickListener }
                pushUndo()
                el.underline = !el.underline
                btnStyle.text = if (el.underline) "زیرخط ✓" else "زیرخط"
                canvasView.invalidate()
            }
        }

        findViewById<Button>(R.id.btnRotateLeft).setOnClickListener { rotateSelected(-5f) }
        findViewById<Button>(R.id.btnRotateRight).setOnClickListener { rotateSelected(5f) }

        findViewById<Button>(R.id.btnZoomIn).setOnClickListener {
            if (selectedIndex == -1) return@setOnClickListener
            val el = canvasView.elements[selectedIndex]
            if (el.locked) { toast("قفل است"); return@setOnClickListener }
            pushUndo()
            when (el) {
                is CanvasView.TextElement -> el.resize(1.05f)
                is CanvasView.ImageElement -> el.resize(1.05f)
            }
            canvasView.invalidate()
            updateSizeDisplay()
        }

        findViewById<Button>(R.id.btnZoomOut).setOnClickListener {
            if (selectedIndex == -1) return@setOnClickListener
            val el = canvasView.elements[selectedIndex]
            if (el.locked) { toast("قفل است"); return@setOnClickListener }
            pushUndo()
            when (el) {
                is CanvasView.TextElement -> { if (el.size > 5f) el.resize(0.95f) }
                is CanvasView.ImageElement -> { if (el.width > 20f) el.resize(0.95f) }
            }
            canvasView.invalidate()
            updateSizeDisplay()
        }

        btnLock.setOnClickListener {
            if (selectedIndex == -1) return@setOnClickListener
            val el = canvasView.elements[selectedIndex]
            pushUndo()
            el.locked = !el.locked
            btnLock.text = if (el.locked) "بازکردن" else "قفل"
            canvasView.invalidate()
        }

        findViewById<Button>(R.id.btnSticker).setOnClickListener {
            pickStickerLauncher.launch("image/*")
        }

        findViewById<Button>(R.id.btnUndo).setOnClickListener { performUndo() }

        findViewById<Button>(R.id.btnSave).setOnClickListener { saveImage() }
    }

    private fun pushUndo() {
        val snapshot = canvasView.elements.map { it.clone() }
        undoStack.add(snapshot)
        if (undoStack.size > MAX_UNDO) undoStack.removeAt(0)
    }

    private fun performUndo() {
        if (undoStack.size > 1) {
            undoStack.removeAt(undoStack.size - 1)
            val prev = undoStack.last()
            canvasView.elements.clear()
            canvasView.elements.addAll(prev)
            selectedIndex = -1
            canvasView.invalidate()
            updateUI()
        }
    }

    private fun rotateSelected(delta: Float) {
        if (selectedIndex == -1) return
        val el = canvasView.elements[selectedIndex]
        if (el.locked) { toast("قفل است"); return }
        pushUndo()
        var newRot = el.rotation + delta
        newRot = newRot.coerceIn(-180f, 180f)
        el.rotation = newRot
        canvasView.invalidate()
        updateUI()
    }

    private fun updateSizeDisplay() {
        if (selectedIndex != -1) {
            val el = canvasView.elements[selectedIndex]
            when (el) {
                is CanvasView.TextElement -> tvSize.text = el.size.toInt().toString()
                is CanvasView.ImageElement -> tvSize.text = "عکس"
            }
        } else {
            tvSize.text = "60"
        }
    }

    private fun updateUI() {
        if (selectedIndex != -1) {
            val el = canvasView.elements[selectedIndex]
            tvRotation.text = "${el.rotation.toInt()}°"
            btnLock.text = if (el.locked) "بازکردن" else "قفل"
            when (el) {
                is CanvasView.TextElement -> {
                    tvSize.text = el.size.toInt().toString()
                    btnStyle.text = if (el.underline) "زیرخط ✓" else "زیرخط"
                }
                is CanvasView.ImageElement -> {
                    tvSize.text = "عکس"
                    btnStyle.text = "زیرخط"
                }
            }
        } else {
            tvSize.text = "60"
            tvRotation.text = "0°"
            btnLock.text = "قفل"
            btnStyle.text = "زیرخط"
        }
    }

    private fun showAddTextDialog() {
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = android.view.Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle("متن جدید")
            .setView(editText)
            .setPositiveButton("افزودن") { _, _ ->
                val text = editText.text.toString().trim()
                if (text.isNotEmpty()) {
                    pushUndo()
                    val defaultFont = fontMap[fontNames.lastOrNull()] ?: Typeface.DEFAULT
                    val element = CanvasView.TextElement(
                        text = text, x = 640f, y = 360f, size = 60f,
                        typeface = defaultFont,
                        fontName = fontNames.lastOrNull() ?: "پیش‌فرض"
                    )
                    canvasView.elements.add(element)
                    selectedIndex = canvasView.elements.size - 1
                    canvasView.invalidate()
                    updateUI()
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun showEditTextDialog(textEl: CanvasView.TextElement) {
        val editText = EditText(this).apply {
            setText(textEl.text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = android.view.Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle("ویرایش متن")
            .setView(editText)
            .setPositiveButton("ذخیره") { _, _ ->
                val newText = editText.text.toString().trim()
                if (newText.isNotEmpty()) {
                    pushUndo()
                    textEl.text = newText
                    canvasView.invalidate()
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun showFontPicker() {
        val items = fontNames.toMutableList()
        items.add(0, "بارگذاری فونت جدید...")
        AlertDialog.Builder(this)
            .setTitle("انتخاب فونت")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    openFontLauncher.launch(arrayOf("font/ttf", "application/x-font-ttf", "*/*"))
                } else {
                    val name = items[which]
                    fontMap[name]?.let { tf ->
                        val el = canvasView.elements[selectedIndex]
                        if (el is CanvasView.TextElement) {
                            pushUndo()
                            el.typeface = tf; el.fontName = name
                            canvasView.invalidate()
                            toast("فونت $name اعمال شد")
                        }
                    }
                }
            }
            .show()
    }

    private fun showColorPicker() {
        val colors = arrayOf(
            0xFF000000.toInt() to "سیاه", 0xFFFFFFFF.toInt() to "سفید",
            0xFFFF0000.toInt() to "قرمز", 0xFF0000FF.toInt() to "آبی",
            0xFF008000.toInt() to "سبز", 0xFFFFA500.toInt() to "نارنجی",
            0xFF800080.toInt() to "بنفش", 0xFFFFD700.toInt() to "طلایی",
            0xFF00FFFF.toInt() to "فیروزه‌ای", 0xFFFF69B4.toInt() to "صورتی",
            0xFFA52A2A.toInt() to "قهوه‌ای", 0xFF808080.toInt() to "خاکستری"
        )
        val gridLayout = android.widget.GridLayout(this).apply {
            columnCount = 4; rowCount = 3; useDefaultMargins = true
        }
        for ((color, _) in colors) {
            val v = View(this).apply {
                setBackgroundColor(color)
                layoutParams = ViewGroup.LayoutParams(80, 80)
                setOnClickListener {
                    val el = canvasView.elements[selectedIndex]
                    if (el is CanvasView.TextElement) {
                        pushUndo()
                        el.color = color
                        canvasView.invalidate()
                    }
                    (parent?.parent?.parent as? AlertDialog)?.dismiss()
                }
            }
            gridLayout.addView(v)
        }
        AlertDialog.Builder(this)
            .setTitle("رنگ متن")
            .setView(gridLayout)
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun addSticker(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bitmap == null) return

            val maxWidth = 300f
            val scale = min(maxWidth / bitmap.width, 1f)
            val w = bitmap.width * scale
            val h = bitmap.height * scale

            pushUndo()
            val element = CanvasView.ImageElement(bitmap, 640f, 360f, w, h)
            canvasView.elements.add(element)
            selectedIndex = canvasView.elements.size - 1
            canvasView.invalidate()
            updateUI()
            toast("برچسب اضافه شد")
        } catch (e: Exception) {
            toast("خطا در بارگذاری تصویر")
        }
    }

    private fun loadFont(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val fontDir = File(filesDir, "fonts")
            if (!fontDir.exists()) fontDir.mkdirs()
            val fileName = uri.lastPathSegment ?: "custom_font.ttf"
            val destFile = File(fontDir, fileName)
            destFile.outputStream().use { out -> inputStream.copyTo(out) }
            val typeface = Typeface.createFromFile(destFile)
            val fontName = fileName.removeSuffix(".ttf").removeSuffix(".TTF")
            fontMap[fontName] = typeface
            if (!fontNames.contains(fontName)) {
                fontNames.add(fontName)
                saveFontList()
            }
            toast("فونت '$fontName' اضافه شد")
        } catch (e: Exception) {
            toast("خطا در بارگذاری فونت")
        }
    }

    private fun saveFontList() {
        getSharedPreferences("fonts", MODE_PRIVATE)
            .edit().putStringSet("names", fontNames.toSet()).apply()
    }

    private fun loadStoredFonts() {
        val names = getSharedPreferences("fonts", MODE_PRIVATE)
            .getStringSet("names", emptySet()) ?: emptySet()
        fontNames.addAll(names)
        val fontDir = File(filesDir, "fonts")
        for (name in fontNames) {
            val file = File(fontDir, "$name.ttf")
            if (file.exists()) {
                try { fontMap[name] = Typeface.createFromFile(file) } catch (_: Exception) {}
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun saveImage() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val outW = 3264; val outH = 1836
                val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)

                val scaleX = outW.toFloat() / 1280f
                val scaleY = outH.toFloat() / 720f
                for (el in canvasView.elements) {
                    el.draw(canvas, scaleX, scaleY, 0f, 0f)
                }

                val values = android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "TextOnPhoto_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                    }
                }
                withContext(Dispatchers.Main) {
                    toast("تصویر ۳۲۶۴×۱۸۳۶ در گالری ذخیره شد")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("خطا: ${e.message}") }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
