package com.leitor.reflow

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import android.view.HapticFeedbackConstants
import java.io.File
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
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import androidx.appcompat.widget.SwitchCompat
import android.widget.EditText
import com.tom_roush.pdfbox.text.PDFTextStripper
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
import androidx.recyclerview.widget.LinearSmoothScroller
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

    /**
     * Sem zoom lateral não há o que rolar aqui: deixa o arrasto para os lados com a lista
     * (troca de página no formato horizontal). O HorizontalScrollView puro interceptaria o gesto.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!canScrollHorizontally(1) && !canScrollHorizontally(-1)) return false
        return super.onInterceptTouchEvent(ev)
    }

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

    // O Android marca a palavra com dois toques rápidos. Aqui o segundo toque de um duplo toque
    // é engolido: a palavra só é marcada ao tocar e segurar. (O duplo toque continua chegando
    // à Activity, que usa o gesto para alternar entre Original e Refluxo.)
    private var lastTapUp = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var swallow = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dt = event.eventTime - lastTapUp
                swallow = dt in 1..android.view.ViewConfiguration.getDoubleTapTimeout().toLong() &&
                    abs(event.x - lastTapX) < 120f && abs(event.y - lastTapY) < 120f
            }
            MotionEvent.ACTION_UP -> {
                if (!swallow && event.eventTime - event.downTime < 400) {
                    lastTapUp = event.eventTime
                    lastTapX = event.x
                    lastTapY = event.y
                } else {
                    lastTapUp = 0L
                }
            }
        }
        if (swallow) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) swallow = false
            return true
        }
        detector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }
}

/** Imagem da página que também desenha a marcação do texto selecionado (modo Original). */
class PageImageView(context: Context) : ImageView(context) {
    /** Retângulos marcados, em pontos da página (origem no canto superior esquerdo). */
    var selRects: List<RectF> = emptyList()
    var pageW = 0f
    var pageH = 0f
    private val paint = Paint().apply {
        color = Color.argb(90, 50, 120, 255)
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (selRects.isEmpty() || pageW <= 0f || pageH <= 0f) return
        val sx = width / pageW
        val sy = height / pageH
        for (r in selRects) {
            canvas.drawRect(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy, paint)
        }
    }
}

class MainActivity : AppCompatActivity() {

    private companion object {
        const val ZOOM_MAX = 2.5f
        const val ZOOM_STEP = 0.25f
        // velocidade mínima (px/s) para o deslize virar a página; abaixo disso só move a página
        const val FAST_SWIPE = 1200f
        // região vertical onde o toque vale (fração da altura da tela): antes 0.25–0.75
        const val TAP_TOP = 0.08f
        const val TAP_BOTTOM = 0.92f
        const val TEXT_DEFAULT = 16f // tamanho padrão do texto no Refluxo (antes 18f)
        const val TEXT_MIN = 12f
        const val TEXT_MAX = 40f
        const val RENDER_MAX_W = 2700
        const val MAX_HITS = 300
        const val REFLOW_CACHE_VERSION = 1 // mude quando o método de extração mudar
    }

    private class PageText(val text: String, val spans: List<Reflow.Span>, val loaded: Boolean = true)
    private class ReflowData(val targets: List<LinkTarget>, val pages: List<PageText>)

    private val main = Handler(Looper.getMainLooper())

    // ---- modo refluxo (texto) ----
    private val pages = ArrayList<PageText>()
    private val reflowTargets = ArrayList<LinkTarget>()
    private var pagesUri: Uri? = null
    private var pagesComplete = false

    // ---- modo original (imagem das páginas) ----
    private val sizes = ArrayList<IntArray>() // [largura, altura] de cada página
    private class PageInfo(val links: List<PageLink>, val targets: List<LinkTarget>)

