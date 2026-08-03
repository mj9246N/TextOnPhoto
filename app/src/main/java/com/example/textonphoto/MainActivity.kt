package com.example.textonphoto

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.*

class MainActivity : AppCompatActivity() {

    private lateinit var canvasView: CanvasView
    private var selectedElementIndex = -1
    private val fontMap = mutableMapOf<String, Typeface>() // name -> typeface
    private val fontNames = mutableListOf<String>()

    private val openFontLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { loadFont(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        canvasView = findViewById(R.id.canvasView)
        loadStoredFonts()

        // کلیک روی بوم برای افزودن متن (در صورت عدم انتخاب عنصر)
        canvasView.setOnClickListener { x, y ->
            if (selectedElementIndex != -1) {
                // ویرایش متن
                showTextDialog(canvasView.elements[selectedElementIndex] as? CanvasView.TextElement)
            } else {
                showTextDialog(null, x, y)
            }
        }

        canvasView.onElementSelected = { index ->
            selectedElementIndex = index
        }

        findViewById<android.widget.Button>(R.id.btnAddText).setOnClickListener {
            selectedElementIndex = -1
            canvasView.invalidate()
            Toast.makeText(this, "روی بوم کلیک کنید تا متن اضافه شود", Toast.LENGTH_SHORT).show()
        }

        findViewById<android.widget.Button>(R.id.btnFont).setOnClickListener {
            openFontLauncher.launch(arrayOf("font/ttf", "application/x-font-ttf", "*/*"))
        }

        findViewById<android.widget.Button>(R.id.btnShape).setOnClickListener {
            showShapeDialog()
        }

        findViewById<android.widget.Button>(R.id.btnDelete).setOnClickListener {
            if (selectedElementIndex != -1) {
                canvasView.elements.removeAt(selectedElementIndex)
                selectedElementIndex = -1
                canvasView.invalidate()
            }
        }

        findViewById<android.widget.Button>(R.id.btnZoomIn).setOnClickListener {
            if (selectedElementIndex != -1) {
                canvasView.elements[selectedElementIndex].size += 5f
                canvasView.invalidate()
            }
        }

        findViewById<android.widget.Button>(R.id.btnZoomOut).setOnClickListener {
            if (selectedElementIndex != -1 && canvasView.elements[selectedElementIndex].size > 5f) {
                canvasView.elements[selectedElementIndex].size -= 5f
                canvasView.invalidate()
            }
        }

        findViewById<android.widget.Button>(R.id.btnSave).setOnClickListener {
            saveBitmapToGallery()
        }
    }

    private fun showTextDialog(
        existing: CanvasView.TextElement? = null,
        defaultX: Float = canvasView.width / 2f,
        defaultY: Float = canvasView.height / 2f
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
                        val font = if (fontNames.isNotEmpty()) fontMap[fontNames.last()] else Typeface.DEFAULT
                        canvasView.elements.add(
                            CanvasView.TextElement(text, defaultX, defaultY, 60f, font, fontNames.lastOrNull() ?: "پیش‌فرض")
                        )
                    }
                    canvasView.invalidate()
                }
            }
            .show()
    }

    private fun showShapeDialog() {
        val shapes = arrayOf("مربع", "مستطیل", "خط", "دایره")
        AlertDialog.Builder(this)
            .setTitle("انتخاب شکل")
            .setItems(shapes) { _, which ->
                val type = when (which) {
                    0 -> CanvasView.ShapeElement.ShapeType.SQUARE
                    1 -> CanvasView.ShapeElement.ShapeType.RECTANGLE
                    2 -> CanvasView.ShapeElement.ShapeType.LINE
                    3 -> CanvasView.ShapeElement.ShapeType.CIRCLE
                    else -> CanvasView.ShapeElement.ShapeType.SQUARE
                }
                canvasView.elements.add(
                    CanvasView.ShapeElement(type, canvasView.width / 2f, canvasView.height / 2f, 100f)
                )
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
            destFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            val typeface = Typeface.createFromFile(destFile)
            val fontName = fileName.removeSuffix(".ttf").removeSuffix(".TTF")
            fontMap[fontName] = typeface
            if (!fontNames.contains(fontName)) fontNames.add(fontName)
            saveFontNameList()
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
                fontMap[name] = Typeface.createFromFile(file)
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun saveBitmapToGallery() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val bitmap = Bitmap.createBitmap(3264, 1836, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvasView.elements.forEach { it.draw(canvas, 1f, 0f, 0f) }

                val contentValues = android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "TextOnPhoto_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "تصویر ذخیره شد", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "خطا در ذخیره", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
