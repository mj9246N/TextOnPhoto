package com.example.textonphoto

import android.app.AlertDialog
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var canvasView: CanvasView
    private var selectedIndex = -1
    private val fontMap = mutableMapOf<String, Typeface>()
    private val fontNames = mutableListOf<String>()
    private lateinit var tvSize: TextView
    private lateinit var btnLock: Button

    private val openFontLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { loadFont(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        canvasView = findViewById(R.id.canvasView)
        tvSize = findViewById(R.id.tvSize)
        btnLock = findViewById(R.id.btnLock)

        loadStoredFonts()

        canvasView.onElementSelected = { idx ->
            selectedIndex = idx
            updateUI()
        }

        // متن جدید
        findViewById<Button>(R.id.btnAddText).setOnClickListener {
            showAddTextDialog()
        }

        // حذف
        findViewById<Button>(R.id.btnDelete).setOnClickListener {
            if (selectedIndex == -1) {
                toast("ابتدا یک متن را انتخاب کنید")
                return@setOnClickListener
            }
            val el = canvasView.elements[selectedIndex]
            if (el.locked) {
                toast("متن قفل است")
                return@setOnClickListener
            }
            canvasView.elements.removeAt(selectedIndex)
            selectedIndex = -1
            canvasView.invalidate()
            updateUI()
        }

        // فونت
        findViewById<Button>(R.id.btnFont).setOnClickListener {
            if (selectedIndex == -1) {
                toast("متنی انتخاب نشده")
                return@setOnClickListener
            }
            showFontPicker()
        }

        // رنگ
        findViewById<Button>(R.id.btnColor).setOnClickListener {
            if (selectedIndex == -1) {
                toast("متنی انتخاب نشده")
                return@setOnClickListener
            }
            if (canvasView.elements[selectedIndex].locked) {
                toast("متن قفل است")
                return@setOnClickListener
            }
            showColorPicker()
        }

        // بزرگ‌کردن
        findViewById<Button>(R.id.btnZoomIn).setOnClickListener {
            if (selectedIndex == -1) return@setOnClickListener
            val el = canvasView.elements[selectedIndex]
            if (el.locked) { toast("متن قفل است"); return@setOnClickListener }
            el.size += 5f
            canvasView.invalidate()
            tvSize.text = el.size.toInt().toString()
        }

        // کوچک‌کردن
        findViewById<Button>(R.id.btnZoomOut).setOnClickListener {
            if (selectedIndex == -1) return@setOnClickListener
            val el = canvasView.elements[selectedIndex]
            if (el.locked) { toast("متن قفل است"); return@setOnClickListener }
            if (el.size > 5f) {
                el.size -= 5f
                canvasView.invalidate()
                tvSize.text = el.size.toInt().toString()
            }
        }

        // قفل
        btnLock.setOnClickListener {
            if (selectedIndex == -1) return@setOnClickListener
            val el = canvasView.elements[selectedIndex]
            el.locked = !el.locked
            btnLock.text = if (el.locked) "بازکردن" else "قفل"
            canvasView.invalidate()
        }

        // ذخیره
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveImage()
        }
    }

    private fun updateUI() {
        if (selectedIndex != -1) {
            val el = canvasView.elements[selectedIndex]
            tvSize.text = el.size.toInt().toString()
            btnLock.text = if (el.locked) "بازکردن" else "قفل"
        } else {
            tvSize.text = "60"
            btnLock.text = "قفل"
        }
    }

    private fun showAddTextDialog() {
        val editText = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("متن جدید")
            .setView(editText)
            .setPositiveButton("افزودن") { _, _ ->
                val text = editText.text.toString()
                if (text.isNotBlank()) {
                    val defaultFont = fontMap[fontNames.lastOrNull()] ?: Typeface.DEFAULT
                    val element = CanvasView.TextElement(
                        text = text,
                        x = 640f,
                        y = 360f,
                        size = 60f,
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
                        el.typeface = tf
                        el.fontName = name
                        canvasView.invalidate()
                        toast("فونت $name اعمال شد")
                    }
                }
            }
            .show()
    }

    private fun showColorPicker() {
        val colors = arrayOf(
            0xFF000000.toInt() to "سیاه",
            0xFFFFFFFF.toInt() to "سفید",
            0xFFFF0000.toInt() to "قرمز",
            0xFF0000FF.toInt() to "آبی",
            0xFF008000.toInt() to "سبز",
            0xFFFFA500.toInt() to "نارنجی",
            0xFF800080.toInt() to "بنفش",
            0xFFFFD700.toInt() to "طلایی",
            0xFF00FFFF.toInt() to "فیروزه‌ای",
            0xFFFF69B4.toInt() to "صورتی",
            0xFFA52A2A.toInt() to "قهوه‌ای",
            0xFF808080.toInt() to "خاکستری"
        )
        val gridLayout = android.widget.GridLayout(this).apply {
            columnCount = 4
            rowCount = 3
            useDefaultMargins = true
        }
        val swatches = colors.map { (color, name) ->
            View(this).apply {
                setBackgroundColor(color)
                layoutParams = ViewGroup.LayoutParams(80, 80)
                setOnClickListener {
                    canvasView.elements[selectedIndex].color = color
                    canvasView.invalidate()
                    // بستن دیالوگ
                    (parent?.parent?.parent as? AlertDialog)?.dismiss()
                }
            }
        }
        swatches.forEach { gridLayout.addView(it) }

        AlertDialog.Builder(this)
            .setTitle("رنگ متن")
            .setView(gridLayout)
            .setNegativeButton("انصراف", null)
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
                try {
                    fontMap[name] = Typeface.createFromFile(file)
                } catch (_: Exception) {}
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun saveImage() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val outW = 3264
                val outH = 1836
                val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)

                val scaleX = outW.toFloat() / 1280f
                val scaleY = outH.toFloat() / 720f
                for (e in canvasView.elements) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        typeface = e.typeface
                        textSize = e.size * scaleX
                        color = e.color
                    }
                    canvas.drawText(e.text, e.x * scaleX, e.y * scaleY, paint)
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
                withContext(Dispatchers.Main) {
                    toast("خطا: ${e.message}")
                }
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
