package com.example.textonphoto

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
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
    private val fontMap = mutableMapOf<String, Typeface>()  // نام فونت -> Typeface
    private val fontNames = mutableListOf<String>()          // لیست نام فونت‌ها
    private lateinit var tvSize: TextView

    // برای انتخاب فایل فونت
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
        loadStoredFonts()

        // کلیک روی بوم (اضافه یا ویرایش متن)
        canvasView.onCanvasTap = { x, y ->
            if (selectedElementIndex != -1) {
                val element = canvasView.elements.getOrNull(selectedElementIndex)
                if (element is CanvasView.TextElement) {
                    showTextDialog(element)
                }
            } else {
                showTextDialog(null, x, y)
            }
        }

        canvasView.onElementSelected = { index ->
            selectedElementIndex = index
            updateSizeDisplay()
        }

        // دکمه افزودن متن جدید
        findViewById<Button>(R.id.btnAddText).setOnClickListener {
            selectedElementIndex = -1
            canvasView.invalidate()
            Toast.makeText(this, "روی بوم کلیک کنید تا متن اضافه شود", Toast.LENGTH_SHORT).show()
        }

        // دکمه فونت (انتخاب فونت برای متن انتخاب‌شده یا بارگذاری فونت جدید)
        findViewById<Button>(R.id.btnFont).setOnClickListener {
            if (selectedElementIndex != -1) {
                val element = canvasView.elements[selectedElementIndex]
                if (element is CanvasView.TextElement) {
                    showFontPickerDialog(element)
                } else {
                    openFontLauncher.launch(arrayOf("font/ttf", "application/x-font-ttf", "*/*"))
                }
            } else {
                openFontLauncher.launch(arrayOf("font/ttf", "application/x-font-ttf", "*/*"))
            }
        }

        // حذف عنصر انتخاب‌شده
        findViewById<Button>(R.id.btnDelete).setOnClickListener {
            if (selectedElementIndex != -1) {
                canvasView.elements.removeAt(selectedElementIndex)
                selectedElementIndex = -1
                canvasView.invalidate()
                updateSizeDisplay()
            }
        }

        // بزرگ‌کردن اندازه
        findViewById<Button>(R.id.btnZoomIn).setOnClickListener {
            if (selectedElementIndex != -1) {
                canvasView.elements[selectedElementIndex].size += 5f
                canvasView.invalidate()
                updateSizeDisplay()
            }
        }

        // کوچک‌کردن اندازه
        findViewById<Button>(R.id.btnZoomOut).setOnClickListener {
            if (selectedElementIndex != -1 && canvasView.elements[selectedElementIndex].size > 5f) {
                canvasView.elements[selectedElementIndex].size -= 5f
                canvasView.invalidate()
                updateSizeDisplay()
            }
        }

        // ذخیره‌ی عکس نهایی
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveBitmapToGallery()
        }
    }

    private fun updateSizeDisplay() {
        if (selectedElementIndex != -1) {
            val size = canvasView.elements[selectedElementIndex].size.toInt()
            tvSize.text = size.toString()
        } else {
            tvSize.text = "60" // اندازه پیش‌فرض
        }
    }

    // دیالوگ وارد کردن متن
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

    // دیالوگ انتخاب فونت برای متن مشخص
    private fun showFontPickerDialog(textElement: CanvasView.TextElement) {
        val items = fontNames.toMutableList()
        items.add(0, "بارگذاری فونت جدید...")
        AlertDialog.Builder(this)
            .setTitle("انتخاب فونت")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    // بارگذاری فونت جدید و سپس اعمال آن
                    openFontLauncher.launch(arrayOf("font/ttf", "application/x-font-ttf", "*/*"))
                    // بعد از بارگذاری باید فونت به این متن اعمال شود – برای سادگی، کاربر دوباره باید فونت را انتخاب کند.
                    // می‌توانیم یک callback تنظیم کنیم، ولی فعلاً از کاربر می‌خواهیم دوباره دکمه فونت را بزند.
                    Toast.makeText(this, "پس از بارگذاری فونت، دوباره دکمه فونت را بزنید و فونت را انتخاب کنید", Toast.LENGTH_LONG).show()
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

    // بارگذاری یک فونت ttf از حافظه
    private fun loadFont(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val fontDir = File(filesDir, "fonts")
            if (!fontDir.exists()) fontDir.mkdirs()
            val fileName = uri.lastPathSegment ?: "custom_font.ttf"
            val destFile = File(fontDir, fileName)
            destFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
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
        val names = getSharedPreferences("fonts", MODE_PRIVATE)
            .getStringSet("names", emptySet()) ?: emptySet()
        fontNames.addAll(names)
        val fontDir = File(filesDir, "fonts")
        for (name in fontNames) {
            val file = File(fontDir, "$name.ttf")
            if (file.exists()) {
                try {
                    fontMap[name] = Typeface.createFromFile(file)
                } catch (e: Exception) {
                    // فایل خراب است، نادیده گرفته می‌شود
                }
            }
        }
    }

    // ذخیره‌سازی بوم در گالری با کیفیت 1280x720
    @OptIn(DelicateCoroutinesApi::class)
    private fun saveBitmapToGallery() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                // رسم همه عناصر با مقیاس ۱ (اندازه‌ی واقعی طراحی)
                canvasView.elements.forEach { it.draw(canvas, 1f, 0f, 0f) }

                val contentValues = android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "TextOnPhoto_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }
                val uri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "تصویر در گالری ذخیره شد", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "خطا در ذخیره‌سازی", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
