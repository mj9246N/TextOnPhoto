package com.example.textonphoto

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var canvasView: CanvasView
    private var selectedElementIndex = -1
    private val fontMap = mutableMapOf<String, Typeface>()
    private val fontNames = mutableListOf<String>()
    private lateinit var tvSize: TextView
    private lateinit var btnLock: Button

    private val openFontLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { loadFont(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        canvasView = findViewById(R.id.canvasView)
        tvSize = findViewById(R.id.tvSize)
        btnLock = findViewById<Button>(R.id.btnLock)

        loadStoredFonts()

        canvasView.onCanvasTap = { x, y ->
            if (selectedElementIndex != -1) {
                val element = canvasView.elements.getOrNull(selectedElementIndex)
                if (element is CanvasView.TextElement && !element.locked) {
                    showTextDialog(element)
                } else if (element is CanvasView.TextElement && element.locked) {
                    Toast.makeText(this, "متن قفل است، ابتدا قفل را باز کنید", Toast.LENGTH_SHORT).show()
                }
            } else {
                showTextDialog(null, x, y)
            }
        }

        canvasView.onElementSelected = { index ->
            selectedElementIndex = index
            updateUIForSelection()
        }

        findViewById<Button>(R.id.btnAddText).setOnClickListener {
            selectedElementIndex = -1
            updateUIForSelection()
            Toast.makeText(this, "روی بوم کلیک کنید", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnFont).setOnClickListener {
            val idx = selectedElementIndex
            if (idx != -1) {
                val element = canvasView.elements[idx]
                if (element is CanvasView.TextElement) {
                    if (element.locked) {
                        Toast.makeText(this, "متن قفل است", Toast.LENGTH_SHORT).show()
                    } else {
                        showFontPickerDialog(element)
                    }
                } else {
                    openFontLauncher.launch(arrayOf("font/ttf", "application/x-font-ttf", "*/*"))
                }
            } else {
                openFontLauncher.launch(arrayOf("font/ttf", "application/x-font-ttf", "*/*"))
            }
        }

        findViewById<Button>(R.id.btnColor).setOnClickListener {
            val idx = selectedElementIndex
            if (idx != -1) {
                val element = canvasView.elements[idx]
                if (element is CanvasView.TextElement) {
                    if (element.locked) {
                        Toast.makeText(this, "متن قفل است", Toast.LENGTH_SHORT).show()
                    } else {
                        showColorPickerDialog(element)
                    }
                }
            }
        }

        btnLock.setOnClickListener {
            val idx = selectedElementIndex
            if (idx != -1) {
                val element = canvasView.elements[idx] as? CanvasView.TextElement ?: return@setOnClickListener
                element.locked = !element.locked
                updateLockButtonText(element.locked)
                canvasView.invalidate()
                Toast.makeText(this, if (element.locked) "متن قفل شد" else "قفل باز شد", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnDelete).setOnClickListener {
            val idx = selectedElementIndex
            if (idx != -1) {
                val element = canvasView.elements[idx]
                if (element is CanvasView.TextElement && element.locked) {
                    Toast.makeText(this, "نمی‌توانید متن قفل‌شده را حذف کنید", Toast.LENGTH_SHORT).show()
                } else {
                    canvasView.elements.removeAt(idx)
                    selectedElementIndex = -1
                    canvasView.invalidate()
                    updateUIForSelection()
                }
            }
        }

        findViewById<Button>(R.id.btnZoomIn).setOnClickListener {
            val idx = selectedElementIndex
            if (idx != -1) {
                val element = canvasView.elements[idx]
                if (element is CanvasView.TextElement && element.locked) {
                    Toast.makeText(this, "متن قفل است", Toast.LENGTH_SHORT).show()
                } else {
                    element.size += 5f
                    canvasView.invalidate()
                    updateSizeDisplay()
                }
            }
        }

        findViewById<Button>(R.id.btnZoomOut).setOnClickListener {
            val idx = selectedElementIndex
            if (idx != -1) {
                val element = canvasView.elements[idx]
                if (element is CanvasView.TextElement && element.locked) {
                    Toast.makeText(this, "متن قفل است", Toast.LENGTH_SHORT).show()
                } else if (element.size > 5f) {
                    element.size -= 5f
                    canvasView.invalidate()
                    updateSizeDisplay()
                }
            }
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveBitmapToGallery()
        }
    }

    private fun updateUIForSelection() {
        if (selectedElementIndex != -1) {
            val element = canvasView.elements[selectedElementIndex]
            if (element is CanvasView.TextElement) {
                tvSize.text = element.size.toInt().toString()
                updateLockButtonText(element.locked)
            } else {
                tvSize.text = "60"
                btnLock.text = "قفل"
            }
        } else {
            tvSize.text = "60"
            btnLock.text = "قفل"
        }
    }

    private fun updateLockButtonText(locked: Boolean) {
        btnLock.text = if (locked) "بازکردن" else "قفل"
    }

    private fun updateSizeDisplay() {
        if (selectedElementIndex != -1) {
            val size = canvasView.elements[selectedElementIndex].size.toInt()
            tvSize.text = size.toString()
        } else {
            tvSize.text = "60"
        }
    }

    private fun showTextDialog(
        existing: CanvasView.TextElement? = null,
        defaultX: Float = 640f,
        defaultY: Float = 360f
    ) {
        val editText = EditText(this)
        editText.setText(existing?.text ?: "")
        AlertDialog.Builder(this)
            .setTitle("متن")
            .setView(editText)
            .setPositiveButton("تأیید") { _, _ ->
                val text = editText.text.toString()
                if (text.isNotEmpty()) {
                    if (existing != null) {
                        existing.text = text
                    } else {
                        val font = fontMap[fontNames.lastOrNull()] ?: Typeface.DEFAULT
                        canvasView.elements.add(
                            CanvasView.TextElement(
                                text,
                                defaultX.coerceIn(0f, canvasView.designWidth),
                                defaultY.coerceIn(0f, canvasView.designHeight),
                                60f,
                                font,
                                fontNames.lastOrNull() ?: "پیش‌فرض"
                            )
                        )
                    }
                    canvasView.invalidate()
                }
            }
            .show()
    }

    private fun showFontPickerDialog(textElement: CanvasView.TextElement) {
        val items = fontNames.toMutableList()
        items.add(0, "بارگذاری فونت جدید...")
        AlertDialog.Builder(this)
            .setTitle("انتخاب فونت")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    openFontLauncher.launch(arrayOf("font/ttf", "application/x-font-ttf", "*/*"))
                    Toast.makeText(this, "فونت جدید را انتخاب کنید، سپس دوباره از دکمه فونت استفاده کنید", Toast.LENGTH_LONG).show()
                } else {
                    val fontName = items[which]
                    fontMap[fontName]?.let { tf ->
                        textElement.typeface = tf
                        textElement.fontName = fontName
                        canvasView.invalidate()
                        Toast.makeText(this, "فونت $fontName اعمال شد", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun showColorPickerDialog(textElement: CanvasView.TextElement) {
        val colors = arrayOf(
            "سیاه" to Color.BLACK,
            "سفید" to Color.WHITE,
            "قرمز" to Color.RED,
            "آبی" to Color.BLUE,
            "سبز" to Color.GREEN,
            "زرد" to Color.YELLOW,
            "نارنجی" to 0xFFFFA500.toInt(),
            "بنفش" to 0xFF800080.toInt()
        )
        val colorNames = colors.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("رنگ متن")
            .setItems(colorNames) { _, which ->
                textElement.color = colors[which].second
                canvasView.invalidate()
            }
            .show()
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
                saveFontNameList()
            }
            Toast.makeText(this, "فونت '$fontName' اضافه شد", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در بارگذاری فونت", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveFontNameList() {
        getSharedPreferences("fonts", MODE_PRIVATE).edit()
            .putStringSet("names", fontNames.toSet())
            .apply()
    }

    private fun loadStoredFonts() {
        val names = getSharedPreferences("fonts", MODE_PRIVATE).getStringSet("names", emptySet()) ?: emptySet()
        fontNames.addAll(names)
        val fontDir = File(filesDir, "fonts")
        for (name in fontNames) {
            val file = File(fontDir, "$name.ttf")
            if (file.exists()) {
                try {
                    fontMap[name] = Typeface.createFromFile(file)
                } catch (_: Exception) {}
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun saveBitmapToGallery() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                // اندازهٔ نهایی خروجی: ۳۲۶۴×۱۸۳۶ پیکسل
                val bitmap = Bitmap.createBitmap(3264, 1836, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)

                // مقیاس طراحی: 1280x720 -> 3264x1836
                val outputScale = 3264f / 1280f  // = 2.55
                canvasView.elements.forEach { it.draw(canvas, outputScale, 0f, 0f) }

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
                    Toast.makeText(this@MainActivity, "تصویر با کیفیت ۳۲۶۴×۱۸۳۶ در گالری ذخیره شد", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "خطا: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
