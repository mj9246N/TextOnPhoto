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
    private val fontMap = mutableMapOf<String, Typeface>()
    private val fontNames = mutableListOf<String>()
    private lateinit var tvSize: TextView
    private lateinit var tvRotation: TextView
    private lateinit var btnLock: Button
    private lateinit var btnStyle: Button

    private val undoStack = mutableListOf<List<CanvasView.CanvasElement>>()
    private val MAX_UNDO = 100

    private val historyTexts = mutableListOf<String>()

    // ذخیره‌سازی آخرین فونت انتخاب‌شده
    private var lastUsedFont: String? = null

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

        loadStoredFonts()   // بارگذاری لیست فونت‌ها و آخرین فونت استفاده‌شده
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
                toast("فقط برای متن"); return@setOnClickListener
            }
            showFontPicker()
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

        // دکمه تاریخچه (حالا در نوار ابزار پایین)
        findViewById<Button>(R.id.btnHistory).setOnClickListener { showHistoryDialog() }
    }

    // ===================== تاریخچه =====================
    private fun addToHistory(text: String) {
        if (!historyTexts.contains(text)) {
            historyTexts.add(0, text)
            if (historyTexts.size > 200) historyTexts.removeAt(historyTexts.size - 1)
        }
    }

    private fun showHistoryDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }
        if (historyTexts.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "تاریخچه خالی است"
                setTextColor(Color.BLACK)
            })
        } else {
            for (i in historyTexts.indices) {
                var text = historyTexts[i]
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 4, 0, 4) }
                    gravity = Gravity.CENTER_VERTICAL
                }
                val tv = TextView(this).apply {
                    text = text
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    textSize = 16f
                    maxLines = 2
                    setTextColor(Color.BLACK)
                }
                val copyBtn = ImageButton(this).apply {
                    setImageResource(android.R.drawable.ic_menu_edit)
                    setOnClickListener {
                        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("text", text))
                        toast("کپی شد")
                    }
                }
                val delBtn = ImageButton(this).apply {
                    setImageResource(android.R.drawable.ic_menu_delete)
                    setOnClickListener {
                        historyTexts.removeAt(i)
                        container.removeView(row)
                        if (historyTexts.isEmpty()) {
                            container.removeAllViews()
                            container.addView(TextView(context).apply {
                                text = "تاریخچه خالی است"
                                setTextColor(Color.BLACK)
                            })
                        }
                        toast("حذف شد")
                    }
                }
                row.addView(tv)
                row.addView(copyBtn)
                row.addView(delBtn)
                container.addView(row)
            }
        }
        val deleteAllBtn = Button(this).apply {
            text = "حذف همه"
            setOnClickListener {
                historyTexts.clear()
                container.removeAllViews()
                container.addView(TextView(context).apply {
                    text = "تاریخچه خالی است"
                    setTextColor(Color.BLACK)
                })
                toast("همه حذف شدند")
            }
        }
        container.addView(deleteAllBtn)

        AlertDialog.Builder(this)
            .setTitle("تاریخچه متون")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("بستن", null)
            .show()
    }

    // ===================== افزودن متن (با اعداد و فونت پیش‌فرض هوشمند) =====================
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

        // اعداد دایره‌ای با اندازه ثابت
        val numContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val hscroll = HorizontalScrollView(this)
        for (i in 1..50) {
            val btn = TextView(this).apply {
                text = getCircledNumber(i)
                background = getDrawable(R.drawable.circle_number_bg)
                gravity = Gravity.CENTER
                val size = (44 * resources.displayMetrics.density).toInt()
                width = size
                height = size
                textSize = 16f
                setOnClickListener {
                    val start = maxOf(editText.selectionStart, 0)
                    val end = maxOf(editText.selectionEnd, 0)
                    editText.text.replace(start, end, text)
                    editText.setSelection(start + text.length)
                }
            }
            numContainer.addView(btn)
        }
        hscroll.addView(numContainer)
        layout.addView(hscroll)

        AlertDialog.Builder(this).setTitle("متن جدید").setView(layout)
            .setPositiveButton("افزودن") { _, _ ->
                val text = editText.text.toString().trim()
                if (text.isNotEmpty()) {
                    pushUndo()
                    // انتخاب فونت: آخرین فونت استفاده‌شده، در غیر این صورت آخرین فونت موجود، وگرنه پیش‌فرض
                    val defaultFont = if (lastUsedFont != null && fontMap.containsKey(lastUsedFont)) {
                        fontMap[lastUsedFont]!!
                    } else {
                        fontMap[fontNames.lastOrNull()] ?: Typeface.DEFAULT
                    }
                    val fontNameToUse = if (lastUsedFont != null && fontMap.containsKey(lastUsedFont)) {
                        lastUsedFont!!
                    } else {
                        fontNames.lastOrNull() ?: "پیش‌فرض"
                    }

                    val el = CanvasView.TextElement(
                        text, 640f, 360f, 60f, defaultFont, fontNameToUse
                    )
                    canvasView.elements.add(el)
                    selectedIndex = canvasView.elements.size - 1
                    canvasView.selectedElementIndex = selectedIndex
                    canvasView.invalidate()
                    updateUI()
                    addToHistory(text)
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    // ===================== ویرایش متن =====================
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
                    addToHistory(newText)
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    // ===================== فونت (با ذخیره آخرین انتخاب) =====================
    private fun showFontPicker() {
        if (fontNames.isEmpty()) {
            toast("ابتدا فونت اضافه کنید")
            openFontLauncher.launch(arrayOf("font/ttf", "application/x-font-ttf", "*/*"))
            return
        }
        val adapter = object : ArrayAdapter<String>(this, 0, fontNames.toList()) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val v = cv ?: layoutInflater.inflate(R.layout.item_font_preview, parent, false)
                val name = getItem(pos)!!
                v.findViewById<TextView>(R.id.fontName).text = name
                v.findViewById<TextView>(R.id.fontPreview).typeface = fontMap[name] ?: Typeface.DEFAULT
                return v
            }
        }
        AlertDialog.Builder(this).setTitle("انتخاب فونت").setAdapter(adapter) { _, w ->
            val name = fontNames[w]
            fontMap[name]?.let { tf ->
                val el = canvasView.elements[selectedIndex] as CanvasView.TextElement
                pushUndo()
                el.typeface = tf
                el.fontName = name
                // ذخیره به‌عنوان آخرین فونت استفاده‌شده
                lastUsedFont = name
                saveLastUsedFont(name)
                canvasView.invalidate()
                toast("فونت $name اعمال شد")
            }
        }.setNeutralButton("بارگذاری جدید") { _, _ ->
            openFontLauncher.launch(arrayOf("font/ttf", "application/x-font-ttf", "*/*"))
        }.show()
    }

    private fun saveLastUsedFont(name: String) {
        getSharedPreferences("fonts", MODE_PRIVATE)
            .edit().putString("lastFont", name).apply()
    }

    private fun loadLastUsedFont() {
        lastUsedFont = getSharedPreferences("fonts", MODE_PRIVATE)
            .getString("lastFont", null)
    }

    // ===================== ذخیره و بازیابی فونت‌ها =====================
    private fun loadFont(uri: Uri) {
        try {
            val inp = contentResolver.openInputStream(uri) ?: return
            val dir = File(filesDir, "fonts"); if (!dir.exists()) dir.mkdirs()
            val name = uri.lastPathSegment ?: "font.ttf"
            val dest = File(dir, name)
            dest.outputStream().use { inp.copyTo(it) }
            inp.close()
            val tf = Typeface.createFromFile(dest)
            val fname = name.removeSuffix(".ttf").removeSuffix(".TTF")
            fontMap[fname] = tf
            if (!fontNames.contains(fname)) {
                fontNames.add(fname)
                saveFontList()
            }
            toast("فونت '$fname' اضافه شد")
        } catch (e: Exception) { toast("خطا در بارگذاری فونت") }
    }

    private fun saveFontList() {
        getSharedPreferences("fonts", MODE_PRIVATE)
            .edit().putStringSet("names", fontNames.toSet()).apply()
    }

    private fun loadStoredFonts() {
        val prefs = getSharedPreferences("fonts", MODE_PRIVATE)
        val names = prefs.getStringSet("names", emptySet()) ?: emptySet()
        fontNames.addAll(names)
        val dir = File(filesDir, "fonts")
        for (n in fontNames) {
            val f = File(dir, "$n.ttf")
            if (f.exists()) {
                try { fontMap[n] = Typeface.createFromFile(f) } catch (_: Exception) {}
            }
        }
        // بارگذاری آخرین فونت استفاده‌شده
        lastUsedFont = prefs.getString("lastFont", null)
        // اگر فونت ذخیره‌شده دیگر وجود نداشت، پاک شود
        if (lastUsedFont != null && !fontMap.containsKey(lastUsedFont)) {
            lastUsedFont = null
            prefs.edit().remove("lastFont").apply()
        }
    }

    // ===================== ابزارهای عمومی =====================
    private fun pushUndo() {
        val snapshot = canvasView.elements.map { it.clone() }
        undoStack.add(snapshot)
        if (undoStack.size > MAX_UNDO) undoStack.removeAt(0)
    }

    private fun performUndo() {
        if (undoStack.size > 1) {
            undoStack.removeLast()
            val prev = undoStack.last()
            canvasView.elements.clear()
            canvasView.elements.addAll(prev)
            selectedIndex = -1
            canvasView.selectedElementIndex = -1
            canvasView.invalidate()
            updateUI()
        }
    }

    private fun rotateSelected(d: Float) {
        if (selectedIndex == -1) return
        val e = canvasView.elements[selectedIndex]
        if (e.locked) { toast("قفل"); return }
        pushUndo()
        e.rotation = (e.rotation + d).coerceIn(-180f, 180f)
        canvasView.invalidate()
        updateUI()
    }

    private fun updateSizeDisplay() {
        tvSize.text = if (selectedIndex != -1) {
            (canvasView.elements[selectedIndex] as? CanvasView.TextElement)?.size?.toInt()?.toString() ?: "عکس"
        } else "60"
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
                tvSize.text = "عکس"
                btnStyle.text = "زیرخط"
            }
        } else {
            tvSize.text = "60"
            tvRotation.text = "0°"
            btnLock.text = "قفل"
            btnStyle.text = "زیرخط"
        }
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
        val list = colors.toMutableList()
        if (forImage) list.add(null to "بدون رنگ")

        for ((color, _) in list) {
            val v = View(this).apply {
                setBackgroundColor(color ?: Color.TRANSPARENT)
                layoutParams = ViewGroup.LayoutParams(80, 80)
                setOnClickListener {
                    when {
                        color == null && el is CanvasView.ImageElement -> {
                            pushUndo(); (el as CanvasView.ImageElement).tintColor = null; canvasView.invalidate()
                        }
                        el is CanvasView.TextElement && color != null -> {
                            pushUndo(); (el as CanvasView.TextElement).color = color; canvasView.invalidate()
                        }
                        el is CanvasView.ImageElement && color != null -> {
                            pushUndo(); (el as CanvasView.ImageElement).tintColor = color; canvasView.invalidate()
                        }
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
            canvasView.invalidate()
            updateUI()
            toast("برچسب اضافه شد")
        } catch (e: Exception) { toast("خطا در بارگذاری تصویر") }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun saveImage() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val bw = 3264; val bh = 1836
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
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("خطا: ${e.message}") }
            }
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
