package com.example.textonphoto

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

        findViewById<Button>(R.id.btnFont).setOnClickListener {
            if (selectedIndex == -1 || canvasView.elements[selectedIndex] !is CanvasView.TextElement) {
                toast("فقط برای متن")
                return@setOnClickListener
            }
            showFontPicker()
        }

        // دکمه رنگ: هم برای متن و هم برای عکس (تینت)
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

        findViewById<Button>(R.id.btnSticker).setOnClickListener {
            pickStickerLauncher.launch("image/*")
        }

        findViewById<Button>(R.id.btnUndo).setOnClickListener { performUndo() }

        findViewById<Button>(R.id.btnSave).setOnClickListener { saveImage() }

        findViewById<Button>(R.id.btnHistory).setOnClickListener {
            showHistoryDialog()
        }
    }

    // ---------- تاریخچه ----------
    private fun showHistoryDialog() {
        val textElements = canvasView.elements.filterIsInstance<CanvasView.TextElement>()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8,8,8,8)
        }

        if (textElements.isEmpty()) {
            container.addView(TextView(this).apply { text = "هیچ متنی وجود ندارد" })
        } else {
            for ((index, el) in textElements.withIndex()) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0,4,0,4)
                    }
                    gravity = Gravity.CENTER_VERTICAL
                }
                val textView = TextView(this).apply {
                    text = el.text
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    textSize = 16f
                    maxLines = 2
                }
                val copyBtn = ImageButton(this).apply {
                    setImageResource(android.R.drawable.ic_menu_edit)
                    setOnClickListener {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("text", el.text))
                        toast("کپی شد")
                    }
                }
                val delBtn = ImageButton(this).apply {
                    setImageResource(android.R.drawable.ic_menu_delete)
                    setOnClickListener {
                        canvasView.elements.remove(el)
                        canvasView.invalidate()
                        selectedIndex = -1; canvasView.selectedElementIndex = -1
                        updateUI()
                        toast("حذف شد")
                        (parent?.parent?.parent as? AlertDialog)?.dismiss()
                    }
                }
                row.addView(textView); row.addView(copyBtn); row.addView(delBtn)
                container.addView(row)
            }
        }
        val deleteAllBtn = Button(this).apply {
            text = "حذف همه متون"
            setOnClickListener {
                canvasView.elements.removeAll { it is CanvasView.TextElement }
                canvasView.invalidate()
                selectedIndex = -1; canvasView.selectedElementIndex = -1
                updateUI()
                toast("همه متون حذف شدند")
                (parent as? AlertDialog)?.dismiss()
            }
        }
        container.addView(deleteAllBtn)

        val scrollView = ScrollView(this)
        scrollView.addView(container)

        AlertDialog.Builder(this)
            .setTitle("تاریخچه متون")
            .setView(scrollView)
            .setPositiveButton("بستن", null)
            .show()
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
            canvasView.selectedElementIndex = -1
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
        } else tvSize.text = "60"
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
            tvSize.text = "60"; tvRotation.text = "0°"; btnLock.text = "قفل"; btnStyle.text = "زیرخط"
        }
    }

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

        val numbersLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 8
            }
        }
        val hscroll = HorizontalScrollView(this)
        val numContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (i in 1..50) {
            val circledChar = getCircledNumber(i)
            val btn = TextView(this).apply {
                text = circledChar
                background = resources.getDrawable(R.drawable.circle_number_bg, null)
                gravity = Gravity.CENTER
                width = 44.dpToPx(); height = 44.dpToPx()
                textSize = 16f
                setOnClickListener {
                    val start = maxOf(editText.selectionStart, 0)
                    val end = maxOf(editText.selectionEnd, 0)
                    val newText = editText.text.replace(start, end, circledChar)
                    editText.setText(newText)
                    editText.setSelection(start + circledChar.length)
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
                    val defaultFont = fontMap[fontNames.lastOrNull()] ?: Typeface.DEFAULT
                    val el = CanvasView.TextElement(text, 640f, 360f, 60f, defaultFont, fontNames.lastOrNull() ?: "پیش‌فرض")
                    canvasView.elements.add(el)
                    selectedIndex = canvasView.elements.size - 1
                    canvasView.selectedElementIndex = selectedIndex
                    canvasView.invalidate()
                    updateUI()
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun getCircledNumber(num: Int): String {
        return when {
            num in 1..20 -> String(Character.toChars(0x2460 + num - 1))
            num in 21..35 -> String(Character.toChars(0x3251 + num - 21))
            num in 36..50 -> String(Character.toChars(0x32B1 + num - 36))
            else -> "($num)"
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

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
                    pushUndo()
                    textEl.text = newText
                    canvasView.invalidate()
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun showFontPicker() {
        if (fontNames.isEmpty()) {
            toast("ابتدا فونت اضافه کنید")
            openFontLauncher.launch(arrayOf("font/ttf", "application/x-font-ttf", "*/*"))
            return
        }
        val adapter = object : ArrayAdapter<String>(this, 0, fontNames.toList()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_font_preview, parent, false)
                val name = getItem(position)!!
                val nameTv = view.findViewById<TextView>(R.id.fontName)
                val previewTv = view.findViewById<TextView>(R.id.fontPreview)
                nameTv.text = name
                val tf = fontMap[name] ?: Typeface.DEFAULT
                previewTv.typeface = tf
                return view
            }
        }
        AlertDialog.Builder(this)
            .setTitle("انتخاب فونت")
            .setAdapter(adapter) { _, which ->
                val name = fontNames[which]
                fontMap[name]?.let { tf ->
                    val el = canvasView.elements[selectedIndex] as CanvasView.TextElement
                    pushUndo()
                    el.typeface = tf; el.fontName = name
                    canvasView.invalidate()
                    toast("فونت $name اعمال شد")
                }
            }
            .setNeutralButton("بارگذاری جدید") { _, _ ->
                openFontLauncher.launch(arrayOf("font/ttf", "application/x-font-ttf", "*/*"))
            }
            .show()
    }

    // رنگ‌دهی برای متن یا تینت عکس
    private fun showColorPicker(forImage: Boolean) {
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
        val el = canvasView.elements[selectedIndex]
        // اضافه کردن گزینه "بدون رنگ" برای عکس‌ها
        val colorList = colors.toMutableList()
        if (forImage) colorList.add(null to "بدون رنگ")
        for ((color, name) in colorList) {
            val v = View(this).apply {
                setBackgroundColor(color ?: Color.TRANSPARENT)
                if (color == null) {
                    // نمایش علامت ضربدر یا متن
                    val paint = Paint().apply { color = Color.BLACK; textSize = 20f; textAlign = Paint.Align.CENTER }
                    setOnClickListener {
                        if (el is CanvasView.ImageElement) {
                            pushUndo()
                            el.tintColor = null
                            canvasView.invalidate()
                        }
                        (parent?.parent?.parent as? AlertDialog)?.dismiss()
                    }
                } else {
                    setOnClickListener {
                        when {
                            el is CanvasView.TextElement -> {
                                pushUndo()
                                el.color = color
                                canvasView.invalidate()
                            }
                            el is CanvasView.ImageElement -> {
                                pushUndo()
                                el.tintColor = color
                                canvasView.invalidate()
                            }
                        }
                        (parent?.parent?.parent as? AlertDialog)?.dismiss()
                    }
                }
                layoutParams = ViewGroup.LayoutParams(80, 80)
            }
            gridLayout.addView(v)
        }
        AlertDialog.Builder(this)
            .setTitle(if (forImage) "تینت عکس" else "رنگ متن")
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
            val w = bitmap.width * scale; val h = bitmap.height * scale
            pushUndo()
            val el = CanvasView.ImageElement(bitmap, 640f, 360f, w, h)
            canvasView.elements.add(el)
            selectedIndex = canvasView.elements.size - 1
            canvasView.selectedElementIndex = selectedIndex
            canvasView.invalidate()
            updateUI()
            toast("برچسب اضافه شد")
        } catch (e: Exception) { toast("خطا در بارگذاری تصویر") }
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
        } catch (e: Exception) { toast("خطا در بارگذاری فونت") }
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
                val scaleX = outW.toFloat() / 1280f; val scaleY = outH.toFloat() / 720f
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
                withContext(Dispatchers.Main) { toast("تصویر ۳۲۶۴×۱۸۳۶ در گالری ذخیره شد") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("خطا: ${e.message}") }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
