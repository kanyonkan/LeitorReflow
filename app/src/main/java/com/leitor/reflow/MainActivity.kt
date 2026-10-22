package com.leitor.reflow

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * HorizontalScrollView mede o filho sem limite de largura e ignora o width dele.
 * Aqui o filho é medido exatamente com a largura definida (tela x zoom), então o
 * texto do refluxo quebra na largura da tela.
 */
class FixedWidthScroll(context: Context) : HorizontalScrollView(context) {
    override fun measureChildWithMargins(
        child: View,
        parentWidthMeasureSpec: Int,
        widthUsed: Int,
        parentHeightMeasureSpec: Int,
        heightUsed: Int
    ) {
        val lp = child.layoutParams as ViewGroup.MarginLayoutParams
        if (lp.width <= 0) {
            super.measureChildWithMargins(
                child, parentWidthMeasureSpec, widthUsed, parentHeightMeasureSpec, heightUsed
            )
            return
        }
        val w = MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY)
        val h = getChildMeasureSpec(
            parentHeightMeasureSpec,
            paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin + heightUsed,
            lp.height
        )
        child.measure(w, h)
    }
}

/** LinearLayoutManager que permite travar a rolagem lateral feita com o dedo. */
class PagerLayoutManager(context: Context, orientation: Int) :
    LinearLayoutManager(context, orientation, false) {
    var userScrollEnabled = true
    override fun canScrollHorizontally(): Boolean = userScrollEnabled && super.canScrollHorizontally()
}

/**
 * Texto selecionável (pressionar e arrastar para marcar e copiar) que também
 * reage ao toque simples sobre links (ClickableSpan).
 *
 * Se, depois de um toque longo, o Android não iniciar a seleção, chama
 * [onLongPressNoSelection] (usado para abrir a janela de texto da página).
 */
class LinkTextView(context: Context) : TextView(context) {

    var onLongPressNoSelection: (() -> Unit)? = null

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val l = layout ?: return false
            val sp = text as? Spanned ?: return false
            val x = e.x - totalPaddingLeft + scrollX
            val y = e.y - totalPaddingTop + scrollY
            if (y < 0 || y > l.height) return false
            val line = l.getLineForVertical(y.toInt())
            if (x < l.getLineLeft(line) || x > l.getLineRight(line)) return false
            val off = l.getOffsetForHorizontal(line, x)
            val spans = sp.getSpans(off, off, ClickableSpan::class.java)
            if (spans.isNotEmpty()) {
                spans[0].onClick(this@LinkTextView)
                return true
            }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            val cb = onLongPressNoSelection ?: return
            postDelayed({
                if (isAttachedToWindow && !hasSelection()) cb()
            }, 450)
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }
}

class MainActivity : AppCompatActivity() {

    private companion object {
        const val ZOOM_MAX = 2.5f
        const val ZOOM_STEP = 0.25f
        const val TEXT_MIN = 12f
        const val TEXT_MAX = 40f
        const val RENDER_MAX_W = 2700
    }

    private class PageText(val text: String, val spans: List<Reflow.Span>)

    private val main = Handler(Looper.getMainLooper())

    // ---- modo refluxo (texto) ----
    private val pages = ArrayList<PageText>()
    private val reflowTargets = ArrayList<LinkTarget>()
    private var pagesUri: Uri? = null
    private var pagesComplete = false

    // ---- modo original (imagem das páginas) ----
    private val sizes = ArrayList<IntArray>() // [largura, altura] de cada página
    private val origLinks = ArrayList<List<PageLink>>()
    private val origTargets = ArrayList<LinkTarget>()
    private val renderExec = Executors.newSingleThreadExecutor()
    private val rendererLock = Any()
    private var renderer: PdfRenderer? = null
    private var renderFd: ParcelFileDescriptor? = null

    // ---- estado ----
    private var currentUri: Uri? = null
    private var currentKey = ""
    private var pendingPage = -1
    private val backStack = ArrayList<Int>()
    private var lastLinkTap = 0L

    private var reflowMode = true
    private var night = false
    private var textSp = 18f
    private var zoom = 1f

    /** 0 = vertical contínuo, 1 = horizontal (uma página por tela, como um livro) */
    private var pageLayout = 0
    private val isBook: Boolean get() = pageLayout == 1

