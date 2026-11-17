package com.camcalc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var photoFile: File
    private lateinit var photoUri: Uri
    private lateinit var preview: ImageView
    private lateinit var exprInput: EditText
    private lateinit var resultView: TextView
    private lateinit var statusView: TextView
    private lateinit var modeSpinner: Spinner

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) onPhoto() else status("لم يتم التصوير")
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importFromGallery(uri)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.parseColor("#F4F6FA"))
        }

        root.addView(TextView(this).apply {
            text = "حاسبة التصوير"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0D2A57"))
        })
        root.addView(TextView(this).apply {
            text = "صوّر مسألة أو صفحة كاملة، واختر العملية المطلوبة"
            textSize = 14f
            setTextColor(Color.parseColor("#667085"))
            setPadding(0, dp(4), 0, dp(16))
        })

        root.addView(Button(this).apply {
            text = "📷  صوّر المسألة"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0D2A57"))
            setOnClickListener { launchCamera() }
        }, LinearLayout.LayoutParams(-1, dp(56)))

        root.addView(Button(this).apply {
            text = "🖼️  اختر صورة من المعرض"
            textSize = 16f
            setOnClickListener { pickImage.launch("image/*") }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })

        preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, dp(200)).apply { topMargin = dp(16) })

        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#667085"))
            setPadding(0, dp(12), 0, dp(4))
        }
        root.addView(statusView)

        modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "حل كل مسألة في الصفحة",
                    "جمع كل الأرقام",
                    "طرح (الأول ناقص الباقي)",
                    "ضرب كل الأرقام",
                    "المتوسط"
                )
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (exprInput.text.isNotBlank()) calculate()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        root.addView(modeSpinner, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })

        exprInput = EditText(this).apply {
            hint = "النص المقروء من الصورة (يمكنك تعديله)"
            textSize = 20f
            gravity = Gravity.START or Gravity.TOP
            textDirection = View.TEXT_DIRECTION_LTR
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }
        root.addView(exprInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        root.addView(Button(this).apply {
            text = "احسب"
            textSize = 16f
            setOnClickListener { calculate() }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })

        resultView = TextView(this).apply {
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.START
            textDirection = View.TEXT_DIRECTION_LTR
            setTextColor(Color.parseColor("#0D2A57"))
        }
        root.addView(resultView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun status(msg: String) { statusView.text = msg }

    private fun prepareFile() {
        val dir = File(cacheDir, "images").apply { mkdirs() }
        photoFile = File(dir, "problem.jpg")
        photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
    }

    private fun launchCamera() {
        prepareFile()
        takePicture.launch(photoUri)
    }

    private fun importFromGallery(uri: Uri) {
        try {
            prepareFile()
            contentResolver.openInputStream(uri)?.use { input ->
                photoFile.outputStream().use { input.copyTo(it) }
            } ?: run { status("تعذّر فتح الصورة"); return }
            onPhoto()
        } catch (e: Exception) {
            status("تعذّر تحميل الصورة: ${e.message}")
        }
    }

    private fun onPhoto() {
        showPreview()
        status("جاري قراءة المسألة...")
        resultView.text = ""
        try {
            val image = InputImage.fromFilePath(this, photoUri)
            recognizer.process(image)
                .addOnSuccessListener { t ->
                    val text = t.text.lines().map { it.trim() }
                        .filter { it.isNotEmpty() }.joinToString("\n")
                    if (text.isEmpty()) {
                        status("ما قدرت أقرأ نصًا. جرّب صورة أوضح أو اكتب يدويًا.")
                        exprInput.setText("")
                    } else {
                        status("تمت القراءة. عدّل النص إن لزم، واختر نوع العملية.")
                        exprInput.setText(text)
                        calculate()
                    }
                }
                .addOnFailureListener { status("خطأ في القراءة: ${it.message}") }
        } catch (e: Exception) {
            status("تعذّر فتح الصورة: ${e.message}")
        }
    }

    private fun calculate() {
        val text = exprInput.text.toString()
        if (text.isBlank()) { resultView.text = ""; return }
        val pos = modeSpinner.selectedItemPosition
        resultView.text = if (pos == 0) {
            val r = Calc.solveLines(text)
            if (r.isEmpty()) {
                status("ما لقيت مسألة. اختر عملية أخرى أو عدّل النص.")
                "؟"
            } else r.joinToString("\n")
        } else {
            Calc.aggregate(pos, Calc.numbers(text))
        }
    }

    private fun showPreview() {
        try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            var bmp = BitmapFactory.decodeFile(photoFile.absolutePath, opts) ?: return
            val orientation = ExifInterface(photoFile.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val deg = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (deg != 0f) {
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height,
                    Matrix().apply { postRotate(deg) }, true)
            }
            preview.setImageBitmap(bmp)
            preview.visibility = View.VISIBLE
        } catch (_: Exception) { }
    }
}