    private val pageInfo = HashMap<Int, PageInfo>() // links de cada página, lidos sob demanda
    private val infoRequested = HashSet<Int>()
    private val wordCache = HashMap<Int, List<Word>>() // palavras para a seleção
    private val bmpCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 6).toInt().coerceAtLeast(8 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val renderExec = Executors.newSingleThreadExecutor()
    private val rendererLock = Any()
    private var renderer: PdfRenderer? = null
    private var renderFd: ParcelFileDescriptor? = null

    // ---- documento PDFBox compartilhado (texto, links e seleção) ----
    private val textExec = Executors.newSingleThreadExecutor()
    private val docLock = Any()
    private var sharedDoc: PDDocument? = null
    private var sharedDocUri: Uri? = null

    // ---- seleção de texto no modo original ----
    private var selPage = -1
    private var selWords: List<Word> = emptyList()
    private var selA = -1
    private var selB = -1
    private var selDragging = false
    private var selHolder: ImageAdapter.ImgVH? = null
    private var selX = 0f
    private var selY = 0f
    private lateinit var selBar: LinearLayout

    // ---- busca no documento ----
    private class SearchHit(val page: Int, val snippet: String)

    private val pageTexts = ArrayList<String>() // texto simples de cada página (espaços colapsados)
    private val pageFolded = ArrayList<String>() // o mesmo sem acentos e em minúsculas
    private var searchDocUri: Uri? = null
    private var searchToken = 0
    private var lastQuery = ""
    private var lastAccents = true // diferenciar acentos na busca
    private var hlAccents = true
    private var lastWhole = false // só palavras inteiras
    private var hlWhole = false
    private var hlPage = -1 // página com a palavra achada destacada (modo Refluxo)
    private var hlQuery = ""
    private val wsRegex = Regex("\\s+")
    private val markRegex = Regex("\\p{Mn}+")

    // ---- estado ----
    private var currentUri: Uri? = null
    private var currentKey = ""
    private var currentName = ""
    private var pendingPage = -1
    private val backStack = ArrayList<Int>()
    private var lastLinkTap = 0L

    private var reflowMode = true
    private var night = false
    private var textSp = TEXT_DEFAULT
    private var zoom = 1f

    /** 0 = vertical contínuo, 1 = horizontal (uma página por tela, como um livro) */
    private var pageLayout = 0
    private val isBook: Boolean get() = pageLayout == 1

    private var rememberPage = false
    private var keepZoom = false // deste PDF: lembrar zoom/tamanho do texto
    private var keepMode = false // deste PDF: lembrar o modo (Original/Refluxo)
    private var keepCenter = false // deste PDF: lembrar a centralização (posição horizontal)
    private var keepLayout = false // deste PDF: lembrar o deslizamento (Vertical/Horizontal)
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
    // posição horizontal da página ao encostar o dedo e página a virar ao soltar (deslize rápido)
    private var downScrollX = 0
    private var turnAfterUp = 0
    // posição horizontal (centralização) do modo Original, guardada ao ir para o Refluxo
    private var origScrollX = 0

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
    private lateinit var settingsBtn: Button
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
                if (e.x > w * 0.25f && e.x < w * 0.75f && e.y > h * TAP_TOP && e.y < h * TAP_BOTTOM) {
                    toggleBar()
                    return true
                }
                // toque perto da borda direita (na faixa do meio): próxima página; esquerda: anterior
                if (e.y > h * TAP_TOP && e.y < h * TAP_BOTTOM) {
                    if (e.x >= w * 0.75f) {
                        pageStep(+1)
                        return true
                    }
                    if (e.x <= w * 0.25f) {
                        pageStep(-1)
                        return true
                    }
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // dois toques rápidos no centro: alterna entre Original e Refluxo
                if (currentUri == null) return false
                val w = root.width
                val h = root.height
                if (e.x > w * 0.25f && e.x < w * 0.75f && e.y > h * TAP_TOP && e.y < h * TAP_BOTTOM) {
                    toggleMode()
                    return true
                }
                return false
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                // só no formato horizontal, modo original, com zoom: o RecyclerView está travado
                // e a página vira só se o gesto começou na borda da página ampliada
                if (!isBook || e1 == null || scaling || pinchedRecently || selDragging) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (!reflowMode && zoom > 1f) {
                    // deslize RÁPIDO vira a página (mantendo a centralização);
                    // deslize lento só move a página dentro da tela
                    val fast = abs(dx) > dp(60) && abs(dx) > abs(dy) * 1.5f && abs(vx) > FAST_SWIPE
                    if (!fast) return false
                    val dir = if (dx < 0) 1 else -1
                    turnAfterUp = dir
                    turnPage(dir)
                    return true
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
                    saveDocSettings()
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
            downScrollX = hScroll.scrollX
            turnAfterUp = 0
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
        val handled = super.dispatchTouchEvent(ev)
        if (turnAfterUp != 0 && ev.actionMasked == MotionEvent.ACTION_UP) {
            // deslize rápido: desfaz o pequeno deslocamento do arrasto e cancela a inércia,
            // para a próxima página entrar na mesma posição horizontal
            turnAfterUp = 0
            val x = downScrollX
            hScroll.fling(0)
            hScroll.scrollTo(x, 0)
            hScroll.post { hScroll.scrollTo(x, 0) }
        }
        return handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)

        val prefs = getSharedPreferences("leitor", MODE_PRIVATE)
        night = prefs.getBoolean("night", false)
        // todo PDF novo começa no padrão; os "Lembrar..." são guardados por PDF (ver open)
        textSp = TEXT_DEFAULT
        zoom = 1f
        reflowMode = false
        pageLayout = 1
        rememberPage = false

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
        val searchBtn = makeBtn("🔍") { showSearchDialog() }
        (searchBtn.layoutParams as LinearLayout.LayoutParams).weight = 0.6f
        row1.addView(searchBtn)
        bar.addView(row1, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        layoutBtn = makeBtn("") { cyclePageLayout() }
        settingsBtn = makeBtn("Configuração") { showSettings() }
        row2.addView(layoutBtn)
        row2.addView(settingsBtn)
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
        // a linha de status não aparece mais no cabeçalho (o TextView existe só para o código interno)

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
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = (resources.displayMetrics.widthPixels * 0.9f).toInt()
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
        pageIndicator.setOnClickListener { showGoToDialog() } // toque no número: ir para a página

        // barra da seleção de texto (modo Original)
        selBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE222222"))
                cornerRadius = dp(24).toFloat()
            }
            visibility = View.GONE
        }
        val copyBtn = makeBtn("Copiar") { copySelection() }
        val cancelBtn = makeBtn("Cancelar") { clearSelection() }
        for (b in listOf(copyBtn, cancelBtn)) {
            b.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(4), 0, dp(4), 0) }
            b.setPadding(dp(16), 0, dp(16), 0)
            selBar.addView(b)
        }
        root.addView(
            selBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(60) }
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
            (pageIndicator.layoutParams as FrameLayout.LayoutParams).bottomMargin = sb.bottom + dp(12)
            pageIndicator.requestLayout()
            (selBar.layoutParams as FrameLayout.LayoutParams).bottomMargin = sb.bottom + dp(58)
            selBar.requestLayout()
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
        saveCenter()
    }

    override fun onDestroy() {
        super.onDestroy()
        loadToken++
        closeRenderer()
        renderExec.shutdownNow()
        textExec.execute {
            synchronized(docLock) {
                try { sharedDoc?.close() } catch (_: Throwable) {}
                sharedDoc = null
            }
        }
        textExec.shutdown()
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
            saveDocSettings()
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
        saveDocSettings()

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
        saveDocSettings()
        // no modo Original mantém a centralização (deslocamento horizontal) ajustada no zoom
        val keepX = if (!reflowMode) hScroll.scrollX else 0
        hScroll.scrollTo(0, 0)
        applyMode()
        applyTheme()
        updateLabels()
        restorePending()
        updatePageIndicator()
        if (keepX > 0) hScroll.doOnNextLayout { hScroll.scrollTo(keepX, 0) }
    }

    /** Chave de ajuste deste PDF (cada PDF tem os seus). */
    private fun dk(name: String) = "$currentKey#$name"

    /** Grava os ajustes do PDF aberto que estão marcados como "Lembrar". */
    private fun saveDocSettings() {
        if (currentUri == null || currentKey.isEmpty()) return
        val e = getSharedPreferences("leitor", MODE_PRIVATE).edit()
        if (keepZoom) {
            e.putFloat(dk("zoom"), zoom)
            e.putFloat(dk("textSp"), textSp)
        }
        if (keepMode) e.putBoolean(dk("reflow"), reflowMode)
        if (keepLayout) e.putInt(dk("layout"), pageLayout)
        e.apply()
    }

    /** Grava a centralização (posição horizontal do modo Original) se este PDF a lembra. */
    private fun saveCenter() {
        if (!keepCenter || currentUri == null || currentKey.isEmpty()) return
        val x = if (reflowMode) origScrollX else hScroll.scrollX
        getSharedPreferences("leitor", MODE_PRIVATE).edit().putInt(dk("scrollX"), x).apply()
    }

    private fun showSettings() {
        if (currentUri == null) {
            Toast.makeText(this, "Abra um PDF para configurar.", Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = getSharedPreferences("leitor", MODE_PRIVATE)
        fun row(label: String, value: Boolean, onChange: (Boolean) -> Unit): SwitchCompat =
            SwitchCompat(this).apply {
                textSize = 16f
                setPadding(0, dp(10), 0, dp(10))
                isChecked = value
                text = "$label: ${if (value) "Sim" else "Não"}"
                setOnCheckedChangeListener { _, c ->
                    text = "$label: ${if (c) "Sim" else "Não"}"
                    onChange(c)
                }
            }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        box.addView(row("Lembrar página", rememberPage) { c ->
            rememberPage = c
            prefs.edit().putBoolean(dk("remP"), c).apply()
            if (c) savePage()
        })
        box.addView(row("Lembrar zoom", keepZoom) { c ->
            keepZoom = c
            prefs.edit().putBoolean(dk("remZ"), c).apply()
            if (c) saveDocSettings()
        })
        box.addView(row("Lembrar modo", keepMode) { c ->
            keepMode = c
            prefs.edit().putBoolean(dk("remM"), c).apply()
            if (c) saveDocSettings()
        })
        box.addView(row("Lembrar deslizamento", keepLayout) { c ->
            keepLayout = c
            prefs.edit().putBoolean(dk("remL"), c).apply()
            if (c) saveDocSettings()
        })
        box.addView(row("Lembrar centralização", keepCenter) { c ->
            keepCenter = c
            prefs.edit().putBoolean(dk("remC"), c).apply()
            if (c) saveCenter()
        })
        box.addView(TextView(this).apply {
            text = "Vale só para este PDF. Com \"Sim\", ao abrir este PDF de novo ele volta como você " +
                "deixou. Com \"Não\", ele começa no padrão: primeira página, zoom x1 (e texto no " +
                "tamanho padrão), modo Original, deslizamento na horizontal e página sem deslocamento lateral. PDFs novos sempre " +
                "começam com tudo em \"Não\"."
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dp(8), 0, dp(4))
        })
        AlertDialog.Builder(this)
            .setTitle("Configuração")
            .setView(box)
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun toggleMode() {
        // guarda a página atual para continuar nela no outro modo
        if (pendingPage < 0 && (list.adapter?.itemCount ?: 0) > 0) pendingPage = currentPage()
        if (!reflowMode) origScrollX = hScroll.scrollX // saindo do Original: guarda a centralização
        reflowMode = !reflowMode
        saveDocSettings()
        hScroll.scrollTo(0, 0)
        applyMode()
        applyTheme()
        updateLabels()
        show()
        if (!reflowMode && origScrollX > 0) {
            // voltando ao Original: devolve a centralização que estava ao sair
            val x = origScrollX
            hScroll.doOnNextLayout { hScroll.scrollTo(x, 0) }
        }
    }

    /** Monta a lista conforme o modo (refluxo/original) e o formato (vertical/horizontal). */
    private fun applyMode() {
        applyWidth()
        val orientation = if (isBook) LinearLayoutManager.HORIZONTAL else LinearLayoutManager.VERTICAL
        list.layoutManager = PagerLayoutManager(this, orientation)
        list.onFlingListener = null // o PagerSnapHelper não aceita outro ouvinte já instalado
        snapHelper.attachToRecyclerView(if (isBook) list else null)
        if (!isBook && !reflowMode) {
            // vertical, modo Original: deslize rápido vira UMA página e para; lento só rola
            list.onFlingListener = object : RecyclerView.OnFlingListener() {
                override fun onFling(velocityX: Int, velocityY: Int): Boolean {
                    if (scaling || pinchedRecently || selDragging) return false
                    if (abs(velocityY) < FAST_SWIPE || abs(velocityY) < abs(velocityX)) return false
                    val count = list.adapter?.itemCount ?: 0
                    if (count == 0) return false
                    val dir = if (velocityY > 0) 1 else -1
                    val cur = currentPage()
                    val v = lm().findViewByPosition(cur)
                    val t = when {
                        dir > 0 -> cur + 1
                        // para trás: se o começo da página atual está acima da tela, volta ao começo dela
                        v != null && v.top < list.paddingTop - dp(8) -> cur
                        else -> cur - 1
                    }.coerceIn(0, count - 1)
                    val sc = object : LinearSmoothScroller(this@MainActivity) {
                        override fun getVerticalSnapPreference() = SNAP_TO_START
                    }
                    sc.targetPosition = t
                    lm().startSmoothScroll(sc)
                    return true
                }
            }
        }
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
        val line1 = "Página $p de $n"
        if (currentName.isBlank()) {
            pageIndicator.text = line1
        } else {
            // nome do arquivo em letras menores, abaixo do número da página
            val ss = SpannableString("$line1\n$currentName")
            ss.setSpan(
                android.text.style.RelativeSizeSpan(0.8f), line1.length + 1, ss.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            pageIndicator.text = ss
        }
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

    /** Toque na borda: vai para a página seguinte (+1) ou anterior (-1), em qualquer modo e formato. */
    private fun pageStep(delta: Int) {
        val count = list.adapter?.itemCount ?: 0
        if (count == 0) return
        val cur = currentPage()
        val t = (cur + delta).coerceIn(0, count - 1)
        if (t == cur) return
        lm().scrollToPositionWithOffset(t, 0)
        // modo original: mantém a centralização (deslocamento horizontal) ajustada no zoom;
        // só no refluxo volta ao início
        if (isBook && reflowMode) hScroll.scrollTo(0, 0)
    }

    /** Vira a página no formato horizontal (usado com zoom no modo original). */
    private fun turnPage(delta: Int) {
        val count = list.adapter?.itemCount ?: 0
        if (count == 0) return
        val cur = currentPage()
        val t = (cur + delta).coerceIn(0, count - 1)
        if (t == cur) return
        lm().scrollToPositionWithOffset(t, 0)
        // mantém a centralização (deslocamento horizontal) ajustada ao trocar de página
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
        // nome para exibir, sem a extensão .pdf
        currentName = (name ?: uri.lastPathSegment ?: "").replace(Regex("(?i)\\.pdf$"), "")
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
        saveCenter() // ... e a centralização dele
        if (currentUri != uri) {
            wordCache.clear()
            pageInfo.clear()
            infoRequested.clear()
            bmpCache.evictAll()
            pageTexts.clear()
            pageFolded.clear()
            searchDocUri = null
            searchToken++
            hlPage = -1
        }
        clearSelection()
        currentUri = uri
        currentKey = docKey(uri)
        backStack.clear()
        // ajustes deste PDF: cada "Lembrar" começa em Não e só vale se foi marcado neste mesmo PDF
        val dp0 = getSharedPreferences("leitor", MODE_PRIVATE)
        rememberPage = dp0.getBoolean(dk("remP"), false)
        keepZoom = dp0.getBoolean(dk("remZ"), false)
        keepMode = dp0.getBoolean(dk("remM"), false)
        keepLayout = dp0.getBoolean(dk("remL"), false)
        keepCenter = dp0.getBoolean(dk("remC"), false)
        pendingPage = if (rememberPage) dp0.getInt(currentKey, -1) else -1
        if (pagesUri != uri) {
            pages.clear()
            reflowTargets.clear()
            pagesUri = null
            pagesComplete = false
        }
        hScroll.scrollTo(0, 0)
        origScrollX = if (keepCenter) dp0.getInt(dk("scrollX"), 0).coerceAtLeast(0) else 0
        // aplica os ajustes lembrados deste PDF (ou o padrão)
        val newReflow = keepMode && dp0.getBoolean(dk("reflow"), false)
        val newLayout = if (keepLayout) dp0.getInt(dk("layout"), 1).coerceIn(0, 1) else 1
        val newZoom = if (keepZoom) dp0.getFloat(dk("zoom"), 1f).coerceIn(1f, ZOOM_MAX) else 1f
        val newText = if (keepZoom) dp0.getFloat(dk("textSp"), TEXT_DEFAULT).coerceIn(TEXT_MIN, TEXT_MAX) else TEXT_DEFAULT
        val changed = newReflow != reflowMode || newLayout != pageLayout ||
            newZoom != zoom || newText != textSp
        reflowMode = newReflow
        pageLayout = newLayout
        zoom = newZoom
        textSp = newText
        if (changed) {
            applyMode()
            applyTheme()
            updateLabels()
        }
        show()
        if (!reflowMode && origScrollX > 0) {
            // volta à centralização lembrada deste PDF
            val x = origScrollX
            if (changed) hScroll.doOnNextLayout { hScroll.scrollTo(x, 0) }
            else hScroll.post { hScroll.scrollTo(x, 0) }
        }
    }

    private fun show() {
        val uri = currentUri ?: return
        val token = ++loadToken
        clearSelection()
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
        val cacheFile = reflowCacheFile(currentKey)
        val startHint = pendingPage // página em que você está: é extraída primeiro

        Thread {
            try {
                // 1) texto já extraído numa abertura anterior: abre na hora
                val cached = readReflowCache(cacheFile)
                if (cached != null) {
                    main.post {
                        if (token != loadToken) return@post
                        reflowTargets.addAll(cached.targets)
                        pages.addAll(cached.pages)
                        reflowAdapter.notifyDataSetChanged()
                        status.text = "${pages.size} páginas · refluxo"
                        pagesComplete = true
                        restorePending()
                        pendingPage = -1
                        updatePageIndicator()
                    }
                    return@Thread
                }

                // 2) extração: começa pela página atual e depois completa o resto
                val stripper = LinkStripper().apply { sortByPosition = true }
                val targets = ArrayList<LinkTarget>()
                val total = synchronized(docLock) { ensureDoc(uri).numberOfPages }
                val start = if (startHint in 0 until total) startHint else 0
                main.post {
                    if (token != loadToken) return@post
                    for (k in 0 until total) pages.add(PageText("", emptyList(), false))
                    reflowAdapter.notifyDataSetChanged()
                    status.text = "Carregando… 0 de $total páginas"
                    restorePending()
                    updatePageIndicator()
                }
                val local = arrayOfNulls<PageText>(total)
                var withText = 0
                var done = 0
                for (i in (start until total) + (0 until start)) {
                    if (token != loadToken) return@Thread
                    val before = targets.size
                    // o documento é compartilhado: a trava é solta a cada página
                    val raw = synchronized(docLock) {
                        val doc = ensureDoc(uri)
                        val page = doc.getPage(i)
                        stripper.pageLinks = try {
                            extractLinks(doc, page, targets)
                        } catch (e: Exception) {
                            emptyList()
                        }
                        stripper.extractPageText(doc, i + 1, pageWidth(page))
                    }
                    val added = ArrayList(targets.subList(before, targets.size))
                    val res = Reflow.reflow(raw)
                    if (res.text.isNotBlank()) withText++
                    val pt = PageText(res.text, res.spans)
                    local[i] = pt
                    done++
                    val d = done
                    main.post {
                        if (token != loadToken) return@post
                        reflowTargets.addAll(added)
                        if (i < pages.size) {
                            pages[i] = pt
                            reflowAdapter.notifyItemChanged(i)
                        }
                        status.text = if (d < total) "Carregando… $d de $total páginas"
                        else "$total páginas · refluxo"
                        if (d == total) {
                            pagesComplete = true
                            pendingPage = -1
                        }
                    }
                }
                if (local.all { it != null }) {
                    writeReflowCache(cacheFile, targets, local.map { it!! })
                }
                if (withText == 0) {
                    main.post {
                        if (token != loadToken) return@post
                        status.text =
                            "Este PDF não tem texto (é só imagem). Use o modo Original ou faça o OCR."
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (token != loadToken) return@post
                    status.text = "Não foi possível abrir o PDF."
                    Toast.makeText(this@MainActivity, "Não foi possível abrir o PDF.", Toast.LENGTH_LONG).show()
                    Toast.makeText(this, e.message ?: "Erro", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ cache do refluxo

    private fun reflowCacheFile(key: String): File {
        val dir = File(cacheDir, "reflow")
        dir.mkdirs()
        val name = java.security.MessageDigest.getInstance("SHA-1")
            .digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$name.bin")
    }

    private fun writeStr(o: java.io.DataOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        o.writeInt(b.size)
        o.write(b)
    }

    private fun readStr(i: java.io.DataInputStream): String {
        val b = ByteArray(i.readInt())
        i.readFully(b)
        return String(b, Charsets.UTF_8)
    }

    private fun writeReflowCache(f: File, targets: List<LinkTarget>, pgs: List<PageText>) {
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(tmp))).use { o ->
                o.writeInt(REFLOW_CACHE_VERSION)
                o.writeInt(targets.size)
                for (t in targets) {
                    o.writeInt(t.page)
                    val u = t.uri
                    o.writeBoolean(u != null)
                    if (u != null) writeStr(o, u)
                }
                o.writeInt(pgs.size)
                for (p in pgs) {
                    writeStr(o, p.text)
                    o.writeInt(p.spans.size)
                    for (sp in p.spans) {
                        o.writeInt(sp.start)
                        o.writeInt(sp.end)
                        o.writeInt(sp.id)
                    }
                }
            }
            f.delete()
            tmp.renameTo(f)
            // mantém só os 25 arquivos mais recentes
            f.parentFile?.listFiles { x -> x.name.endsWith(".bin") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(25)
                ?.forEach { it.delete() }
        } catch (_: Throwable) {
        }
    }

    private fun readReflowCache(f: File): ReflowData? {
        if (!f.exists()) return null
        return try {
            java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(f))).use { i ->
                if (i.readInt() != REFLOW_CACHE_VERSION) return@use null
                val nt = i.readInt()
                val targets = ArrayList<LinkTarget>(nt)
                for (k in 0 until nt) {
                    val page = i.readInt()
                    val uri = if (i.readBoolean()) readStr(i) else null
                    targets.add(LinkTarget(page, uri))
                }
                val np = i.readInt()
                val pgs = ArrayList<PageText>(np)
                for (k in 0 until np) {
                    val text = readStr(i)
                    val ns = i.readInt()
                    val spans = ArrayList<Reflow.Span>(ns)
                    for (m in 0 until ns) {
                        val st = i.readInt()
                        val en = i.readInt()
                        val id = i.readInt()
                        spans.add(Reflow.Span(st, en, id))
                    }
                    pgs.add(PageText(text, spans))
                }
                ReflowData(targets, pgs)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun showOriginal(uri: Uri, token: Int) {
        sizes.clear()
        imageAdapter.notifyDataSetChanged()
        infoRequested.clear()
        status.text = "Abrindo…"

        Thread {
            var fd: ParcelFileDescriptor? = null
            var pr: PdfRenderer? = null
            try {
                val f = contentResolver.openFileDescriptor(uri, "r")!!
                fd = f
                val r = PdfRenderer(f)
                pr = r
                val count = r.pageCount
                if (count == 0) throw IllegalStateException("PDF sem páginas")

                // só a primeira página é medida agora: as demais começam com o mesmo tamanho
                // e são corrigidas em segundo plano (assim a leitura começa na hora)
                val p0 = r.openPage(0)
                val first = intArrayOf(p0.width, p0.height)
                p0.close()

                main.post {
                    if (token != loadToken) {
                        synchronized(rendererLock) {
                            try { r.close() } catch (_: Throwable) {}
                        }
                        try { f.close() } catch (_: Throwable) {}
                        return@post
                    }
                    synchronized(rendererLock) {
                        renderer = r
                        renderFd = f
                    }
                    for (i in 0 until count) sizes.add(first)
                    imageAdapter.notifyDataSetChanged()
                    status.text = "$count páginas · original"
                    restorePending()
                    pendingPage = -1
                    updatePageIndicator()
                }

                try {
                    var diffs = ArrayList<IntArray>()
                    for (i in 1 until count) {
                        if (token != loadToken) return@Thread
                        var w = 0
                        var h = 0
                        synchronized(rendererLock) {
                            val p = r.openPage(i)
                            w = p.width
                            h = p.height
                            p.close()
                        }
                        if (w != first[0] || h != first[1]) diffs.add(intArrayOf(i, w, h))
                        if (diffs.isNotEmpty() && (diffs.size >= 30 || i == count - 1)) {
                            val batch = diffs
                            diffs = ArrayList()
                            main.post {
                                if (token != loadToken) return@post
                                for (d in batch) {
                                    if (d[0] < sizes.size) {
                                        sizes[d[0]] = intArrayOf(d[1], d[2])
                                        imageAdapter.notifyItemChanged(d[0])
                                    }
                                }
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
                    Toast.makeText(this@MainActivity, "Não foi possível abrir o PDF no modo Original.", Toast.LENGTH_LONG).show()
                    Toast.makeText(this, e.message ?: "Erro", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ documento compartilhado

    /**
     * Chame com [docLock]. Copia o PDF para o cache (leitura por arquivo é bem mais rápida
     * que ler tudo para a memória) e abre o documento uma única vez para texto, links e seleção.
     */
    private fun ensureDoc(uri: Uri): PDDocument {
        val d = sharedDoc
        if (d != null && sharedDocUri == uri) return d
        try { sharedDoc?.close() } catch (_: Throwable) {}
        sharedDoc = null
        sharedDocUri = null
        val f = File(cacheDir, "atual.pdf")
        contentResolver.openInputStream(uri)!!.use { input ->
            f.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
        }
        val doc = PDDocument.load(f)
        sharedDoc = doc
        sharedDocUri = uri
        return doc
    }

    /** Lê os links de uma página do modo original, só quando ela aparece. */
    private fun requestLinks(pos: Int) {
        if (pageInfo.containsKey(pos) || !infoRequested.add(pos)) return
        val uri = currentUri ?: return
        val token = loadToken
        textExec.execute {
            val info = try {
                synchronized(docLock) {
                    val d = ensureDoc(uri)
                    val t = ArrayList<LinkTarget>()
                    val l = extractLinks(d, d.getPage(pos), t)
                    PageInfo(l, t)
                }
            } catch (_: Throwable) {
                null
            }
            main.post {
                if (token == loadToken) pageInfo[pos] = info ?: PageInfo(emptyList(), emptyList())
            }
        }
    }

    // ------------------------------------------------------------------ ir para a página

    private fun showGoToDialog() {
        val n = list.adapter?.itemCount ?: 0
        if (n == 0) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 a $n"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Ir para a página")
            .setMessage("Digite o número da página (1 a $n)")
            .setView(box)
            .setPositiveButton("OK") { _, _ -> goToTyped(input.text.toString(), n) }
            .setNegativeButton("Cancelar", null)
            .create()
        input.setOnEditorActionListener { _, _, _ ->
            dlg.dismiss()
            goToTyped(input.text.toString(), n)
            true
        }
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        input.requestFocus()
    }

    private fun goToTyped(text: String, n: Int) {
        val p = text.trim().toIntOrNull()
        if (p == null || p < 1 || p > n) {
            Toast.makeText(this, "Digite um número de 1 a $n.", Toast.LENGTH_SHORT).show()
            return
        }
        goToPage(p - 1)
    }

    // ------------------------------------------------------------------ busca

    /** Sem acentos e em minúsculas, para a busca ignorar maiúsculas e acentos. */
    private fun fold(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(markRegex, "").lowercase()

    /** A ocorrência em [at] não está dentro de uma palavra maior (antes e depois não há letra/dígito). */
    private fun isWholeAt(text: String, at: Int, len: Int): Boolean {
        val before = if (at > 0) text[at - 1] else ' '
        val after = if (at + len < text.length) text[at + len] else ' '
        return !before.isLetterOrDigit() && !after.isLetterOrDigit()
    }

    /** Só minúsculas (mantém os acentos), com a forma composta dos caracteres. */
    private fun exact(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC).lowercase()

    private fun showSearchDialog() {
        if (currentUri == null) {
            Toast.makeText(this, "Abra um PDF primeiro.", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            setSingleLine()
            hint = "Palavra ou frase"
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(lastQuery)
            setSelection(text.length)
        }
        val accentBox = CheckBox(this).apply {
            text = "Diferenciar acentos (ação ≠ acao)"
            isChecked = lastAccents
        }
        val wholeBox = CheckBox(this).apply {
            text = "Palavra inteira (ação ≠ organização)"
            isChecked = lastWhole
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
            addView(accentBox)
            addView(wholeBox)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Buscar no documento")
            .setView(box)
            .setPositiveButton("Buscar") { _, _ ->
                runSearch(input.text.toString(), accentBox.isChecked, wholeBox.isChecked)
            }
            .setNegativeButton("Fechar", null)
            .create()
        input.setOnEditorActionListener { _, _, _ ->
            dlg.dismiss()
            runSearch(input.text.toString(), accentBox.isChecked, wholeBox.isChecked)
            true
        }
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        input.requestFocus()
    }

    /** Procura em todas as páginas (o texto de cada página fica em cache para as próximas buscas). */
    private fun runSearch(q: String, accents: Boolean, whole: Boolean) {
        val uri = currentUri ?: return
        val query = q.trim().replace(wsRegex, " ")
        if (query.isEmpty()) return
        lastQuery = query
        lastAccents = accents
        lastWhole = whole
        val fq = if (accents) exact(query) else fold(query)
        if (searchDocUri != uri) {
            pageTexts.clear()
            pageFolded.clear()
            searchDocUri = uri
        }
        val my = ++searchToken
        val snapshot = ArrayList(pageTexts)
        val snapshotF = ArrayList(pageFolded)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Buscando “$query”")
            .setMessage("Preparando…")
            .setNegativeButton("Cancelar") { _, _ -> searchToken++ }
            .setCancelable(false)
            .create()
        dlg.show()

        Thread {
            try {
                val stripper = PDFTextStripper()
                val total = synchronized(docLock) { ensureDoc(uri).numberOfPages }
                val hits = ArrayList<SearchHit>()
                var batchStart = snapshot.size
                val bt = ArrayList<String>()
                val bf = ArrayList<String>()

                fun flush() {
                    if (bt.isEmpty()) return
                    val start = batchStart
                    val ct = ArrayList(bt)
                    val cf = ArrayList(bf)
                    batchStart += bt.size
                    bt.clear()
                    bf.clear()
                    main.post {
                        if (pageTexts.size == start && searchDocUri == uri) {
                            pageTexts.addAll(ct)
                            pageFolded.addAll(cf)
                        }
                    }
                }

                var truncated = false
                for (i in 0 until total) {
                    if (my != searchToken) {
                        flush()
                        return@Thread
                    }
                    val txt: String
                    val plain: String // sem acentos (é o que fica em cache)
                    if (i < snapshot.size) {
                        txt = snapshot[i]
                        plain = snapshotF[i]
                    } else {
                        txt = synchronized(docLock) {
                            val d = ensureDoc(uri)
                            stripper.setStartPage(i + 1)
                            stripper.setEndPage(i + 1)
                            stripper.getText(d)
                        }.replace(wsRegex, " ").trim()
                        plain = fold(txt)
                        bt.add(txt)
                        bf.add(plain)
                        if (bt.size >= 25) flush()
                    }
                    val fl = if (accents) exact(txt) else plain
                    var from = 0
                    while (true) {
                        val at = fl.indexOf(fq, from)
                        if (at < 0) break
                        if (whole && !isWholeAt(fl, at, fq.length)) {
                            from = at + 1
                            continue
                        }
                        val s = (at - 40).coerceAtLeast(0)
                        val e = (at + fq.length + 60).coerceAtMost(txt.length)
                        val snip = if (s < e) {
                            (if (s > 0) "…" else "") + txt.substring(s, e) + (if (e < txt.length) "…" else "")
                        } else {
                            query
                        }
                        hits.add(SearchHit(i, snip))
                        from = at + fq.length
                        if (hits.size >= MAX_HITS) {
                            truncated = true
                            break
                        }
                    }
                    if (i % 10 == 0) {
                        val msg = "Página ${i + 1} de $total · ${hits.size} resultado(s)"
                        main.post { if (my == searchToken) dlg.setMessage(msg) }
                    }
                    if (truncated) break
                }
                flush()
                val truncatedFinal = truncated
                main.post {
                    if (my != searchToken) return@post
                    dlg.dismiss()
                    showResults(query, accents, whole, hits, truncatedFinal)
                }
            } catch (e: Throwable) {
                main.post {
                    dlg.dismiss()
                    Toast.makeText(this, "Não foi possível buscar neste PDF.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showResults(
        query: String, accents: Boolean, whole: Boolean, hits: List<SearchHit>, truncated: Boolean
    ) {
        if (isFinishing) return
        if (hits.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Buscar")
                .setMessage("Nenhum resultado para “$query”.\n\nPDFs só de imagem não têm texto para buscar.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val items = hits.map<SearchHit, CharSequence> { "Pág. ${it.page + 1}: ${it.snippet}" }.toTypedArray()
        val title = if (truncated) "Primeiros $MAX_HITS resultados" else "${hits.size} resultado(s) para “$query”"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items) { _, which -> openSearchResult(hits[which].page, query, accents, whole) }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun openSearchResult(page: Int, query: String, accents: Boolean, whole: Boolean) {
        goToPage(page)
        if (reflowMode) {
            hlPage = page
            hlQuery = query
            hlAccents = accents
            hlWhole = whole
            if (page < pages.size) reflowAdapter.notifyItemChanged(page)
        } else {
            highlightInOriginal(page, query, accents, whole)
        }
    }

    /** Trecho do texto do refluxo que corresponde à busca (ignora acentos e maiúsculas). */
    private fun hlRange(content: String): IntRange? {
        if (hlQuery.isBlank()) return null
        val key: (String) -> String = if (hlAccents) { t: String -> exact(t) } else { t: String -> fold(t) }
        val toks = key(hlQuery).trim().split(wsRegex).filter { it.isNotEmpty() }
        if (toks.isEmpty()) return null
        val f = key(content)
        val fc = if (f.length == content.length) f else content.lowercase()
        var pat = toks.joinToString("\\s+") { Regex.escape(it) }
        if (hlWhole) pat = "(?<![\\p{L}\\p{N}])$pat(?![\\p{L}\\p{N}])"
        val m = Regex(pat).find(fc) ?: return null
        return m.range
    }

    /** No modo Original, marca a primeira ocorrência na página (some ao tocar na tela). */
    private fun highlightInOriginal(pos: Int, query: String, accents: Boolean, whole: Boolean) {
        val key: (String) -> String = if (accents) { t: String -> exact(t) } else { t: String -> fold(t) }
        wordsFor(pos) { words ->
            val fq = key(query).trim()
            if (fq.isEmpty() || words.isEmpty()) return@wordsFor
            val sb = StringBuilder()
            val starts = IntArray(words.size)
            for (i in words.indices) {
                if (i > 0) sb.append(' ')
                starts[i] = sb.length
                sb.append(key(words[i].text))
            }
            var at = sb.indexOf(fq)
            val all = sb.toString()
            while (at >= 0 && whole && !isWholeAt(all, at, fq.length)) at = all.indexOf(fq, at + 1)
            if (at < 0) return@wordsFor
            var a = 0
            var b = 0
            for (i in words.indices) {
                if (starts[i] <= at) a = i
                if (starts[i] <= at + fq.length - 1) b = i
            }
            clearSelection()
            selPage = pos
            selWords = words
            selA = a
            selB = b
            imageAdapter.notifyItemChanged(pos)
        }
    }

    // ------------------------------------------------------------------ seleção (modo original)

    /** Palavras da página, lidas em segundo plano na primeira vez. */
    private fun wordsFor(pos: Int, cb: (List<Word>) -> Unit) {
        val cached = wordCache[pos]
        if (cached != null) {
            cb(cached)
            return
        }
        val uri = currentUri ?: return
        val token = loadToken
        textExec.execute {
            val words = try {
                synchronized(docLock) {
                    LinkStripper().extractWords(ensureDoc(uri), pos + 1)
                }
            } catch (_: Throwable) {
                emptyList<Word>()
            }
            main.post {
                if (token != loadToken) return@post
                wordCache[pos] = words
                cb(words)
            }
        }
    }

    private fun startSelection(h: ImageAdapter.ImgVH, x: Float, y: Float) {
        val pos = h.boundPos
        if (pos < 0 || pos >= sizes.size) return
        clearSelection()
        selDragging = true
        selHolder = h
        selPage = pos
        selX = x
        selY = y
        h.img.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        h.root.parent?.requestDisallowInterceptTouchEvent(true)
        wordsFor(pos) { words ->
            if (selPage != pos) return@wordsFor
            if (words.isEmpty()) {
                Toast.makeText(
                    this, "Esta página não tem texto para marcar (parece ser imagem).",
                    Toast.LENGTH_LONG
                ).show()
                clearSelection()
                return@wordsFor
            }
            selWords = words
            val i = nearestWord(h, selX, selY, 50f)
            if (i < 0) {
                clearSelection()
                return@wordsFor
            }
            selA = i
            selB = i
            applySelection(h)
            if (!selDragging) selBar.visibility = View.VISIBLE
        }
    }

    private fun dragSelection(h: ImageAdapter.ImgVH, x: Float, y: Float) {
        selX = x
        selY = y
        if (selA < 0) return
        val i = nearestWord(h, x, y, 1e6f)
        if (i >= 0 && i != selB) {
            selB = i
            applySelection(h)
        }
    }

    private fun endDrag() {
        selDragging = false
        if (selA >= 0) selBar.visibility = View.VISIBLE
    }

    /** Palavra mais próxima do ponto tocado (em pixels da imagem); -1 se estiver longe demais. */
    private fun nearestWord(h: ImageAdapter.ImgVH, x: Float, y: Float, maxDist: Float): Int {
        val pos = h.boundPos
        if (pos != selPage || pos < 0 || pos >= sizes.size) return -1
        val w = h.img.width
        val hh = h.img.height
        if (w == 0 || hh == 0) return -1
        val px = x / w * sizes[pos][0]
        val py = y / hh * sizes[pos][1]
        var best = -1
        var bd = Float.MAX_VALUE
        for (i in selWords.indices) {
            val wd = selWords[i]
            val dx = maxOf(wd.x0 - px, 0f, px - wd.x1)
            val dy = maxOf(wd.y0 - py, 0f, py - wd.y1)
            val d = dx * dx + dy * dy * 4f // dá preferência a palavras na mesma linha
            if (d < bd) {
                bd = d
                best = i
            }
        }
        return if (best >= 0 && bd <= maxDist * maxDist) best else -1
    }

    private fun selectionRects(): List<RectF> {
        if (selA < 0 || selWords.isEmpty()) return emptyList()
        val lo = minOf(selA, selB).coerceIn(0, selWords.size - 1)
        val hi = maxOf(selA, selB).coerceIn(0, selWords.size - 1)
        val out = ArrayList<RectF>()
        var cur: RectF? = null
        for (i in lo..hi) {
            val w = selWords[i]
            val c = cur
            if (c != null && abs(c.top - w.y0) < 3f && abs(c.bottom - w.y1) < 3f &&
                w.x0 >= c.left && w.x0 - c.right < 10f
            ) {
                c.right = maxOf(c.right, w.x1)
            } else {
                val nr = RectF(w.x0, w.y0, w.x1, w.y1)
                out.add(nr)
                cur = nr
            }
        }
        return out
    }

    private fun applySelection(h: ImageAdapter.ImgVH) {
        val pos = h.boundPos
        if (pos < 0 || pos >= sizes.size) return
        h.img.pageW = sizes[pos][0].toFloat()
        h.img.pageH = sizes[pos][1].toFloat()
        h.img.selRects = if (pos == selPage) selectionRects() else emptyList()
        h.img.invalidate()
    }

    private fun selectionText(): String {
        if (selA < 0 || selWords.isEmpty()) return ""
        val lo = minOf(selA, selB).coerceIn(0, selWords.size - 1)
        val hi = maxOf(selA, selB).coerceIn(0, selWords.size - 1)
        val sb = StringBuilder()
        var prev: Word? = null
        for (i in lo..hi) {
            val w = selWords[i]
            val p = prev
            if (p != null) {
                val newLine = abs(w.y1 - p.y1) > 3f
                when {
                    !newLine -> sb.append(' ')
                    p.text.length > 1 && p.text.endsWith("-") &&
                        w.text.firstOrNull()?.isLowerCase() == true -> sb.setLength(sb.length - 1)
                    w.y0 - p.y1 > (p.y1 - p.y0) * 0.45f && w.y0 > p.y1 -> sb.append("\n\n")
                    else -> sb.append(' ')
                }
            }
            sb.append(w.text)
            prev = w
        }
        return sb.toString()
    }

    private fun copySelection() {
        val t = selectionText()
        if (t.isNotBlank()) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Texto", t))
            Toast.makeText(this, "Texto copiado.", Toast.LENGTH_SHORT).show()
        }
        clearSelection()
    }

    private fun clearSelection() {
        val h = selHolder
        selPage = -1
        selA = -1
        selB = -1
        selWords = emptyList()
        selDragging = false
        selHolder = null
        if (h != null) {
            h.img.selRects = emptyList()
            h.img.invalidate()
        }
        if (::selBar.isInitialized) selBar.visibility = View.GONE
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
            val content = if (!pt.loaded) "Carregando página…" else pt.text.ifBlank { "(página sem texto)" }

            h.label.setTextColor(fg)
            h.label.text = "— ${position + 1} —"

            // desliga a seleção antes de trocar o texto (evita falhas ao reciclar)
            h.body.setTextIsSelectable(false)
            h.body.setTextColor(fg)
            h.body.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            val hl = if (position == hlPage) hlRange(content) else null
            if (pt.spans.isEmpty() && hl == null) {
                h.body.text = content
            } else {
                val ss = SpannableString(content)
                for (s in pt.spans) {
                    if (s.start >= 0 && s.end > s.start && s.end <= content.length) {
                        ss.setSpan(LinkSpan(s.id), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                if (hl != null) {
                    ss.setSpan(
                        BackgroundColorSpan(Color.argb(200, 255, 214, 0)),
                        hl.first, hl.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    ss.setSpan(
                        ForegroundColorSpan(Color.BLACK),
                        hl.first, hl.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
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

        inner class ImgVH(val root: View, val img: PageImageView) : RecyclerView.ViewHolder(root) {
            @Volatile
            var boundPos = -1
        }

        override fun getItemViewType(position: Int): Int = if (isBook) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImgVH {
            val img = PageImageView(parent.context).apply { scaleType = ImageView.ScaleType.FIT_XY }
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
                override fun onLongPress(e: MotionEvent) {
                    startSelection(holder, e.x, e.y)
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (selA >= 0) clearSelection() // toque fora tira a marcação
                    val pos = holder.boundPos
                    if (pos < 0 || pos >= sizes.size) return false
                    val info = pageInfo[pos] ?: return false
                    val w = img.width
                    val h = img.height
                    if (w == 0 || h == 0) return false
                    val px = e.x / w * sizes[pos][0]
                    val py = e.y / h * sizes[pos][1]
                    for (l in info.links) {
                        if (px >= l.x0 - 4f && px <= l.x1 + 4f && py >= l.y0 - 4f && py <= l.y1 + 4f) {
                            openTarget(l.id, info.targets)
                            return true
                        }
                    }
                    return false
                }
            })
            img.isClickable = true
            img.setOnTouchListener { _, e ->
                gd.onTouchEvent(e)
                if (selDragging && selHolder === holder) {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_MOVE -> dragSelection(holder, e.x, e.y)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
                    }
                    true
                } else {
                    false
                }
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

            // marcação de texto e links desta página
            h.img.pageW = sz[0].toFloat()
            h.img.pageH = sz[1].toFloat()
            if (position == selPage) {
                selHolder = h
                h.img.selRects = selectionRects()
            } else {
                h.img.selRects = emptyList()
            }
            requestLinks(position)

            val r = renderer ?: return
            // limita o bitmap para não estourar a memória em telas grandes com zoom
            val renderW = minOf(displayW, RENDER_MAX_W)
            val renderH = (renderW.toLong() * sz[1] / sz[0]).toInt().coerceAtLeast(1)
            val key = "$position|$renderW"
            val hit = bmpCache.get(key)
            if (hit != null) {
                h.img.setImageBitmap(hit)
                return
            }

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
                bmpCache.put(key, done)
                main.post { if (h.boundPos == position) h.img.setImageBitmap(done) }
            }
        }

        override fun onViewRecycled(h: ImgVH) {
            h.boundPos = -1
            h.img.selRects = emptyList()
            h.img.setImageBitmap(null)
        }
    }
}
