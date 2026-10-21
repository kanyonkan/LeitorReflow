package com.leitor.reflow

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())

    // ---- modo refluxo (texto) ----
    private val pages = ArrayList<String>()
    private var pagesUri: Uri? = null
    private var pagesComplete = false

    // ---- modo original (imagem das páginas) ----
    private val sizes = ArrayList<IntArray>() // [largura, altura] de cada página
    private val renderExec = Executors.newSingleThreadExecutor()
    private val rendererLock = Any()
    private var renderer: PdfRenderer? = null
    private var renderFd: ParcelFileDescriptor? = null

    // ---- estado ----
    private var currentUri: Uri? = null
    private var reflowMode = true
    private var night = false
    private var textSp = 18f
    private var zoom = 1f
    private var barVisible = true
    private var loadToken = 0
    private var listWidth = 0

    // ---- views ----
    private lateinit var root: FrameLayout
    private lateinit var hScroll: HorizontalScrollView
    private lateinit var list: RecyclerView
    private lateinit var bar: LinearLayout
    private lateinit var status: TextView
    private lateinit var modeBtn: Button
    private lateinit var themeBtn: Button
    private val buttons = ArrayList<Button>()
    private lateinit var controller: WindowInsetsControllerCompat

    private val reflowAdapter = ReflowAdapter()
    private val imageAdapter = ImageAdapter()

    private val invertFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                -0.86f, 0f, 0f, 0f, 220f,
                0f, -0.86f, 0f, 0f, 220f,
                0f, 0f, -0.86f, 0f, 220f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) open(uri)
    }

    // toque simples no centro da tela mostra/esconde a barra
    private val tapDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val w = root.width
                val h = root.height
                if (e.x > w * 0.25f && e.x < w * 0.75f && e.y > h * 0.25f && e.y < h * 0.75f) {
                    toggleBar()
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        tapDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)

        val prefs = getSharedPreferences("leitor", MODE_PRIVATE)
        night = prefs.getBoolean("night", false)
        textSp = prefs.getFloat("textSp", 18f)
        zoom = prefs.getFloat("zoom", 1f)
        reflowMode = prefs.getBoolean("reflow", true)

        // tela cheia: o app desenha atrás das barras do sistema
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT

        root = FrameLayout(this)

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            clipToPadding = false
            setPadding(0, dp(30), 0, dp(30))
            setItemViewCacheSize(2)
        }
        hScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        hScroll.addView(list, FrameLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            hScroll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(makeBtn("Abrir") { picker.launch(arrayOf("application/pdf")) })
        modeBtn = makeBtn("") { toggleMode() }
        row.addView(modeBtn)
        row.addView(makeBtn("A−") { changeSize(-1) })
        row.addView(makeBtn("A+") { changeSize(+1) })
        themeBtn = makeBtn("") { toggleTheme() }
        row.addView(themeBtn)
        bar.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        status = TextView(this).apply {
            text = "Toque em Abrir e escolha um PDF."
            textSize = 13f
            setPadding(dp(10), dp(6), dp(10), dp(2))
        }
        bar.addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )
        setContentView(root)

        controller = WindowCompat.getInsetsController(window, root)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // a barra de botões respeita a barra de status e o recorte da câmera
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sb = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            bar.setPadding(dp(6) + sb.left, sb.top + dp(4), dp(6) + sb.right, dp(4))
            insets
        }

        applyWidth()
        applyMode()
        applyTheme()
        ViewCompat.requestApplyInsets(root)

        intent?.data?.let { open(it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyWidth()
        list.adapter?.notifyDataSetChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        loadToken++
        closeRenderer()
        renderExec.shutdownNow()
    }

    // ------------------------------------------------------------------ interface

    private fun makeBtn(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { onClick() }
            buttons.add(this)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(2), 0, dp(2), 0) }
        }

    private fun toggleBar() {
        barVisible = !barVisible
        bar.visibility = if (barVisible) View.VISIBLE else View.GONE
        if (barVisible) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun changeSize(dir: Int) {
        val prefs = getSharedPreferences("leitor", MODE_PRIVATE).edit()
        if (reflowMode) {
            textSp = (textSp + dir).coerceIn(12f, 34f)
            prefs.putFloat("textSp", textSp)
            reflowAdapter.notifyDataSetChanged()
        } else {
            zoom = (zoom + dir * 0.25f).coerceIn(1f, 2f)
            prefs.putFloat("zoom", zoom)
            applyWidth()
            imageAdapter.notifyDataSetChanged()
        }
        prefs.apply()
    }

    private fun toggleTheme() {
        night = !night
        getSharedPreferences("leitor", MODE_PRIVATE).edit().putBoolean("night", night).apply()
        applyTheme()
    }

    private fun toggleMode() {
        reflowMode = !reflowMode
        getSharedPreferences("leitor", MODE_PRIVATE).edit().putBoolean("reflow", reflowMode).apply()
        applyMode()
        applyTheme()
        show()
    }

    private fun applyMode() {
        modeBtn.text = if (reflowMode) "Modo: Refluxo" else "Modo: Original"
        applyWidth()
        list.adapter = if (reflowMode) reflowAdapter else imageAdapter
    }

    /** No modo original, a largura da lista vira largura da tela x zoom (rolagem lateral). */
    private fun applyWidth() {
        listWidth = (resources.displayMetrics.widthPixels * (if (reflowMode) 1f else zoom)).toInt()
        list.layoutParams = FrameLayout.LayoutParams(listWidth, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun applyTheme() {
        val bg = if (night) Color.parseColor("#121212") else Color.parseColor("#FAF8F3")
        val gap = if (night) Color.parseColor("#0A0A0A") else Color.parseColor("#C9C5B9")
        val fg = if (night) Color.parseColor("#D6D3CB") else Color.parseColor("#1B1B1B")
        val barBg = if (night) Color.parseColor("#F01E1E1E") else Color.parseColor("#F0E9E5DA")
        root.setBackgroundColor(bg)
        hScroll.setBackgroundColor(if (reflowMode) bg else gap)
        list.setBackgroundColor(if (reflowMode) bg else gap)
        bar.setBackgroundColor(barBg)
        status.setTextColor(fg)
        buttons.forEach {
            it.setTextColor(fg)
            it.setBackgroundColor(if (night) Color.parseColor("#2C2C2C") else Color.parseColor("#D8D3C4"))
        }
        themeBtn.text = if (night) "☀ Dia" else "☾ Noite"
        controller.isAppearanceLightStatusBars = !night
        controller.isAppearanceLightNavigationBars = !night
        reflowAdapter.notifyDataSetChanged()
        imageAdapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------------ abrir PDF

    private fun open(uri: Uri) {
        currentUri = uri
        if (pagesUri != uri) {
            pages.clear()
            pagesUri = null
            pagesComplete = false
        }
        show()
    }

    private fun show() {
        val uri = currentUri ?: return
        val token = ++loadToken
        closeRenderer()
        if (reflowMode) showReflow(uri, token) else showOriginal(uri, token)
    }

    private fun showReflow(uri: Uri, token: Int) {
        if (pagesUri == uri && pagesComplete) {
            reflowAdapter.notifyDataSetChanged()
            status.text = "${pages.size} páginas · refluxo"
            return
        }
        pages.clear()
        pagesUri = uri
        pagesComplete = false
        reflowAdapter.notifyDataSetChanged()
        status.text = "Abrindo…"

        Thread {
            try {
                contentResolver.openInputStream(uri)!!.use { input ->
                    PDDocument.load(input).use { doc ->
                        val total = doc.numberOfPages
                        val stripper = PDFTextStripper().apply { sortByPosition = true }
                        var withText = 0
                        for (i in 1..total) {
                            if (token != loadToken) return@Thread
                            stripper.startPage = i
                            stripper.endPage = i
                            val text = Reflow.reflow(stripper.getText(doc))
                            if (text.isNotBlank()) withText++
                            main.post {
                                if (token != loadToken) return@post
                                pages.add(text)
                                reflowAdapter.notifyItemInserted(pages.size - 1)
                                status.text = if (i < total) "Carregando página $i de $total…"
                                else "$total páginas · refluxo"
                                if (i == total) pagesComplete = true
                            }
                        }
                        if (withText == 0) {
                            main.post {
                                if (token != loadToken) return@post
                                status.text =
                                    "Este PDF não tem texto (é só imagem). Use o modo Original ou faça o OCR."
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (token != loadToken) return@post
                    status.text = "Não foi possível abrir o PDF."
                    Toast.makeText(this, e.message ?: "Erro", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showOriginal(uri: Uri, token: Int) {
        sizes.clear()
        imageAdapter.notifyDataSetChanged()
        status.text = "Abrindo…"

        Thread {
            var fd: ParcelFileDescriptor? = null
            var pr: PdfRenderer? = null
            try {
                val f = contentResolver.openFileDescriptor(uri, "r")!!
                fd = f
                val r = PdfRenderer(f)
                pr = r
                val found = ArrayList<IntArray>()
                for (i in 0 until r.pageCount) {
                    if (token != loadToken) {
                        r.close(); f.close()
                        return@Thread
                    }
                    val p = r.openPage(i)
                    found.add(intArrayOf(p.width, p.height))
                    p.close()
                }
                main.post {
                    if (token != loadToken) {
                        r.close(); f.close()
                        return@post
                    }
                    synchronized(rendererLock) {
                        renderer = r
                        renderFd = f
                    }
                    sizes.addAll(found)
                    imageAdapter.notifyDataSetChanged()
                    status.text = "${found.size} páginas · original"
                }
            } catch (e: Throwable) {
                try { pr?.close() } catch (_: Throwable) {}
                try { fd?.close() } catch (_: Throwable) {}
                main.post {
                    if (token != loadToken) return@post
                    status.text = "Não foi possível abrir o PDF no modo Original."
                    Toast.makeText(this, e.message ?: "Erro", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun closeRenderer() {
        synchronized(rendererLock) {
            try { renderer?.close() } catch (_: Throwable) {}
            try { renderFd?.close() } catch (_: Throwable) {}
            renderer = null
            renderFd = null
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ------------------------------------------------------------------ adaptador: refluxo

    private inner class ReflowAdapter : RecyclerView.Adapter<ReflowAdapter.VH>() {

        inner class VH(val box: LinearLayout, val label: TextView, val body: TextView) :
            RecyclerView.ViewHolder(box)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val box = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(dp(20), dp(14), dp(20), dp(18))
            }
            val label = TextView(parent.context).apply {
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                alpha = 0.55f
            }
            val body = TextView(parent.context).apply {
                typeface = Typeface.SERIF
                setLineSpacing(0f, 1.4f)
            }
            box.addView(label)
            box.addView(body)
            return VH(box, label, body)
        }

        override fun getItemCount() = pages.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val fg = if (night) Color.parseColor("#D6D3CB") else Color.parseColor("#1B1B1B")
            h.label.setTextColor(fg)
            h.label.text = "— ${position + 1} —"
            h.body.setTextColor(fg)
            h.body.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            h.body.text = pages[position].ifBlank { "(página sem texto)" }
        }
    }

    // ------------------------------------------------------------------ adaptador: original

    private inner class ImageAdapter : RecyclerView.Adapter<ImageAdapter.ImgVH>() {

        inner class ImgVH(val img: ImageView) : RecyclerView.ViewHolder(img) {
            @Volatile
            var boundPos = -1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImgVH {
            val img = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(100))
            }
            return ImgVH(img)
        }

        override fun getItemCount() = sizes.size

        override fun onBindViewHolder(h: ImgVH, position: Int) {
            val sz = sizes[position]
            val targetW = listWidth.coerceAtLeast(1)
            val targetH = (targetW.toLong() * sz[1] / sz[0]).toInt().coerceAtLeast(1)

            val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, targetH)
            lp.bottomMargin = dp(6)
            h.img.layoutParams = lp
            h.img.colorFilter = if (night) invertFilter else null
            h.img.setBackgroundColor(if (night) Color.parseColor("#1A1A1A") else Color.WHITE)
            h.img.setImageBitmap(null)
            h.boundPos = position

            val r = renderer ?: return
            // limita o bitmap para não estourar a memória em telas grandes com zoom
            val renderW = minOf(targetW, 2400)
            val renderH = (renderW.toLong() * sz[1] / sz[0]).toInt().coerceAtLeast(1)

            renderExec.execute {
                if (h.boundPos != position) return@execute
                var bmp: Bitmap? = null
                synchronized(rendererLock) {
                    val rr = renderer
                    if (rr == null || rr !== r) return@execute
                    try {
                        val page = rr.openPage(position)
                        val b = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                        b.eraseColor(Color.WHITE)
                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        bmp = b
                    } catch (_: Throwable) {
                    }
                }
                val done = bmp ?: return@execute
                main.post { if (h.boundPos == position) h.img.setImageBitmap(done) }
            }
        }

        override fun onViewRecycled(h: ImgVH) {
            h.boundPos = -1
            h.img.setImageBitmap(null)
        }
    }
}
