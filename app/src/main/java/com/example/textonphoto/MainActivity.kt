package com.example.textonphoto

import android.app.AlertDialog
import android.content.*
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var canvasView: CanvasView
    private var selectedIndex = -1
    private lateinit var tvSize: TextView
    private lateinit var tvRotation: TextView
    private lateinit var btnLock: Button
    private lateinit var btnStyle: Button

    private val undoStack = mutableListOf<List<CanvasView.CanvasElement>>()
    private val MAX_UNDO = 100

    // تاریخچه پایدار (ذخیره در فایل)
    private val historyFile by lazy { File(filesDir, "history.txt") }
    private val historyTexts = mutableListOf<String>()

    private var defaultTypeface: Typeface = Typeface.DEFAULT

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

        // بارگذاری فونت پیش‌فرض
        try {
            defaultTypeface = Typeface.createFromAsset(assets, "fonts/default.ttf")
            Toast.makeText(this, "فونت پیش‌فرض با موفقیت بارگذاری شد", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            defaultTypeface = Typeface.DEFAULT
            Toast.makeText(this, "فونت پیش‌فرض یافت نشد! مسیر: assets/fonts/default.ttf", Toast.LENGTH_LONG).show()
        }

        // بارگذاری تاریخچه از فایل
        loadHistory()

        pushUndo()

        canvasView.onElementSelected = { idx ->
            selectedIndex = idx
            canvasView.selectedElementIndex = idx
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
            canvasView.selectedElementIndex = selectedIndex
            canvasView.invalidate()
            updateUI()
        }
        findViewById<Button>(R.id.btnDelete).setOnClickListener {
            if (selectedIndex == -1) return@setOnClickListener
            if (canvasView.elements[selectedIndex].locked) { toast("قفل است"); return@setOnClickListener }
            pushUndo()
            canvasView.elements.removeAt(selectedIndex)
            selectedIndex = -1
            canvasView.selectedElementIndex = -1
            canvasView.invalidate()
            updateUI()
        }
        findViewById<Button>(R.id.btnColor).setOnClickListener {
            if (selectedIndex == -1) return@setOnClickListener
            val el = canvasView.elements[selectedIndex]
            when {
                el is CanvasView.TextElement -> {
                    if (el.locked) { toast("قفل است"); return@setOnClickListener }
                    showColorPicker(forImage = false)
                }
                el is CanvasView.ImageElement -> {
                    if (el.locked) { toast("قفل است"); return@setOnClickListener }
                    showColorPicker(forImage = true)
                }
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
            el.resize(1.05f)
            canvasView.invalidate()
            updateSizeDisplay()
        }
        findViewById<Button>(R.id.btnZoomOut).setOnClickListener {
            if (selectedIndex == -1) return@setOnClickListener
            val el = canvasView.elements[selectedIndex]
            if (el.locked) { toast("قفل است"); return@setOnClickListener }
            if ((el is CanvasView.TextElement && el.size > 5f) || (el is CanvasView.ImageElement && el.width > 20f)) {
                pushUndo()
                el.resize(0.95f)
                canvasView.invalidate()
                updateSizeDisplay()
            }
        }
        btnLock.setOnClickListener {
            if (selectedIndex == -1) return@setOnClickListener
            val el = canvasView.elements[selectedIndex]
            pushUndo()
            el.locked = !el.locked
            btnLock.text = if (el.locked) "بازکردن" else "قفل"
            canvasView.invalidate()
        }
        findViewById<Button>(R.id.btnSticker).setOnClickListener { pickStickerLauncher.launch("image/*") }
        findViewById<Button>(R.id.btnUndo).setOnClickListener { performUndo() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveImage() }
        findViewById<Button>(R.id.btnHistory).setOnClickListener { showHistoryDialog() }
    }

    // ===================== تاریخچه (پایدار با فایل) =====================
    private fun loadHistory() {
        if (historyFile.exists()) {
            historyTexts.clear()
            historyTexts.addAll(historyFile.readLines().filter { it.isNotBlank() })
        }
    }

    private fun saveHistory() {
        historyFile.writeText(historyTexts.joinToString("\n"))
    }

    private fun addToHistory(text: String) {
        if (!historyTexts.contains(text)) {
            historyTexts.add(0, text)
            if (historyTexts.size > 200) historyTexts.removeAt(historyTexts.size - 1)
            saveHistory()
        }
    }

    private fun showHistoryDialog() {
        val builder = AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
        builder.setTitle("تاریخچه متون")

        if (historyTexts.isEmpty()) {
            builder.setMessage("تاریخچه خالی است")
            builder.setPositiveButton("بستن", null)
            builder.show()
            return
        }

        // آداپتور لیست
        val listAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, historyTexts.toList()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_1, parent, false)
                val textView = view.findViewById<TextView>(android.R.id.text1)
                textView.text = getItem(position)
                textView.setTextColor(Color.BLACK)
                textView.textSize = 16f
                return view
            }
        }

        builder.setAdapter(listAdapter) { dialog, which ->
            val selectedText = historyTexts[which]
            AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle(selectedText)
                .setItems(arrayOf("کپی", "حذف")) { _, opt ->
                    when (opt) {
                        0 -> {
                            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("text", selectedText))
                            toast("کپی شد")
                        }
                        1 -> {
                            historyTexts.removeAt(which)
                            saveHistory()
                            listAdapter.notifyDataSetChanged()
                            if (historyTexts.isEmpty()) {
                                dialog.dismiss()
                                showHistoryDialog()
                            }
                            toast("حذف شد")
                        }
                    }
                }
                .setPositiveButton("بستن", null)
                .show()
        }

        builder.setPositiveButton("بستن") { dialog, _ -> dialog.dismiss() }
        builder.setNeutralButton("حذف همه🗑️") { _, _ ->
            historyTexts.clear()
            saveHistory()
            showHistoryDialog()
            toast("همه حذف شدند")
        }

        builder.show()
    }

    // ===================== افزودن/ویرایش متن =====================
    private fun showAddTextDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            hint = "متن خود را بنویسید"
        }
        layout.addView(editText)

        val numContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val hscroll = HorizontalScrollView(this)
        for (i in 1..50) {
            val btn = TextView(this).apply {
                text = getCircledNumber(i)
                background = getDrawable(R.drawable.circle_number_bg)
                gravity = Gravity.CENTER
                val size = (44 * resources.displayMetrics.density).toInt()
                width = size; height = size; textSize = 16f
                setOnClickListener {
                    val s = maxOf(editText.selectionStart, 0)
                    val e = maxOf(editText.selectionEnd, 0)
                    editText.text.replace(s, e, text)
                    editText.setSelection(s + text.length)
                }
            }
            numContainer.addView(btn)
        }
        hscroll.addView(numContainer)
        layout.addView(hscroll)

        AlertDialog.Builder(this)
            .setTitle("متن جدید")
            .setView(layout)
            .setPositiveButton("افزودن") { _, _ ->
                val text = editText.text.toString().trim()
                if (text.isNotEmpty()) {
                    pushUndo()
                    val el = CanvasView.TextElement(text, 640f, 360f, 60f, defaultTypeface, "پیش‌فرض")
                    canvasView.elements.add(el)
                    selectedIndex = canvasView.elements.size - 1
                    canvasView.selectedElementIndex = selectedIndex
                    canvasView.invalidate(); updateUI()
                    addToHistory(text)   // ذخیره در تاریخچه پایدار
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
        }
        AlertDialog.Builder(this)
            .setTitle("ویرایش متن")
            .setView(editText)
            .setPositiveButton("ذخیره") { _, _ ->
                val newText = editText.text.toString().trim()
                if (newText.isNotEmpty()) {
                    pushUndo(); textEl.text = newText; canvasView.invalidate()
                    addToHistory(newText)   // ذخیره در تاریخچه
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    // ===================== رنگ / برچسب / ذخیره =====================
    private fun showColorPicker(forImage: Boolean) {
        val colors = arrayOf<Pair<Int?, String>>(
            0xFF000000.toInt() to "سیاه", 0xFFFFFFFF.toInt() to "سفید",
            0xFFFF0000.toInt() to "قرمز", 0xFF0000FF.toInt() to "آبی",
            0xFF008000.toInt() to "سبز", 0xFFFFA500.toInt() to "نارنجی",
            0xFF800080.toInt() to "بنفش", 0xFFFFD700.toInt() to "طلایی",
            0xFF00FFFF.toInt() to "فیروزه‌ای", 0xFFFF69B4.toInt() to "صورتی",
            0xFFA52A2A.toInt() to "قهوه‌ای", 0xFF808080.toInt() to "خاکستری"
        )
        val grid = android.widget.GridLayout(this).apply { columnCount = 4; rowCount = 3; useDefaultMargins = true }
        val el = canvasView.elements[selectedIndex]
        val list = colors.toMutableList(); if (forImage) list.add(null to "بدون رنگ")
        for ((color, _) in list) {
            val v = View(this).apply {
                setBackgroundColor(color ?: Color.TRANSPARENT)
                layoutParams = ViewGroup.LayoutParams(80, 80)
                setOnClickListener {
                    when {
                        color == null && el is CanvasView.ImageElement -> { pushUndo(); (el as CanvasView.ImageElement).tintColor = null; canvasView.invalidate() }
                        el is CanvasView.TextElement && color != null -> { pushUndo(); (el as CanvasView.TextElement).color = color; canvasView.invalidate() }
                        el is CanvasView.ImageElement && color != null -> { pushUndo(); (el as CanvasView.ImageElement).tintColor = color; canvasView.invalidate() }
                    }
                    (parent?.parent?.parent as? AlertDialog)?.dismiss()
                }
            }
            grid.addView(v)
        }
        AlertDialog.Builder(this)
            .setTitle(if (forImage) "تینت عکس" else "رنگ متن")
            .setView(grid)
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun addSticker(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val bmp = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bmp == null) return
            val s = min(300f / bmp.width, 1f)
            pushUndo()
            val el = CanvasView.ImageElement(bmp, 640f, 360f, bmp.width * s, bmp.height * s)
            canvasView.elements.add(el)
            selectedIndex = canvasView.elements.size - 1
            canvasView.selectedElementIndex = selectedIndex
            canvasView.invalidate(); updateUI()
            toast("برچسب اضافه شد")
        } catch (e: Exception) { toast("خطا در بارگذاری تصویر") }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun saveImage() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val bw = 1280; val bh = 720
                val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp); c.drawColor(Color.WHITE)
                val sx = bw / 1280f; val sy = bh / 720f
                canvasView.elements.forEach { it.draw(c, sx, sy, 0f, 0f) }
                val v = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "TextOnPhoto_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
                uri?.let { contentResolver.openOutputStream(it)?.use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 100, out) } }
                withContext(Dispatchers.Main) { toast("تصویر در گالری ذخیره شد") }
            } catch (e: Exception) { withContext(Dispatchers.Main) { toast("خطا: ${e.message}") } }
        }
    }

    // ===================== ابزارهای عمومی =====================
    private fun pushUndo() { undoStack.add(canvasView.elements.map { it.clone() }); if (undoStack.size > MAX_UNDO) undoStack.removeAt(0) }
    private fun performUndo() {
        if (undoStack.size > 1) { undoStack.removeLast(); val prev = undoStack.last(); canvasView.elements.clear(); canvasView.elements.addAll(prev); selectedIndex = -1; canvasView.selectedElementIndex = -1; canvasView.invalidate(); updateUI() }
    }
    private fun rotateSelected(d: Float) {
        if (selectedIndex == -1) return
        val e = canvasView.elements[selectedIndex]
        if (e.locked) { toast("قفل"); return }
        pushUndo(); e.rotation = (e.rotation + d).coerceIn(-180f, 180f); canvasView.invalidate(); updateUI()
    }
    private fun updateSizeDisplay() {
        tvSize.text = if (selectedIndex != -1) (canvasView.elements[selectedIndex] as? CanvasView.TextElement)?.size?.toInt()?.toString() ?: "عکس" else "60"
    }
    private fun updateUI() {
        if (selectedIndex != -1) {
            val e = canvasView.elements[selectedIndex]
            tvRotation.text = "${e.rotation.toInt()}°"
            btnLock.text = if (e.locked) "بازکردن" else "قفل"
            if (e is CanvasView.TextElement) {
                tvSize.text = e.size.toInt().toString()
                btnStyle.text = if (e.underline) "زیرخط ✓" else "زیرخط"
            } else {
                tvSize.text = "عکس"; btnStyle.text = "زیرخط"
            }
        } else {
            tvSize.text = "60"; tvRotation.text = "0°"; btnLock.text = "قفل"; btnStyle.text = "زیرخط"
        }
    }

    private fun getCircledNumber(n: Int) = when {
        n in 1..20 -> String(Character.toChars(0x2460 + n - 1))
        n in 21..35 -> String(Character.toChars(0x3251 + n - 21))
        n in 36..50 -> String(Character.toChars(0x32B1 + n - 36))
        else -> "($n)"
    }
    private fun Int.dpToPx() = (this * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
