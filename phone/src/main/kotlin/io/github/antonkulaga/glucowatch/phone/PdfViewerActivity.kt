package io.github.antonkulaga.glucowatch.phone

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.*
import java.io.File

/** Offline PDF viewer: only the selected page is rendered, keeping long reports bounded. */
class PdfViewerActivity : Activity() {
    private val scope = MainScope()
    private var renderer: PdfRenderer? = null
    private var cachedFile: File? = null
    private var bitmap: Bitmap? = null
    private var page = 0
    private var rendering = false
    private lateinit var status: TextView
    private lateinit var image: ImageView
    private lateinit var previous: Button
    private lateinit var next: Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(16, 32, 16, 24); setBackgroundColor(Color.WHITE)
            fitsSystemWindows = true
        }
        status = TextView(this).apply { text = "Opening PDF…"; setTextColor(Color.BLACK); textSize = 16f }
        val controls = LinearLayout(this)
        previous = Button(this).apply { text = "Previous"; setOnClickListener { showPage(page - 1) } }
        next = Button(this).apply { text = "Next"; setOnClickListener { showPage(page + 1) } }
        controls.addView(previous); controls.addView(next)
        column.addView(status); column.addView(controls)
        image = ImageView(this).apply { adjustViewBounds = true; contentDescription = "PDF page" }
        column.addView(ScrollView(this).apply { addView(image) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(column)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        previous.isEnabled = false; next.isEnabled = false
        scope.launch {
            try {
                val uri = requireNotNull(intent.data) { "No PDF selected" }
                require(uri.scheme == "content") { "Open a PDF from Downloads" }
                cachedFile = File.createTempFile("pdf-view-", ".pdf", cacheDir)
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        cachedFile!!.outputStream().use { output ->
                            val buffer = ByteArray(8192); var total = 0L
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer); if (count < 0) break
                                total += count; require(total <= 100L * 1024 * 1024) { "PDF exceeds 100 MB" }
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error("Could not open PDF")
                }
                renderer = PdfRenderer(ParcelFileDescriptor.open(cachedFile, ParcelFileDescriptor.MODE_READ_ONLY))
                showPage(savedInstanceState?.getInt("page") ?: 0)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { status.text = error.message ?: "Could not display PDF" }
        }
    }
    private fun showPage(index: Int) {
        val document = renderer ?: return
        if (rendering || index !in 0 until document.pageCount) return
        rendering = true; previous.isEnabled = false; next.isEnabled = false
        scope.launch {
            try {
                // Rendering stays on this activity's main dispatcher so destruction cannot close an active page.
                val rendered = document.openPage(index).use { selected ->
                    val width = (resources.displayMetrics.widthPixels * 2).coerceIn(800, 2048)
                    val height = (width.toFloat() * selected.height / selected.width).toInt().coerceIn(1, 8192)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE); selected.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
                image.setImageBitmap(rendered); bitmap?.recycle(); bitmap = rendered; page = index
                status.text = "Page ${index + 1} of ${document.pageCount}"
            } catch (error: Exception) { status.text = error.message ?: "Could not display page" }
            finally {
                rendering = false; previous.isEnabled = page > 0; next.isEnabled = page + 1 < document.pageCount
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("page", page); super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        scope.cancel(); renderer?.close(); image.setImageDrawable(null); bitmap?.recycle(); cachedFile?.delete(); super.onDestroy()
    }
}