    private var rememberPage = true
    private var barVisible = true
    private var loadToken = 0
    private var listWidth = 0

    // ---- pinça e gestos ----
    private var scaling = false
    private var cancelSent = false
    private var pinchedRecently = false
    private var pinchFactor = 1f
    private var pinchFocusX = 0f
    private var canPanRightAtDown = false
    private var canPanLeftAtDown = false

    // ---- views ----
    private lateinit var root: FrameLayout
    private lateinit var hScroll: HorizontalScrollView
    private lateinit var list: RecyclerView
    private lateinit var bar: LinearLayout
    private lateinit var status: TextView
    private lateinit var pageIndicator: TextView
    private lateinit var modeBtn: Button
    private lateinit var themeBtn: Button
    private lateinit var layoutBtn: Button
    private lateinit var rememberBtn: Button
    private val buttons = ArrayList<Button>()
    private lateinit var controller: WindowInsetsControllerCompat
    private val snapHelper = PagerSnapHelper()

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

    // toque no centro mostra/esconde a barra; deslize rápido para os lados vira a página
    private val tapDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // um link acabou de ser tocado: não alterna a barra
                if (SystemClock.uptimeMillis() - lastLinkTap < 700) return false
                val w = root.width
                val h = root.height
                if (e.x > w * 0.25f && e.x < w * 0.75f && e.y > h * 0.25f && e.y < h * 0.75f) {
                    toggleBar()
                    return true
                }
                return false
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                // só no formato horizontal, modo original, com zoom: o RecyclerView está travado
                // e a página vira só se o gesto começou na borda da página ampliada
                if (!isBook || e1 == null || scaling || pinchedRecently) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val swipe = abs(dx) > dp(80) && abs(dx) > abs(dy) * 1.5f && abs(vx) > 600f
                if (!swipe) return false
                val dir = if (dx < 0) 1 else -1
                if (!reflowMode && zoom > 1f) {
                    val atEdge = if (dir > 0) !canPanRightAtDown else !canPanLeftAtDown
                    if (atEdge) {
                        turnPage(dir)
                        return true
                    }
                }
                return false
            }
        })
    }

    private val scaleDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                scaling = true
                cancelSent = false
                pinchFactor = 1f
                pinchFocusX = d.focusX
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                pinchFocusX = d.focusX
                if (reflowMode) {
                    textSp = (textSp * d.scaleFactor).coerceIn(TEXT_MIN, TEXT_MAX)
                    reflowAdapter.applyTextSizeToVisible()
                } else {
                    val target = (zoom * pinchFactor * d.scaleFactor).coerceIn(1f, ZOOM_MAX)
                    pinchFactor = target / zoom
                    hScroll.pivotX = d.focusX
                    hScroll.pivotY = d.focusY
                    hScroll.scaleX = pinchFactor
                    hScroll.scaleY = pinchFactor
                }
                return true
            }

            override fun onScaleEnd(d: ScaleGestureDetector) {
                scaling = false
                pinchedRecently = true
                if (reflowMode) {
                    getSharedPreferences("leitor", MODE_PRIVATE).edit().putFloat("textSp", textSp).apply()
                    reflowAdapter.notifyDataSetChanged()
                } else {
                    val f = pinchFactor
                    hScroll.scaleX = 1f
                    hScroll.scaleY = 1f
                    pinchFactor = 1f
                    if (abs(f - 1f) > 0.01f) setZoom(zoom * f, pinchFocusX)
                }
            }
        }).also { it.isQuickScaleEnabled = false }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            pinchedRecently = false
            canPanRightAtDown = hScroll.canScrollHorizontally(1)
            canPanLeftAtDown = hScroll.canScrollHorizontally(-1)
        }
        scaleDetector.onTouchEvent(ev)
        if (scaling) {
            // durante a pinça, o resto da tela não recebe os toques
            if (!cancelSent) {
                cancelSent = true
                val c = MotionEvent.obtain(ev)
                c.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(c)
                c.recycle()
            }
            return true
        }
        tapDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)

        val prefs = getSharedPreferences("leitor", MODE_PRIVATE)
        night = prefs.getBoolean("night", false)
        textSp = prefs.getFloat("textSp", 18f)
        zoom = prefs.getFloat("zoom", 1f).coerceIn(1f, ZOOM_MAX)
        reflowMode = prefs.getBoolean("reflow", true)
        rememberPage = prefs.getBoolean("remember", true)
        // "layout2" (0 vertical, 1 horizontal) substitui o antigo "layout" (2 = horizontal)
        pageLayout = when {
            prefs.contains("layout2") -> prefs.getInt("layout2", 0).coerceIn(0, 1)
            prefs.getInt("layout", 0) == 2 -> 1
            else -> 0
        }

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
            layoutManager = PagerLayoutManager(this@MainActivity, LinearLayoutManager.VERTICAL)
            clipToPadding = false
            setItemViewCacheSize(2)
        }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updatePageIndicator()
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) updatePageIndicator()
            }
        })
        hScroll = FixedWidthScroll(this).apply { isHorizontalScrollBarEnabled = false }
        hScroll.addView(list, FrameLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            hScroll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row1.addView(makeBtn("Abrir") { picker.launch(arrayOf("application/pdf")) })
        modeBtn = makeBtn("") { toggleMode() }
        row1.addView(modeBtn)
        row1.addView(makeBtn("A−") { changeSize(-1) })
        row1.addView(makeBtn("A+") { changeSize(+1) })
        themeBtn = makeBtn("") { toggleTheme() }
        row1.addView(themeBtn)
        bar.addView(row1, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        layoutBtn = makeBtn("") { cyclePageLayout() }
        rememberBtn = makeBtn("") { toggleRemember() }
        row2.addView(layoutBtn)
        row2.addView(rememberBtn)
        val row2Lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        row2Lp.topMargin = dp(2)
        bar.addView(row2, row2Lp)

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

        // número da página, sempre visível (inclusive com a barra escondida)
        pageIndicator = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B3000000"))
                cornerRadius = dp(14).toFloat()
            }
            visibility = View.GONE
        }
        val indLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        indLp.bottomMargin = dp(14)
        root.addView(pageIndicator, indLp)

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
            (pageIndicator.layoutParams as FrameLayout.LayoutParams).bottomMargin = sb.bottom + dp(12)
            pageIndicator.requestLayout()
            insets
        }

        // "Voltar" desfaz o salto de um link interno (volta para onde você estava)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (backStack.isNotEmpty()) {
                    val pos = backStack.removeAt(backStack.size - 1)
                    lm().scrollToPositionWithOffset(pos, 0)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        applyMode()
        applyTheme()
        updateLabels()
        ViewCompat.requestApplyInsets(root)

        intent?.data?.let { open(it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyWidth()
        list.adapter?.notifyDataSetChanged()
    }

    override fun onPause() {
        super.onPause()
        savePage()
    }

    override fun onDestroy() {
        super.onDestroy()
        loadToken++
        closeRenderer()
        renderExec.shutdownNow()
    }

    // ------------------------------------------------------------------ interface

    private fun lm(): LinearLayoutManager = list.layoutManager as LinearLayoutManager

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

    private fun updateLabels() {
        modeBtn.text = if (reflowMode) "Modo: Refluxo" else "Modo: Original"
        themeBtn.text = if (night) "☀ Dia" else "☾ Noite"
        layoutBtn.text = if (isBook) "Páginas: Horizontal" else "Páginas: Vertical"
        rememberBtn.text = if (rememberPage) "Lembrar página: Sim" else "Lembrar página: Não"
    }

    private fun toggleBar() {
        barVisible = !barVisible
        bar.visibility = if (barVisible) View.VISIBLE else View.GONE
        if (barVisible) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
        updatePageIndicator() // o número da página aparece e some junto com o menu
    }

    private fun changeSize(dir: Int) {
        if (reflowMode) {
            textSp = (textSp + dir).coerceIn(TEXT_MIN, TEXT_MAX)
            getSharedPreferences("leitor", MODE_PRIVATE).edit().putFloat("textSp", textSp).apply()
            reflowAdapter.notifyDataSetChanged()
        } else {
            setZoom(zoom + dir * ZOOM_STEP, resources.displayMetrics.widthPixels / 2f)
        }
    }

    /** Zoom do modo original: mantém a página em que você está e o ponto focal. */
    private fun setZoom(newZoom: Float, focusX: Float) {
        val old = zoom
        zoom = newZoom.coerceIn(1f, ZOOM_MAX)
        if (zoom == old) return
        getSharedPreferences("leitor", MODE_PRIVATE).edit().putFloat("zoom", zoom).apply()

        val ratio = zoom / old
        val first = if (isBook) currentPage() else lm().findFirstVisibleItemPosition().coerceAtLeast(0)
        val topOff = if (isBook) 0 else (lm().findViewByPosition(first)?.top ?: 0)
        val sx = hScroll.scrollX

        applyWidth()
        lockPaging()
        imageAdapter.notifyDataSetChanged()
        lm().scrollToPositionWithOffset(first, (topOff * ratio).toInt())
        hScroll.doOnNextLayout {
            hScroll.scrollTo(((sx + focusX) * ratio - focusX).toInt().coerceAtLeast(0), 0)
        }
    }

    private fun toggleTheme() {
        night = !night
        getSharedPreferences("leitor", MODE_PRIVATE).edit().putBoolean("night", night).apply()
        applyTheme()
        updateLabels()
    }

    private fun cyclePageLayout() {
        // guarda a página atual para continuar nela no novo formato
        if (pendingPage < 0 && (list.adapter?.itemCount ?: 0) > 0) pendingPage = currentPage()
        pageLayout = 1 - pageLayout
        getSharedPreferences("leitor", MODE_PRIVATE).edit().putInt("layout2", pageLayout).apply()
        hScroll.scrollTo(0, 0)
        applyMode()
        applyTheme()
        updateLabels()
        restorePending()
        updatePageIndicator()
    }

    private fun toggleRemember() {
        rememberPage = !rememberPage
        getSharedPreferences("leitor", MODE_PRIVATE).edit().putBoolean("remember", rememberPage).apply()
        if (rememberPage) savePage()
        updateLabels()
    }

    private fun toggleMode() {
        // guarda a página atual para continuar nela no outro modo
        if (pendingPage < 0 && (list.adapter?.itemCount ?: 0) > 0) pendingPage = currentPage()
        reflowMode = !reflowMode
        getSharedPreferences("leitor", MODE_PRIVATE).edit().putBoolean("reflow", reflowMode).apply()
        hScroll.scrollTo(0, 0)
        applyMode()
        applyTheme()
        updateLabels()
        show()
    }

    /** Monta a lista conforme o modo (refluxo/original) e o formato (vertical/horizontal). */
    private fun applyMode() {
        applyWidth()
        val orientation = if (isBook) LinearLayoutManager.HORIZONTAL else LinearLayoutManager.VERTICAL
        list.layoutManager = PagerLayoutManager(this, orientation)
        snapHelper.attachToRecyclerView(if (isBook) list else null)
        list.setPadding(0, if (isBook) 0 else dp(30), 0, if (isBook) 0 else dp(30))
        list.adapter = if (reflowMode) reflowAdapter else imageAdapter
        lockPaging()
    }

    /** Com zoom no modo original (formato horizontal), o dedo move a página ampliada em vez de virar. */
    private fun lockPaging() {
        (list.layoutManager as? PagerLayoutManager)?.userScrollEnabled =
            !(isBook && !reflowMode && zoom > 1f)
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
        controller.isAppearanceLightStatusBars = !night
        controller.isAppearanceLightNavigationBars = !night
        reflowAdapter.notifyDataSetChanged()
        imageAdapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------------ páginas

    /** Página que está na tela. */
    private fun currentPage(): Int {
        if (isBook) {
            val sv = snapHelper.findSnapView(lm())
            val sp = if (sv != null) list.getChildAdapterPosition(sv) else RecyclerView.NO_POSITION
            return if (sp != RecyclerView.NO_POSITION) sp else lm().findFirstVisibleItemPosition().coerceAtLeast(0)
        }
        val v = list.findChildViewUnder(list.width / 2f, list.height * 0.3f)
        val p = if (v != null) list.getChildAdapterPosition(v) else RecyclerView.NO_POSITION
        return if (p != RecyclerView.NO_POSITION) p else lm().findFirstVisibleItemPosition().coerceAtLeast(0)
    }

    private fun updatePageIndicator() {
        val n = list.adapter?.itemCount ?: 0
        if (n == 0 || !barVisible) {
            pageIndicator.visibility = View.GONE
            return
        }
        val p = (currentPage() + 1).coerceIn(1, n)
        pageIndicator.text = "Página $p de $n"
        pageIndicator.visibility = View.VISIBLE
    }

    private fun restorePending() {
        val p = pendingPage
        if (p < 0) return
        val count = list.adapter?.itemCount ?: 0
        if (count > p) {
            pendingPage = -1
            lm().scrollToPositionWithOffset(p, 0)
        }
    }

    /** Vira a página no formato horizontal (usado com zoom no modo original). */
    private fun turnPage(delta: Int) {
        val count = list.adapter?.itemCount ?: 0
        if (count == 0) return
        val cur = currentPage()
        val t = (cur + delta).coerceIn(0, count - 1)
        if (t == cur) return
        lm().scrollToPositionWithOffset(t, 0)
        hScroll.scrollTo(if (delta > 0) 0 else listWidth, 0)
    }

    private fun savePage() {
        if (!rememberPage || currentUri == null || pendingPage >= 0) return
        if ((list.adapter?.itemCount ?: 0) == 0) return
        getSharedPreferences("leitor", MODE_PRIVATE).edit().putInt(currentKey, currentPage()).apply()
    }

    private fun docKey(uri: Uri): String {
        var name: String? = null
        var size = -1L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {
        }
        return "pg_" + (name ?: uri.toString()) + "|" + size
    }

    // ------------------------------------------------------------------ links

    private fun openTarget(id: Int, targets: List<LinkTarget>) {
        lastLinkTap = SystemClock.uptimeMillis()
        val t = targets.getOrNull(id) ?: return
        val uri = t.uri
        if (uri != null) openUrl(uri) else goToPage(t.page)
    }

    private fun goToPage(n: Int) {
        val count = list.adapter?.itemCount ?: 0
        if (n < 0 || n >= count) {
            Toast.makeText(this, "Essa página ainda não foi carregada.", Toast.LENGTH_SHORT).show()
            return
        }
        backStack.add(currentPage())
        lm().scrollToPositionWithOffset(n, 0)
        hScroll.scrollTo(0, 0)
    }

    private fun openUrl(raw: String) {
        val text = raw.trim()
        val u = Uri.parse(if (text.contains(":")) text else "http://$text")
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https" && scheme != "mailto" && scheme != "tel") {
            Toast.makeText(this, "Link não suportado.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, u))
        } catch (e: Exception) {
            Toast.makeText(this, "Nenhum aplicativo abre este link.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Janela com o texto da página, para marcar e copiar quando a seleção direta
     * não iniciar. "Copiar página" copia tudo de uma vez.
     */
    private fun showTextDialog(pageIdx: Int) {
        val pt = pages.getOrNull(pageIdx) ?: return
        val tv = TextView(this).apply {
            text = pt.text
            textSize = 17f
            typeface = Typeface.SERIF
            setLineSpacing(0f, 1.3f)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setTextIsSelectable(true)
        }
        val sv = ScrollView(this)
        sv.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("Página ${pageIdx + 1}: marque o texto")
            .setView(sv)
            .setPositiveButton("Fechar", null)
            .setNeutralButton("Copiar página") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Página ${pageIdx + 1}", pt.text))
                Toast.makeText(this, "Texto da página copiado.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ------------------------------------------------------------------ abrir PDF

    private fun open(uri: Uri) {
        savePage() // guarda a página do arquivo anterior
        currentUri = uri
        currentKey = docKey(uri)
        backStack.clear()
        pendingPage = if (rememberPage)
            getSharedPreferences("leitor", MODE_PRIVATE).getInt(currentKey, -1) else -1
        if (pagesUri != uri) {
            pages.clear()
            reflowTargets.clear()
            pagesUri = null
            pagesComplete = false
        }
        hScroll.scrollTo(0, 0)
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
            restorePending()
            pendingPage = -1
            updatePageIndicator()
            return
        }
        pages.clear()
        reflowTargets.clear()
        pagesUri = uri
        pagesComplete = false
        reflowAdapter.notifyDataSetChanged()
        status.text = "Abrindo…"

        Thread {
            try {
                contentResolver.openInputStream(uri)!!.use { input ->
                    PDDocument.load(input).use { doc ->
                        val total = doc.numberOfPages
                        val stripper = LinkStripper().apply { sortByPosition = true }
                        val targets = ArrayList<LinkTarget>()
                        var withText = 0
                        for (i in 1..total) {
                            if (token != loadToken) return@Thread
                            val before = targets.size
                            stripper.pageLinks = try {
                                extractLinks(doc, doc.getPage(i - 1), targets)
                            } catch (e: Exception) {
                                emptyList()
                            }
                            val added = ArrayList(targets.subList(before, targets.size))
                            stripper.startPage = i
                            stripper.endPage = i
                            val res = Reflow.reflow(stripper.getText(doc))
                            if (res.text.isNotBlank()) withText++
                            main.post {
                                if (token != loadToken) return@post
                                reflowTargets.addAll(added)
                                pages.add(PageText(res.text, res.spans))
                                reflowAdapter.notifyItemInserted(pages.size - 1)
                                status.text = if (i < total) "Carregando página $i de $total…"
                                else "$total páginas · refluxo"
                                restorePending()
                                if (i == total) {
                                    pagesComplete = true
                                    pendingPage = -1
                                }
                                updatePageIndicator()
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
        origLinks.clear()
        origTargets.clear()
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
                    restorePending()
                    pendingPage = -1
                    updatePageIndicator()
                }

                // depois de mostrar as páginas, lê os links (índice clicável)
                try {
                    contentResolver.openInputStream(uri)!!.use { input ->
                        PDDocument.load(input).use { doc ->
                            val targets = ArrayList<LinkTarget>()
                            val all = ArrayList<List<PageLink>>()
                            for (i in 0 until doc.numberOfPages) {
                                if (token != loadToken) return@Thread
                                all.add(
                                    try {
                                        extractLinks(doc, doc.getPage(i), targets)
                                    } catch (e: Exception) {
                                        emptyList()
                                    }
                                )
                            }
                            main.post {
                                if (token != loadToken) return@post
                                origTargets.clear()
                                origTargets.addAll(targets)
                                origLinks.clear()
                                origLinks.addAll(all)
                            }
                        }
                    }
                } catch (_: Throwable) {
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

    private inner class LinkSpan(val id: Int) : ClickableSpan() {
        override fun onClick(widget: View) {
            openTarget(id, reflowTargets)
        }

        override fun updateDrawState(ds: TextPaint) {
            ds.color = if (night) Color.parseColor("#8FB4E8") else Color.parseColor("#1F3A5F")
            ds.isUnderlineText = true
        }
    }

    private inner class ReflowAdapter : RecyclerView.Adapter<ReflowAdapter.VH>() {

        inner class VH(val root: View, val label: TextView, val body: LinkTextView) :
            RecyclerView.ViewHolder(root)

        override fun getItemViewType(position: Int): Int = if (isBook) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val book = viewType == 1
            val box = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(if (book) 40 else 14), dp(20), dp(if (book) 70 else 18))
            }
            val label = TextView(parent.context).apply {
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                alpha = 0.55f
            }
            val body = LinkTextView(parent.context).apply {
                typeface = Typeface.SERIF
                setLineSpacing(0f, 1.4f)
            }
            box.addView(label)
            box.addView(body)
            if (book) {
                // uma página por tela; se o texto for maior que a tela, rola na vertical dentro dela
                val sv = ScrollView(parent.context).apply {
                    isVerticalScrollBarEnabled = false
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                sv.addView(
                    box,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                return VH(sv, label, body)
            }
            box.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            return VH(box, label, body)
        }

        override fun getItemCount() = pages.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val fg = if (night) Color.parseColor("#D6D3CB") else Color.parseColor("#1B1B1B")
            val pt = pages[position]
            val content = pt.text.ifBlank { "(página sem texto)" }

            h.label.setTextColor(fg)
            h.label.text = "— ${position + 1} —"

            // desliga a seleção antes de trocar o texto (evita falhas ao reciclar)
            h.body.setTextIsSelectable(false)
            h.body.setTextColor(fg)
            h.body.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            if (pt.spans.isEmpty()) {
                h.body.text = content
            } else {
                val ss = SpannableString(content)
                for (s in pt.spans) {
                    if (s.start >= 0 && s.end > s.start && s.end <= content.length) {
                        ss.setSpan(LinkSpan(s.id), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                h.body.text = ss
            }
            h.body.setTextIsSelectable(true)
            h.body.onLongPressNoSelection = {
                val p = h.bindingAdapterPosition
                if (p >= 0) showTextDialog(p)
            }
        }

        /** Usado durante a pinça: muda o tamanho do texto sem recarregar a lista. */
        fun applyTextSizeToVisible() {
            for (i in 0 until list.childCount) {
                val vh = list.getChildViewHolder(list.getChildAt(i)) as? VH ?: continue
                vh.body.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            }
        }
    }

    // ------------------------------------------------------------------ adaptador: original

    private inner class ImageAdapter : RecyclerView.Adapter<ImageAdapter.ImgVH>() {

        inner class ImgVH(val root: View, val img: ImageView) : RecyclerView.ViewHolder(root) {
            @Volatile
            var boundPos = -1
        }

        override fun getItemViewType(position: Int): Int = if (isBook) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImgVH {
            val img = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.FIT_XY }
            val rootView: View
            if (viewType == 1) {
                // uma página por tela; com zoom, rola na vertical dentro dela
                val wrapper = FrameLayout(parent.context)
                wrapper.addView(img, FrameLayout.LayoutParams(dp(100), dp(100)))
                val sv = ScrollView(parent.context).apply {
                    isFillViewport = true
                    isVerticalScrollBarEnabled = false
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                sv.addView(
                    wrapper,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                rootView = sv
            } else {
                img.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(100))
                rootView = img
            }
            val holder = ImgVH(rootView, img)

            // toque sobre uma área de link (índice clicável)
            val gd = GestureDetector(img.context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val pos = holder.boundPos
                    if (pos < 0 || pos >= sizes.size || pos >= origLinks.size) return false
                    val w = img.width
                    val h = img.height
                    if (w == 0 || h == 0) return false
                    val px = e.x / w * sizes[pos][0]
                    val py = e.y / h * sizes[pos][1]
                    for (l in origLinks[pos]) {
                        if (px >= l.x0 - 4f && px <= l.x1 + 4f && py >= l.y0 - 4f && py <= l.y1 + 4f) {
                            openTarget(l.id, origTargets)
                            return true
                        }
                    }
                    return false
                }
            })
            img.isClickable = true
            img.setOnTouchListener { _, e ->
                gd.onTouchEvent(e)
                false
            }
            return holder
        }

        override fun getItemCount() = sizes.size

        override fun onBindViewHolder(h: ImgVH, position: Int) {
            val sz = sizes[position]
            val screenW = resources.displayMetrics.widthPixels
            val displayW: Int
            val displayH: Int

            if (isBook) {
                // a página inteira cabe na tela (zoom 1x); o zoom multiplica esse tamanho
                val viewH = if (list.height > 0) list.height else resources.displayMetrics.heightPixels
                val fit = minOf(screenW.toFloat() / sz[0], viewH.toFloat() / sz[1])
                displayW = (sz[0] * fit * zoom).toInt().coerceAtLeast(1)
                displayH = (sz[1] * fit * zoom).toInt().coerceAtLeast(1)
                val vGrav = if (displayH < viewH) Gravity.CENTER_VERTICAL else Gravity.TOP
                h.img.layoutParams =
                    FrameLayout.LayoutParams(displayW, displayH, Gravity.CENTER_HORIZONTAL or vGrav)
            } else {
                displayW = listWidth.coerceAtLeast(1)
                displayH = (displayW.toLong() * sz[1] / sz[0]).toInt().coerceAtLeast(1)
                val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, displayH)
                lp.bottomMargin = dp(6)
                h.img.layoutParams = lp
            }

            h.img.colorFilter = if (night) invertFilter else null
            h.img.setBackgroundColor(if (night) Color.parseColor("#1A1A1A") else Color.WHITE)
            h.img.setImageBitmap(null)
            h.boundPos = position

            val r = renderer ?: return
            // limita o bitmap para não estourar a memória em telas grandes com zoom
            val renderW = minOf(displayW, RENDER_MAX_W)
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
