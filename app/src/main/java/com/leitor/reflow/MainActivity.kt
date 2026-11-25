package com.leitor.reflow

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
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
/**
 * Página do Refluxo (formato horizontal): o texto selecionável pede foco ao receber o conteúdo e o
 * ScrollView rolava sozinho até ele, deixando a página na primeira entrada sem as linhas do topo.
 * Aqui a rolagem só acontece pelo dedo.
 */
class NoAutoScrollView(context: Context) : ScrollView(context) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int = 0
}

class FixedWidthScroll(context: Context) : HorizontalScrollView(context) {

    /**
     * Sem zoom lateral não há o que rolar aqui: deixa o arrasto para os lados com a lista
     * (troca de página no formato horizontal). O HorizontalScrollView puro interceptaria o gesto.
     */
    /** No formato vertical, o movimento só vira rolagem lateral se for claramente horizontal. */
    var verticalBias = false
    private var downX = 0f
    private var downY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!canScrollHorizontally(1) && !canScrollHorizontally(-1)) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
            }
            MotionEvent.ACTION_MOVE -> if (verticalBias) {
                // um pequeno desvio para o lado não rouba o gesto de deslizar para cima/baixo
                val dx = kotlin.math.abs(ev.x - downX)
                val dy = kotlin.math.abs(ev.y - downY)
                if (dx < 2f * dy) return false
            }
        }
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

    /** Trava de troca por deslizamento (quadrinhos): o arrasto do dedo não sai da página atual. */
    var swipeLock = false
    private var dragging = false
    private var anchor = -1

    override fun onScrollStateChanged(state: Int) {
        super.onScrollStateChanged(state)
        dragging = state == RecyclerView.SCROLL_STATE_DRAGGING
        if (dragging) {
            val vert = orientation == VERTICAL
            val lo = if (vert) paddingTop else paddingLeft
            val hi = if (vert) height - paddingBottom else width - paddingRight
            var best = -1
            var bestO = Int.MIN_VALUE
            for (i in 0 until childCount) {
                val v = getChildAt(i) ?: continue
                val a = if (vert) getDecoratedTop(v) else getDecoratedLeft(v)
                val b = if (vert) getDecoratedBottom(v) else getDecoratedRight(v)
                val o = minOf(b, hi) - maxOf(a, lo)
                if (o > bestO) { bestO = o; best = getPosition(v) }
            }
            anchor = best
        }
    }

    /** Quanto falta para a borda da página âncora no sentido do deslize (positivo = para baixo). */
    fun edgeDelta(fingerUp: Boolean): Int {
        val v = findViewByPosition(anchor) ?: return 0
        val lo = paddingTop
        val hi = height - paddingBottom
        return if (fingerUp) maxOf(0, getDecoratedBottom(v) - hi) else -maxOf(0, lo - getDecoratedTop(v))
    }

    /** Limita o arrasto do dedo ao que ainda cabe dentro da página âncora. */
    private fun clampDrag(d: Int, vert: Boolean): Int {
        val v = findViewByPosition(anchor) ?: return 0
        val lo = if (vert) paddingTop else paddingLeft
        val hi = if (vert) height - paddingBottom else width - paddingRight
        val start = if (vert) getDecoratedTop(v) else getDecoratedLeft(v)
        val end = if (vert) getDecoratedBottom(v) else getDecoratedRight(v)
        return if (d > 0) minOf(d, maxOf(0, end - hi)) else maxOf(d, -maxOf(0, lo - start))
    }

    /**
     * Posição do último salto direto. Durante o processamento do Refluxo cada atualização de página
     * fazia a lista se re-ancorar sozinha numa página antiga (a lista voltava a
     * ela sem nenhuma rolagem). Enquanto o usuário (ou uma rolagem animada) não mexer na lista,
     * cada novo layout reafirma o destino do salto.
     */
    private var stickPos = -1
    private var stickOff = 0

    override fun scrollToPositionWithOffset(position: Int, offset: Int) {
        stickPos = position
        stickOff = offset
        super.scrollToPositionWithOffset(position, offset)
    }

    override fun scrollToPosition(position: Int) {
        stickPos = position
        stickOff = 0
        super.scrollToPosition(position)
    }

    override fun startSmoothScroll(smoothScroller: RecyclerView.SmoothScroller) {
        stickPos = -1
        super.startSmoothScroll(smoothScroller)
    }

    override fun onAdapterChanged(oldAdapter: RecyclerView.Adapter<*>?, newAdapter: RecyclerView.Adapter<*>?) {
        stickPos = -1
        super.onAdapterChanged(oldAdapter, newAdapter)
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        if (stickPos >= 0 && !state.isPreLayout && state.itemCount > stickPos) {
            // sempre reafirma: o registro mostrou layouts que, mesmo com a lista no destino,
            // se ancoravam na página antiga (a lista alternava entre o destino e a página antiga)
            super.scrollToPositionWithOffset(stickPos, stickOff)
        } else if (state.itemCount <= stickPos) {
            stickPos = -1
        }
        super.onLayoutChildren(recycler, state)
    }

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        var d = dy
        if (swipeLock && dragging && d != 0) d = clampDrag(d, true)
        if (d != 0) stickPos = -1
        if (d == 0) return 0
        val r = super.scrollVerticallyBy(d, recycler, state)
        return r
    }

    override fun scrollHorizontallyBy(dx: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        var d = dx
        if (swipeLock && dragging && d != 0) d = clampDrag(d, false)
        if (d != 0) stickPos = -1
        if (d == 0) return 0
        val r = super.scrollHorizontallyBy(d, recycler, state)
        return r
    }

    override fun canScrollHorizontally(): Boolean = userScrollEnabled && super.canScrollHorizontally()

    // o texto selecionável do Refluxo pede foco a cada atualização da página; com isso a lista
    // rolava de volta para a página que estava em foco e desfazia a troca de página pelo toque
    override fun requestChildRectangleOnScreen(
        parent: RecyclerView, child: View, rect: android.graphics.Rect,
        immediate: Boolean, focusedChildVisible: Boolean
    ): Boolean = false
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

    // pedaço da página redesenhado em resolução total (só a parte visível, com zoom alto)
    private var tile: Bitmap? = null
    private val tileSrc = Rect()
    private val tileDst = Rect()
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    var tileKey = ""

    fun setTile(b: Bitmap?, srcW: Int, srcH: Int, dst: Rect?, key: String) {
        tile = b
        tileKey = key
        if (b != null && dst != null) {
            tileSrc.set(0, 0, srcW, srcH)
            tileDst.set(dst)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = tile
        if (t != null) {
            tilePaint.colorFilter = colorFilter // mesmo filtro do modo noturno da página
            canvas.drawBitmap(t, tileSrc, tileDst, tilePaint)
        }
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
        const val MAX_WINDOWS = 3 // PDFs abertos ao mesmo tempo (uma janela do Android para cada)
        @Volatile var liveWindows = 0 // janelas do app vivas neste processo
        @Volatile var lastResumed: java.lang.ref.WeakReference<MainActivity>? = null // janela usada por último
        @Volatile var windowsWithPdf = 0 // dessas, as que já têm um PDF aberto
        const val EXTRA_NEW_WINDOW = "novaJanela" // aberta por este app: não pergunta de novo
        const val ZOOM_MAX = 3.75f // 15 níveis de 0.25 (eram 10: 2.5f)
        const val ZOOM_STEP = 0.25f
        const val ZOOM_MIN = 0.75f // mesma escala do Refluxo: níveis de 1/16 (1 sp), de x0,75 até ZOOM_MAX
        const val ZOOM_UNIT = 1f / 16f
        // velocidade mínima (px/s) para o deslize virar a página; abaixo disso só move a página
        const val FAST_SWIPE = 1200f
        // distância da inércia do deslize = velocidade (px/s) x este tempo, limitada à página
        const val FLING_SECONDS = 0.3f
        // vertical: o deslize corre (velocidade em px/s x este tempo) px e desacelera; maior = corre mais longe
        const val VERTICAL_FLING_SECONDS = 0.8f
        // região vertical onde o toque vale (fração da altura da tela): antes 0.25–0.75
        const val TAP_TOP = 0.08f
        const val TAP_BOTTOM = 0.92f
        const val TEXT_DEFAULT = 16f // tamanho padrão do texto no Refluxo (antes 18f)
        const val TEXT_MIN = 12f
        const val TEXT_MAX = 40f
        const val RENDER_MAX_W = 2700 // largura máxima do desenho de base da página; acima disso o pedaço nítido completa
        const val RENDER_MAX_PIXELS = 12_000_000L // ~48 MB por página de base (o Android recusa bitmaps acima de ~100 MB)
        const val MAX_HITS = 300
        const val REFLOW_CACHE_VERSION = 1 // mude quando o método de extração mudar
    }

    private class PageText(
        val text: String, val spans: List<Reflow.Span>, val loaded: Boolean = true,
        val image: String? = null, // EPUB/DOCX: figura desta página (nome da parte dentro do zip)
        val fmts: List<EpubBook.Fmt> = emptyList() // DOCX: formatação do texto
    )
    private class ReflowData(val targets: List<LinkTarget>, val pages: List<PageText>)

    private val main = Handler(Looper.getMainLooper())

    // ---- modo refluxo (texto) ----
    private val pages = ArrayList<PageText>()
    private val reflowTargets = ArrayList<LinkTarget>()
    private var pagesUri: Uri? = null
    private var pagesComplete = false
    private var docIsEpub = false // arquivo aberto é EPUB (só Refluxo, sem modo Original)
    private var docIsDocx = false // arquivo aberto é DOCX (também vale para DOC: mesmo comportamento)
    private var docIsDoc = false // arquivo aberto é DOC
    private var docIsComic = false // arquivo aberto é CBZ/CBR (quadrinhos: usa o visualizador do modo Original)
    private var comicNames: List<String> = emptyList() // imagem de cada página do CBZ/CBR
    private lateinit var searchBtnRef: Button
    private lateinit var swipeBtn: Button
    private lateinit var rowTwo: LinearLayout
    private lateinit var rowOne: LinearLayout
    private lateinit var centerBtnRef: Button
    private val sizeBtns = ArrayList<Button>()
    private var openBtnMinH = 0
    private var swipeLock = false // só quadrinhos: o deslizamento do dedo não troca de página
    private val docxOriginal = true // DOCX: o texto sempre aparece com a formatação do documento
    private var epubFile: File? = null // cópia do EPUB aberto (acesso direto às figuras)
    @Volatile private var epubZip: java.util.zip.ZipFile? = null
    private val imgExec = Executors.newFixedThreadPool(2)
    private val epubBmp = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(8 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

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
    private var keepZoom = false // deste PDF: lembrar zoom/tamanho do texto e a centralização
    private var keepMode = false // deste PDF: lembrar o modo (Original/Refluxo)
    private var keepLayout = false // deste PDF: lembrar o deslizamento (Vertical/Horizontal)
    private var keepNight = false // deste PDF: lembrar Dia/Noite
    private var keepRot = false // deste PDF: lembrar a rotação da tela
    private var rotation = 0 // 0 = automática/padrão, 1..3 = 90°, 180°, 270° à direita
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
    private var snapAfterFling = false
    private var dragAcc = 0 // quanto o dedo arrastou a lista neste gesto (sentido das páginas)
    private var flingSeen = false
    private var originPos = -1 // página que estava na tela quando o gesto começou
    private var snapStartOff = 0 // posição da página do topo/esquerda ao encostar o dedo (px)
    // Original + horizontal com zoom: quanto o dedo foi além da borda da página ampliada (px, com sinal)
    private var overAcc = 0f
    private var overLastX = 0f
    private var overInvalid = false
    // posição horizontal (centralização) do modo Original, guardada ao ir para o Refluxo
    private var origScrollX = 0

    // ---- views ----
    private lateinit var root: FrameLayout
    private lateinit var hScroll: HorizontalScrollView
    private lateinit var list: RecyclerView
    private lateinit var bar: LinearLayout
    private lateinit var status: TextView
    private lateinit var pageIndicator: TextView
    private lateinit var loadIndicator: TextView
    private lateinit var indBox: LinearLayout
    private var scanUri: Uri? = null // medição das páginas do modo Original em andamento (pode ser retomada)
    private var scanArr = ArrayList<IntArray>()
    private var scanNext = 1 // primeira página ainda não medida
    private var sizesUri: Uri? = null // arquivo a que pertence a lista [sizes] (número de páginas já conhecido)
    private var origScanUri: Uri? = null // PDF cujo tamanho de todas as páginas (modo Original) já foi medido
    private var origScanSizes: ArrayList<IntArray>? = null
    @Volatile private var loadPct = -1 // progresso do carregamento do arquivo (0..99 visível; <0 ou >=100 escondido)
    private lateinit var modeBtn: Button
    private lateinit var openBtn: Button
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
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            chooseWhereToOpen(uri)
        }
    }

    /** Com um PDF já aberto, pergunta se o novo vai para outra janela ou substitui o desta. */
    private fun chooseWhereToOpen(uri: Uri) {
        if (currentUri == null || currentUri == uri) {
            open(uri)
            return
        }
        val canNew = liveWindows < MAX_WINDOWS
        val b = AlertDialog.Builder(this)
            .setTitle("Abrir este arquivo:")
            .setMessage(
                if (canNew) "Em uma nova janela, o arquivo atual continua aberto. Nesta janela, o arquivo atual é " +
                    "fechado (as configurações dele ficam guardadas)."
                else "Já há $MAX_WINDOWS arquivos abertos. Para abrir outro, ele substitui o desta janela " +
                    "(as configurações do atual ficam guardadas), ou feche uma das outras janelas."
            )
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Nesta janela") { _, _ ->
                savePage(true)
                saveCenter(true)
                open(uri)
            }
        if (canNew) b.setPositiveButton("Nova janela") { _, _ -> openInNewWindow(uri) }
        b.show()
    }

    private fun openInNewWindow(uri: Uri) {
        val i = Intent(this, MainActivity::class.java).apply {
            data = uri
            putExtra(EXTRA_NEW_WINDOW, true)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        try {
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir a nova janela.", Toast.LENGTH_LONG).show()
        }
    }

    // toque no centro mostra/esconde a barra; deslize rápido para os lados vira a página
    private val tapDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // um link acabou de ser tocado: não alterna a barra
                if (SystemClock.uptimeMillis() - lastLinkTap < 700) return false
                if (overOverlay(e)) return false
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

            // toques rápidos seguidos na borda viravam "toque duplo" e a página não trocava:
            // guarda o lado do primeiro toque e troca uma página por toque
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                lastEdgeDir = edgeDir(e.x, e.y)
                lastEdgeTime = SystemClock.uptimeMillis()
                return false
            }

            override fun onDoubleTapEvent(e: MotionEvent): Boolean {
                if (e.actionMasked != MotionEvent.ACTION_UP || overOverlay(e)) return false
                val d = edgeDir(e.x, e.y)
                if (d == 0) return false
                val n = if (lastEdgeDir == d && SystemClock.uptimeMillis() - lastEdgeTime < 800) 2 else 1
                // depois do toque ser tratado (um link tocado não deve virar a página)
                root.post {
                    if (SystemClock.uptimeMillis() - lastLinkTap >= 700) pageStep(d * n)
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // dois toques rápidos no centro: PDF alterna entre Original e Refluxo; demais formatos sobem o zoom
                if (currentUri == null || overOverlay(e)) return false
                val w = root.width
                val h = root.height
                if (e.x > w * 0.25f && e.x < w * 0.75f && e.y > h * TAP_TOP && e.y < h * TAP_BOTTOM) {
                    // formatos sem modo Original/Refluxo (quadrinhos, EPUB, DOC, DOCX): dois toques aumentam o zoom um nível
                    if (docIsComic || docIsEpub || docIsDocx) changeSize(+1) else toggleMode()
                    return true
                }
                return false
            }

            // troca de página por deslize desativada: só o toque nas bordas troca de página
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean = false
        }).also {
            // toque um pouco demorado não vira "toque longo" (que cancelava o toque lateral no Refluxo)
            it.setIsLongpressEnabled(false)
        }
    }

    /** O toque caiu sobre o menu, o número da página ou a barra de seleção: não é toque na página. */
    private fun overOverlay(e: MotionEvent): Boolean {
        val loc = IntArray(2)
        for (v in listOf<View>(bar, pageIndicator, loadIndicator, selBar)) {
            if (v.visibility != View.VISIBLE || v.width == 0) continue
            v.getLocationOnScreen(loc)
            if (e.rawX >= loc[0] && e.rawX <= loc[0] + v.width &&
                e.rawY >= loc[1] && e.rawY <= loc[1] + v.height
            ) return true
        }
        return false
    }

    private var lastEdgeDir = 0
    private var lastEdgeTime = 0L

    /** +1 toque na borda direita, -1 na esquerda (faixa vertical válida), 0 fora delas. */
    private fun edgeDir(x: Float, y: Float): Int {
        val w = root.width
        val h = root.height
        if (y <= h * TAP_TOP || y >= h * TAP_BOTTOM) return 0
        return when {
            x >= w * 0.75f -> 1
            x <= w * 0.25f -> -1
            else -> 0
        }
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
                    val target = (zoom * pinchFactor * d.scaleFactor).coerceIn(ZOOM_MIN, ZOOM_MAX)
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
                    updatePageIndicator()
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
            cancelComicFling()
            pinchedRecently = false
            canPanRightAtDown = hScroll.canScrollHorizontally(1)
            canPanLeftAtDown = hScroll.canScrollHorizontally(-1)
            downScrollX = hScroll.scrollX
            turnAfterUp = 0
            overAcc = 0f
            overLastX = ev.x
            overInvalid = false
            snapAfterFling = false // tocar na tela interrompe a corrida: sem encaixe
            alignTarget = -1
            glideSeq++ // e cancela as conferências de chegada de um salto em andamento
            focusLock = -1
            resumeUi()
            snapStartOff = 0
            dragAcc = 0
            flingSeen = false
            originPos = if (list.layoutManager != null) lm().findFirstVisibleItemPosition() else -1
            if (list.layoutManager != null && (list.adapter?.itemCount ?: 0) > 0) {
                val v0 = lm().findViewByPosition(lm().findFirstVisibleItemPosition())
                if (v0 != null) snapStartOff = if (isBook) v0.left else v0.top - list.paddingTop
            }
        }
        if (ev.pointerCount > 1) overInvalid = true
        scaleDetector.onTouchEvent(ev)
        if (scaling) {
            // o detector de toque não pode ficar com um gesto "pela metade" (isso fazia o
            // toque lateral seguinte falhar até que um deslize o reiniciasse)
            if (!cancelSent) {
                val tc = MotionEvent.obtain(ev)
                tc.action = MotionEvent.ACTION_CANCEL
                tapDetector.onTouchEvent(tc)
                tc.recycle()
            }
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
        val zoomedBook = isBook && !reflowMode && zoom > 1f && !(swipeLock && docIsComic)
        if (zoomedBook) {
            // arrasto para os lados com zoom: as páginas formam uma "faixa" contínua (ver handleStrip)
            when (handleStrip(ev)) {
                1 -> return true
                2 -> {
                    val c = MotionEvent.obtain(ev)
                    c.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(c)
                    c.recycle()
                    return true
                }
            }
        }
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP && !flingSeen && isBook && !(!reflowMode && zoom > 1f) &&
            dragAcc != 0 && list.layoutManager != null
        ) {
            // soltou o dedo devagar (sem deslize rápido): só vira a página se arrastou o bastante
            if (abs(dragAcc) >= swipeNeeded()) snapToNearestPage() else returnToOrigin()
        }
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

    // ------------------------------------------------------------------ faixa contínua (Original + horizontal + zoom)

    private var stripTracking = false
    private var stripActive = false
    private var stripDownX = 0f
    private var stripDownY = 0f
    private var stripLastX = 0f
    private var stripS = 0f
    private var stripVt: android.view.VelocityTracker? = null
    private var stripAnim: android.animation.ValueAnimator? = null
    private var stripStartPage = -1 // página no meio da tela quando o gesto começou

    /**
     * Com zoom no formato horizontal, as páginas formam uma faixa contínua: o arrasto anda dentro da
     * página e segue direto para a próxima, e o deslize rápido corre várias páginas conforme a
     * velocidade (como no formato vertical). Ao parar entre duas páginas, encaixa na mais próxima,
     * centralizada. Retorna 0 = deixa o gesto seguir, 1 = tratado aqui, 2 = tratado, e os demais
     * recebem um cancelamento (o arrasto passou a ser nosso).
     */
    private fun handleStrip(ev: MotionEvent): Int {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stripAnim?.cancel() // tocar na tela interrompe a corrida
                stripAnim = null
                stripActive = false
                stripTracking = !selDragging
                stripStartPage = run {
                    val s0 = currentStrip()
                    val l0 = list.width.toFloat()
                    if (s0 == null || l0 <= 0f) -1 else kotlin.math.floor((s0 + hScroll.width / 2f) / l0).toInt()
                }
                stripDownX = ev.x
                stripDownY = ev.y
                stripVt?.recycle()
                stripVt = android.view.VelocityTracker.obtain().also { it.addMovement(ev) }
                return 0
            }
            MotionEvent.ACTION_MOVE -> {
                stripVt?.addMovement(ev)
                if (!stripActive) {
                    if (!stripTracking || ev.pointerCount > 1 || selDragging) return 0
                    val dx = ev.x - stripDownX
                    val dy = ev.y - stripDownY
                    val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
                    if (abs(dx) > slop && abs(dx) > 2f * abs(dy)) {
                        val s0 = currentStrip() ?: return 0
                        stripS = s0
                        stripLastX = ev.x
                        stripActive = true
                        return 2
                    }
                    return 0
                }
                if (ev.pointerCount > 1) return 1
                stripS -= ev.x - stripLastX
                stripLastX = ev.x
                applyStrip()
                return 1
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val vt = stripVt
                stripVt = null
                stripTracking = false
                if (!stripActive) {
                    vt?.recycle()
                    return 0
                }
                stripActive = false
                var vx = 0f
                if (vt != null) {
                    vt.addMovement(ev)
                    vt.computeCurrentVelocity(1000)
                    vx = vt.xVelocity
                    vt.recycle()
                }
                if (ev.actionMasked == MotionEvent.ACTION_CANCEL) vx = 0f
                flingStrip(-vx)
                return 1
            }
        }
        return if (stripActive) 1 else 0
    }

    /** Posição atual na faixa (borda esquerda da janela), a partir do estado da lista e da rolagem lateral. */
    private fun currentStrip(): Float? {
        val n = list.adapter?.itemCount ?: 0
        if (n == 0 || list.width <= 0) return null
        val l = list.width.toFloat()
        val w = hScroll.width.toFloat()
        val p0 = lm().findFirstVisibleItemPosition()
        if (p0 < 0) return null
        val v = lm().findViewByPosition(p0) ?: return null
        val rem = (-v.left).coerceAtLeast(0)
        return if (rem <= 1) p0 * l + hScroll.scrollX else p0 * l + rem + (l - w)
    }

    /** Aplica [stripS]: página (e deslocamento entre duas páginas) na lista e posição lateral na rolagem. */
    private fun applyStrip() {
        val n = list.adapter?.itemCount ?: 0
        if (n == 0 || list.width <= 0) return
        val l = list.width.toFloat()
        val w = hScroll.width.toFloat()
        val maxS = (n * l - w).coerceAtLeast(0f)
        stripS = stripS.coerceIn(0f, maxS)
        val p = kotlin.math.floor(stripS / l).toInt().coerceIn(0, n - 1)
        val right = stripS + w
        val rem: Float
        val x: Float
        if (right <= (p + 1) * l + 0.5f) {
            rem = 0f
            x = stripS - p * l
        } else {
            rem = right - (p + 1) * l
            x = l - w
        }
        hScroll.scrollTo(x.toInt().coerceAtLeast(0), 0)
        lm().scrollToPositionWithOffset(p, -rem.toInt())
    }

    private fun flingStrip(v: Float) {
        if (abs(v) < 300f) {
            snapStrip()
            return
        }
        val dist = v * VERTICAL_FLING_SECONDS
        val ms = (600f + abs(v) * 0.15f).coerceAtMost(2500f).toLong()
        val from = stripS
        runStripAnim(from, from + dist, ms, android.view.animation.DecelerateInterpolator(2f)) { snapStrip() }
    }

    private fun runStripAnim(
        from: Float, to: Float, ms: Long, ip: android.animation.TimeInterpolator, done: () -> Unit
    ) {
        stripAnim?.cancel()
        val a = android.animation.ValueAnimator.ofFloat(from, to)
        a.duration = ms
        a.interpolator = ip
        a.addUpdateListener {
            stripS = it.animatedValue as Float
            applyStrip()
        }
        var cancelled = false
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: android.animation.Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (!cancelled) done()
            }
        })
        stripAnim = a
        a.start()
    }

    /** Parou entre duas páginas: encaixa na mais próxima, alinhada à borda da tela (mesmo zoom). */
    private fun snapStrip() {
        val n = list.adapter?.itemCount ?: 0
        if (n == 0 || list.width <= 0) return
        val l = list.width.toFloat()
        val w = hScroll.width.toFloat()
        val p = kotlin.math.floor(stripS / l).toInt().coerceIn(0, n - 1)
        val straddle = stripS + w > (p + 1) * l + 1f && p < n - 1
        val pt = kotlin.math.floor((stripS + w / 2f) / l).toInt().coerceIn(0, n - 1)
        // trocou de página pelo deslize (mesmo parando dentro da página nova, sem cruzar duas):
        // o canto superior esquerdo da página vai ao canto superior esquerdo da tela
        val changed = stripStartPage >= 0 && pt != stripStartPage
        if (!changed && !straddle) {
            updatePageIndicator()
            saveCenter()
            return
        }
        // sem trocar de página e com zoom: volta à borda da página (direita ou esquerda) em vez de centralizar,
        // para a borda lateral da página não ficar recuada em relação à borda da tela
        val target = when {
            changed -> pt * l
            l > w + 1f -> if (pt == p) p * l + (l - w) else pt * l
            else -> pt * l + ((l - w) / 2f).coerceAtLeast(0f)
        }
        val finish = {
            if (changed) {
                stripStartPage = pt
                // a página pode ainda não estar na lista: repete até o topo da rolagem vertical dela zerar
                for (d in longArrayOf(0, 80, 200, 450)) {
                    list.postDelayed({
                        (lm().findViewByPosition(pt) as? ScrollView)?.scrollTo(0, 0)
                    }, d)
                }
            }
            updatePageIndicator()
            saveCenter()
        }
        if (abs(stripS - target) < 1f) finish() else
            runStripAnim(stripS, target, 160L, android.view.animation.DecelerateInterpolator()) { finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        liveWindows++
        // várias janelas dividem a mesma memória: cada nova usa um cache de imagens menor
        bmpCache.resize((Runtime.getRuntime().maxMemory() / (6L * liveWindows)).toInt().coerceAtLeast(8 * 1024 * 1024))

        val prefs = getSharedPreferences("leitor", MODE_PRIVATE)
        night = false // todo PDF novo abre no modo Dia; o "Lembrar Dia/Noite" é por PDF (ver open)
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
            // sem animação de troca: durante o processamento do Refluxo a lista é atualizada o
            // tempo todo e as animações atrapalhavam a troca de página pelo toque
            itemAnimator = null
        }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updatePageIndicator()
                if (rv.scrollState == RecyclerView.SCROLL_STATE_DRAGGING) dragAcc += if (isBook) dx else dy
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    updatePageIndicator()
                    // terminou uma corrida por deslize rápido: encaixa na página mais próxima
                    if (snapAfterFling) {
                        snapAfterFling = false
                        snapToNearestPage()
                    }
                    if (alignTarget >= 0) alignToPage(alignTarget)
                }
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
        openBtn = makeBtn("Abrir") { picker.launch(
                arrayOf(
                    "application/pdf", "application/epub+zip", "application/msword",
                    "application/vnd.comicbook+zip", "application/vnd.comicbook-rar", "application/x-cbz", "application/x-cbr", "application/x-cb7", "application/x-7z-compressed",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/octet-stream"
                )
            ) }
        row1.addView(openBtn)
        openBtnMinH = openBtn.minHeight
        modeBtn = makeBtn("") { toggleMode() }
        row1.addView(modeBtn)
        val btnMinus = makeBtn("A−") { changeSize(-1) }
        val btnPlus = makeBtn("A+") { changeSize(+1) }
        sizeBtns.add(btnMinus)
        sizeBtns.add(btnPlus)
        row1.addView(btnMinus)
        row1.addView(btnPlus)
        themeBtn = makeBtn("") { toggleTheme() }
        row1.addView(themeBtn)
        val searchBtn = makeBtn("🔍") { showSearchDialog() }
        searchBtnRef = searchBtn
        (searchBtn.layoutParams as LinearLayout.LayoutParams).weight = 0.6f
        row1.addView(searchBtn)
        swipeBtn = makeBtn("Barrar troca por deslizamento") {
            swipeLock = !swipeLock
            applySwipeLock()
            applyTheme()
            updateLabels()
        }
        (swipeBtn.layoutParams as LinearLayout.LayoutParams).weight = 1.5f
        swipeBtn.visibility = View.GONE
        row1.addView(swipeBtn)
        bar.addView(row1, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rowOne = row1

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        layoutBtn = makeBtn("") { cyclePageLayout() }
        val centerBtn = makeBtn("Centralizar") { centerPage() }
        centerBtnRef = centerBtn
        settingsBtn = makeBtn("Configurar") { showSettings() }
        val rotBtn = makeBtn("\u21BB") { rotateScreen() }
        rotBtn.textSize = 18f
        rotBtn.contentDescription = "Girar a tela 90° para a direita"
        rotBtn.layoutParams = LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dp(2), 0, dp(2), 0) }
        row2.addView(layoutBtn)
        row2.addView(centerBtn)
        row2.addView(rotBtn)
        row2.addView(settingsBtn)
        val row2Lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        row2Lp.topMargin = dp(2)
        bar.addView(row2, row2Lp)
        rowTwo = row2

        status = TextView(this).apply {
            text = "Toque em Abrir e escolha um PDF, DOC, DOCX, EPUB, CBZ, CBR ou CB7."
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
        // indicador de carregamento do arquivo: centralizado e colado acima do número da página
        loadIndicator = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B3000000"))
                cornerRadius = dp(14).toFloat()
            }
            visibility = View.GONE
        }
        indBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        indBox.addView(loadIndicator, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        indBox.addView(pageIndicator, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val indLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        indLp.bottomMargin = dp(14)
        root.addView(indBox, indLp)
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
        // rolou (a lista ou a página ampliada): redesenha o pedaço visível em resolução total
        root.viewTreeObserver.addOnScrollChangedListener { scheduleTiles() }

        controller = WindowCompat.getInsetsController(window, root)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // a barra de botões respeita a barra de status e o recorte da câmera
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sb = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            bar.setPadding(dp(6) + sb.left, sb.top + dp(4), dp(6) + sb.right, dp(4))
            (indBox.layoutParams as FrameLayout.LayoutParams).bottomMargin = sb.bottom + dp(12)
            indBox.requestLayout()
            (selBar.layoutParams as FrameLayout.LayoutParams).bottomMargin = sb.bottom + dp(58)
            selBar.requestLayout()
            insets
        }

        // "Voltar" desfaz o salto de um link interno (volta para onde você estava)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (backStack.isNotEmpty()) {
                    val pos = backStack.removeAt(backStack.size - 1)
                    if (reflowMode && !pagesComplete) {
                        // durante o processamento do Refluxo, a mesma rolagem rápida do salto de ida
                        jumpToPage(pos.coerceIn(0, (list.adapter?.itemCount ?: 1) - 1))
                    } else {
                        lm().scrollToPositionWithOffset(pos, 0)
                    }
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

        val first = intent?.data
        if (first != null) {
            if (savedInstanceState == null && !intent.getBooleanExtra(EXTRA_NEW_WINDOW, false) &&
                windowsWithPdf > 0
            ) askForExternalPdf(first)
            else open(first)
        }
    }

    private var hasPdf = false

    /**
     * PDF aberto de fora do app (toque no arquivo) quando já há outro aberto: o Android o põe numa tela
     * nova sobre a anterior; aqui o usuário escolhe uma janela separada ou desiste.
     */
    private fun askForExternalPdf(uri: Uri) {
        val canNew = liveWindows - 1 < MAX_WINDOWS // esta tela será trocada pela nova janela
        val b = AlertDialog.Builder(this)
            .setTitle("Abrir este arquivo:")
            .setCancelable(false)
            .setMessage(
                if (canNew) "Em uma nova janela, o arquivo que você estava lendo continua aberto. " +
                    "Nesta janela, ele é fechado e substituído (as configurações dele ficam guardadas)."
                else "Já há $MAX_WINDOWS arquivos abertos. Nesta janela, o arquivo que você estava lendo é " +
                    "fechado e substituído (as configurações dele ficam guardadas)."
            )
            .setNegativeButton("Cancelar") { _, _ -> finish() }
            .setNeutralButton("Nesta janela") { _, _ ->
                // fecha o PDF que estava aberto antes (a janela usada por último) e abre este aqui
                val prev = lastResumed?.get()?.takeIf { it !== this && !it.isFinishing }
                if (prev != null) {
                    prev.savePage(true)
                    prev.saveCenter(true)
                    prev.finish()
                }
                open(uri)
            }
        if (canNew) {
            b.setPositiveButton("Nova janela") { _, _ ->
                openInNewWindow(uri)
                finish() // esta tela de passagem fecha; a nova janela fica separada
            }
        }
        b.show()
    }

    override fun onResume() {
        super.onResume()
        lastResumed = java.lang.ref.WeakReference(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // a rotação não pode mudar a página: guarda a atual e volta a ela depois do novo layout
        val pg = if (currentUri != null && (list.adapter?.itemCount ?: 0) > 0) currentPage() else -1
        applyWidth()
        list.adapter?.notifyDataSetChanged()
        if (pg >= 0) {
            val fix = Runnable { lm().scrollToPositionWithOffset(pg, 0) }
            list.post(fix)
            list.postDelayed(fix, 250)
        }
    }

    override fun onPause() {
        super.onPause()
        // gravação imediata (commit): se o app for fechado à força logo depois, não se perde
        savePage(true)
        saveCenter(true)
    }

    override fun onStop() {
        super.onStop()
        savePage(true)
        saveCenter(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (liveWindows > 0) liveWindows--
        if (hasPdf && windowsWithPdf > 0) windowsWithPdf--
        loadToken++
        closeRenderer()
        renderExec.shutdownNow()
        try { epubZip?.close() } catch (_: Throwable) {}
        try { epubFile?.delete() } catch (_: Throwable) {}
        imgExec.shutdownNow()
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

    /** Tela inicial: o botão Abrir ganha contorno preto (claro no modo Noite, para continuar visível). */
    private fun styleOpenBtn() {
        val fill = if (night) Color.parseColor("#2C2C2C") else Color.parseColor("#D8D3C4")
        if (currentUri == null) {
            openBtn.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(fill)
                setStroke(dp(2), if (night) Color.parseColor("#BBBBBB") else Color.BLACK)
                cornerRadius = dp(4).toFloat()
            }
        } else {
            openBtn.setBackgroundColor(fill)
        }
    }

    private fun updateLabels() {
        modeBtn.text = if (docIsEpub) "EPUB: só Refluxo"
        else if (docIsDoc) "DOC: só Refluxo"
        else if (docIsDocx) "DOCX: só Refluxo"
        else if (reflowMode) "PDF Modo Original" else "PDF Modo Refluxo"
        // sem documento aberto: só o botão Abrir, ocupando a linha toda; com documento: Abrir reduzido + demais botões
        val noDoc = currentUri == null
        val isPdf = !docIsComic && !docIsEpub && !docIsDocx && !docIsDoc
        modeBtn.visibility = if (!noDoc && isPdf) View.VISIBLE else View.GONE // os demais tipos só têm Refluxo
        themeBtn.visibility = if (noDoc) View.GONE else View.VISIBLE
        for (b in sizeBtns) b.visibility = if (noDoc) View.GONE else View.VISIBLE
        searchBtnRef.visibility = if (noDoc || docIsComic) View.GONE else View.VISIBLE
        swipeBtn.visibility = if (docIsComic && !noDoc) View.VISIBLE else View.GONE
        swipeBtn.text = if (swipeLock) "Soltar troca por deslizamento" else "Barrar troca por deslizamento"
        rowTwo.visibility = if (noDoc) View.GONE else View.VISIBLE
        centerBtnRef.visibility = if (reflowMode) View.GONE else View.VISIBLE // só PDF Original, CBZ e CBR
        openBtn.text = if (noDoc) "Toque aqui para Abrir: PDF, EPUB, DOCX, DOC, CBZ, CBR, CB7" else "Abrir"
        openBtn.maxLines = if (noDoc) 2 else 1
        openBtn.minHeight = openBtnMinH
        openBtn.minimumHeight = openBtnMinH
        openBtn.textSize = 13f
        openBtn.setTypeface(null, if (noDoc) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        (openBtn.layoutParams as LinearLayout.LayoutParams).apply {
            weight = 1f
            topMargin = 0
            // tela inicial: altura fixa (o dobro do que era) e a linha inteira um pouco mais abaixo
            height = if (noDoc) dp(72) else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        rowOne.setPadding(0, if (noDoc) dp(24) else 0, 0, 0)
        openBtn.gravity = Gravity.CENTER // texto centrado também na vertical
        openBtn.includeFontPadding = false
        styleOpenBtn()
        openBtn.requestLayout()
        themeBtn.text = if (night) "☀ Dia" else "☾ Noite"
        layoutBtn.text = if (isBook) "Páginas: Vertical" else "Páginas: Horizontal" // mostra para onde o toque leva
    }

    private fun toggleBar() {
        barVisible = !barVisible
        bar.visibility = if (barVisible) View.VISIBLE else View.GONE
        if (barVisible) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
        updatePageIndicator() // o número da página aparece e some junto com o menu
    }

    /** Próximo nível padrão (grade de 1 em 1 sp = 1/16) no sentido pedido, a partir de um valor qualquer. */
    private fun snapLevel(cur: Float, dir: Int): Float {
        val eps = 0.001f
        return if (dir > 0) kotlin.math.floor(cur + eps) + 1f else kotlin.math.ceil(cur - eps) - 1f
    }

    private fun applyRotation() {
        // o Android costuma recusar o retrato de cabeça para baixo: no 180° o próprio app gira o conteúdo
        root.rotation = if (rotation == 2) 180f else 0f
        requestedOrientation = when (rotation) {
            1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT // 180°: o conteúdo é girado pelo app
            3 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun rotateScreen() {
        rotation = (rotation + 1) % 4
        applyRotation()
        saveDocSettings()
    }

    private fun changeSize(dir: Int) {
        if (reflowMode) {
            textSp = snapLevel(textSp, dir).coerceIn(TEXT_MIN, TEXT_MAX)
            saveDocSettings()
            reflowAdapter.notifyDataSetChanged()
            updatePageIndicator()
        } else {
            val sp = snapLevel(zoom * 16f, dir)
            setZoom(sp * ZOOM_UNIT, resources.displayMetrics.widthPixels / 2f)
        }
    }

    /** Zoom do modo original: mantém a página em que você está e o ponto focal. */
    private fun setZoom(newZoom: Float, focusX: Float) {
        val old = zoom
        zoom = newZoom.coerceIn(ZOOM_MIN, ZOOM_MAX)
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
        updatePageIndicator() // mostra o nível de zoom
    }

    private fun toggleTheme() {
        night = !night
        saveDocSettings()
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
        if (keepNight) e.putBoolean(dk("night"), night)
        if (keepRot) e.putInt(dk("rot"), rotation)
        e.apply()
    }

    /** Grava a centralização (posição horizontal do modo Original) junto com o zoom lembrado. */
    private fun saveCenter(sync: Boolean = false) {
        if (!keepZoom || currentUri == null || currentKey.isEmpty()) return
        val x = if (reflowMode) origScrollX else hScroll.scrollX
        val e = getSharedPreferences("leitor", MODE_PRIVATE).edit().putInt(dk("scrollX"), x)
        if (sync) e.commit() else e.apply()
    }

    /** Ao fim de um deslize rápido, completa o rolamento até o começo da página mais próxima. */
    /** Quanto o dedo precisa arrastar para trocar de página: 30% da largura (horizontal) ou 15% da altura (vertical). */
    private fun swipeNeeded(): Float = if (isBook) list.width * 0.30f else list.height * 0.15f

    /** Volta para a página em que o gesto começou (o arrasto foi curto demais para trocar). */
    private fun returnToOrigin() {
        val v = if (originPos >= 0) lm().findViewByPosition(originPos) else null
        if (v == null) {
            snapToNearestPage()
            return
        }
        val dx = v.left
        if (dx != 0) list.smoothScrollBy(dx, 0)
    }

    private fun snapToNearestPage() {
        if (list.layoutManager == null || (list.adapter?.itemCount ?: 0) == 0) return
        if (isBook && !reflowMode && zoom > 1f) return
        val first = lm().findFirstVisibleItemPosition()
        if (first < 0) return
        val v = lm().findViewByPosition(first) ?: return
        // a página final para na mesma posição em que a página inicial estava ao começar o deslize
        // (no horizontal as páginas ocupam a tela toda: a posição que vale é sempre a página encaixada,
        // senão uma parada anterior "entre duas páginas" seria repetida nas seguintes)
        val off = 0 // a página que entra fica com o topo (ou a borda esquerda) alinhado à tela, como no toque lateral
        if (isBook) {
            val d1 = v.left - off
            val d2 = v.right - off
            val dx = if (abs(d1) <= abs(d2)) d1 else d2
            if (dx != 0) list.smoothScrollBy(dx, 0)
        } else {
            // com zoom (Original/quadrinhos) o canto superior da página vai ao canto superior da tela:
            // topo em y = 0 (sem a margem da lista) e rolagem lateral no começo
            val zoomed = !reflowMode && zoom > 1f
            val o = v.top - (if (zoomed) 0 else pageTopY()) // senão: mesmo alvo do toque lateral
            if (zoomed) hScroll.smoothScrollTo(0, 0)
            val target = if (zoomed) 0 else pageTopY()
            val nv = lm().findViewByPosition(first + 1)
            val d1 = o - off
            // topo da próxima página (com o espaço entre as páginas, que a altura sozinha não inclui)
            val d2 = if (nv != null) nv.top - target - off else o + v.height - off
            val dy = if (abs(d1) <= abs(d2)) d1 else d2
            if (dy != 0) list.smoothScrollBy(0, dy)
        }
    }

    /**
     * Botão "Centralizar" (modo Original, qualquer zoom e formato).
     * Zoom x1: encaixa a página (vertical: alinha ao topo; horizontal: alinha à tela).
     * Zoom maior: centraliza a página no meio da tela, na horizontal e na vertical.
     */
    private fun centerPage() {
        if (currentUri == null) return
        if (list.layoutManager == null || (list.adapter?.itemCount ?: 0) == 0) return
        hScroll.fling(0)
        // no Refluxo não há zoom lateral: só alinha a página à tela
        val z = if (reflowMode) 1f else zoom
        // sentido horizontal: com zoom, o meio da página; sem zoom não há deslocamento lateral
        val cx = if (z > 1f) ((listWidth - hScroll.width) / 2).coerceAtLeast(0) else 0
        hScroll.smoothScrollTo(cx, 0)
        if (isBook) {
            if (z > 1f) {
                // sentido vertical: cada página tem a sua rolagem vertical; leva ao meio dela
                val sv = lm().findViewByPosition(currentPage()) as? ScrollView
                val child = sv?.getChildAt(0)
                if (sv != null && child != null) {
                    sv.smoothScrollTo(0, ((child.height - sv.height) / 2).coerceAtLeast(0))
                }
            } else {
                // zoom x1: alinha a página mais próxima à tela, como no fim de um deslize rápido
                val f = lm().findViewByPosition(lm().findFirstVisibleItemPosition()) ?: return
                val dx = if (-f.left > f.width / 2) f.right else f.left
                if (dx != 0) list.smoothScrollBy(dx, 0)
            }
        } else {
            // a página que está no meio da tela
            val v = list.findChildViewUnder(list.width / 2f, list.height / 2f) ?: return
            val top = list.paddingTop
            val viewportH = list.height - top - list.paddingBottom
            val dy = if (z > 1f && v.height > viewportH) {
                (v.top + v.height / 2) - (top + viewportH / 2) // centro da página no centro da tela
            } else {
                v.top - top // alinha ao topo da página
            }
            if (dy != 0) list.smoothScrollBy(0, dy)
        }
    }

    private fun showSettings() {
        if (currentUri == null) {
            Toast.makeText(this, "Abra um arquivo para configurar.", Toast.LENGTH_SHORT).show()
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
            if (c) {
                saveDocSettings()
                saveCenter() // o zoom lembrado leva junto a centralização atual
            }
        })
        if (!docIsComic) box.addView(row("Lembrar modo", keepMode) { c ->
            keepMode = c
            prefs.edit().putBoolean(dk("remM"), c).apply()
            if (c) saveDocSettings()
        })
        box.addView(row("Lembrar deslizamento", keepLayout) { c ->
            keepLayout = c
            prefs.edit().putBoolean(dk("remL"), c).apply()
            if (c) saveDocSettings()
        })
        box.addView(row("Lembrar Dia/Noite", keepNight) { c ->
            keepNight = c
            prefs.edit().putBoolean(dk("remN"), c).apply()
            if (c) saveDocSettings()
        })
        box.addView(row("Lembrar rotação", keepRot) { c ->
            keepRot = c
            prefs.edit().putBoolean(dk("remR"), c).apply()
            if (c) saveDocSettings()
        })
        box.addView(TextView(this).apply {
            text = "Lembrar neste arquivo: Com \"Sim\", ao abrir este arquivo de novo ele volta como você " +
                "deixou. Com \"Não\", ele começa no padrão: primeira página, zoom x1, texto no tamanho " +
                "padrão e centralizado, modo Original (nos PDFs), deslizamento na horizontal, rotação 0° e modo Dia. " +
                "Arquivos novos sempre começam com tudo em \"Não\"."
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dp(8), 0, dp(4))
        })
        AlertDialog.Builder(this)
            .setTitle("Neste arquivo:")
            .setView(box)
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun toggleMode() {
        if (docIsDocx || docIsEpub) {
            Toast.makeText(
                this, "${if (docIsComic) "Quadrinhos" else if (docIsDoc) "DOC" else if (docIsDocx) "DOCX" else "EPUB"} só tem o modo Refluxo.", Toast.LENGTH_SHORT
            ).show()
            return
        }
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
    private var comicFlingRun: Runnable? = null

    private fun cancelComicFling() {
        comicFlingRun?.let { list.removeCallbacks(it) }
        comicFlingRun = null
    }

    /**
     * Inércia vertical dos quadrinhos com zoom: usa o mesmo OverScroller do deslize lateral do Android,
     * com a mesma velocidade, então o deslize vertical corre igual ao horizontal.
     */
    private fun comicFling(vy: Int, limit: Int = Int.MAX_VALUE) {
        cancelComicFling()
        // o Android deixa a lista "arrastando" quando o deslize é tratado aqui; sem isso o estado
        // nunca voltava a parado e o novo arrasto não recalculava a página âncora da trava
        list.stopScroll()
        val sc = android.widget.OverScroller(this)
        sc.fling(0, 0, 0, vy, 0, 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
        var last = 0
        val run = object : Runnable {
            override fun run() {
                if (!sc.computeScrollOffset()) {
                    comicFlingRun = null
                    comicFlingEnded(limit)
                    return
                }
                var y = sc.currY
                val capped = abs(y) >= limit
                if (capped) y = if (y < 0) -limit else limit
                val d = y - last
                last = y
                if (d != 0) {
                    val before = list.computeVerticalScrollOffset()
                    list.scrollBy(0, d)
                    if (list.computeVerticalScrollOffset() == before) {
                        comicFlingRun = null
                        comicFlingEnded(limit)
                        return
                    }
                }
                if (capped) {
                    comicFlingRun = null
                    return
                }
                list.postOnAnimation(this)
            }
        }
        comicFlingRun = run
        list.postOnAnimation(run)
    }

    /** Fim da corrida dos quadrinhos com zoom (sem a trava): se passou de página, encaixa o topo da página na tela. */
    private fun comicFlingEnded(limit: Int) {
        if (limit != Int.MAX_VALUE || isBook) return
        if (originPos >= 0 && lm().findFirstVisibleItemPosition() != originPos) snapToNearestPage()
    }

    private fun applySwipeLock() {
        (list.layoutManager as? PagerLayoutManager)?.swipeLock = swipeLock && docIsComic
    }

    private fun applyMode() {
        applyWidth()
        val orientation = if (isBook) LinearLayoutManager.HORIZONTAL else LinearLayoutManager.VERTICAL
        list.layoutManager = PagerLayoutManager(this, orientation)
        applySwipeLock()
        (hScroll as? FixedWidthScroll)?.verticalBias = !isBook
        // desliga o snap antes de refazer: se ele já estava ligado a esta lista, um simples
        // "anexar de novo" não reinstala o ouvinte de fling e o deslize passaria a correr solto
        snapHelper.attachToRecyclerView(null)
        list.onFlingListener = null
        // o deslize corre solto, mais longe que o padrão do Android, e passa por várias páginas:
        // vertical em qualquer zoom; horizontal só no Refluxo ou no Original com zoom x1
        // (horizontal com zoom: sem inércia; a troca é pelo toque nas bordas ou arrastando além do canto)
        list.onFlingListener = object : RecyclerView.OnFlingListener() {
            override fun onFling(velocityX: Int, velocityY: Int): Boolean {
                if (scaling || pinchedRecently || selDragging) return true
                if (swipeLock && docIsComic) {
                    // trava ligada: o deslize rápido leva a página até a borda, sem passar para outra
                    if (!isBook) {
                        val dy = (list.layoutManager as? PagerLayoutManager)?.edgeDelta(velocityY > 0) ?: 0
                        if (dy != 0) comicFling(velocityY, abs(dy)) // mesma inércia nativa, parando na borda
                    }
                    return true
                }
                // quadrinhos com zoom, formato vertical: inércia nativa, igual à do deslize lateral
                if (docIsComic && !isBook && zoom > 1f) {
                    comicFling(velocityY)
                    return true
                }
                flingSeen = true
                // movimento curto não troca de página: o deslize rápido só vale se o dedo andou o bastante
                if (!(isBook && !reflowMode && zoom > 1f) && abs(dragAcc) < swipeNeeded()) {
                    list.stopScroll()
                    if (isBook) returnToOrigin()
                    return true
                }
                val v = if (isBook) velocityX else velocityY
                if (isBook && !reflowMode && zoom > 1f) return true
                val dist = (v * VERTICAL_FLING_SECONDS).toInt()
                if (dist == 0) return true
                val ms = (600f + abs(v) * 0.15f).coerceAtMost(2500f).toInt()
                val interp = android.view.animation.DecelerateInterpolator(2f)
                snapAfterFling = true
                if (isBook) list.smoothScrollBy(dist, 0, interp, ms)
                else list.smoothScrollBy(0, dist, interp, ms)
                return true
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
        listWidth = (resources.displayMetrics.widthPixels * (if (reflowMode) 1f else maxOf(zoom, 1f))).toInt()
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
        styleOpenBtn()
        if (swipeLock && docIsComic) { // trava acionada: botão em destaque
            swipeBtn.setBackgroundColor(Color.parseColor("#B5541C"))
            swipeBtn.setTextColor(Color.WHITE)
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

    @Volatile private var reflowFocus = 0 // página em que o usuário está (prioridade do processamento)
    @Volatile private var focusLock = -1 // durante um salto: processa em volta da página de destino

    /** Mostra/esconde o indicador de carregamento: só com o menu visível e enquanto não chegou a 100%. */
    private fun refreshLoadIndicator() {
        val pct = loadPct
        if (pct in 0..99 && barVisible) {
            loadIndicator.text = if (reflowMode && !docIsEpub && !docIsDocx && !docIsComic) "Carregando Refluxo $pct%" else "Carregando… $pct%"
            loadIndicator.visibility = View.VISIBLE
        } else {
            loadIndicator.visibility = View.GONE
        }
    }

    /** Atualiza o progresso do carregamento (pode ser chamado de qualquer thread). */
    private fun setLoad(token: Int, pct: Int) {
        main.post {
            if (token != loadToken) return@post
            val v = pct.coerceAtMost(100)
            if (v == loadPct || (v < loadPct && v >= 0)) return@post // o indicador nunca volta atrás
            loadPct = v
            refreshLoadIndicator()
        }
    }

    /** Copia o arquivo escolhido para [dest], informando o progresso entre [lo] e [hi] por cento. */
    private fun copyUri(uri: Uri, dest: File, token: Int, lo: Int, hi: Int) {
        val total = try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (_: Throwable) { -1L }
        val ins = contentResolver.openInputStream(uri) ?: throw IllegalStateException("Não foi possível ler o arquivo")
        ins.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    if (token != loadToken) throw java.util.concurrent.CancellationException()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = lo + ((hi - lo) * done / total).toInt()
                        if (pct != last) { last = pct; setLoad(token, pct) }
                    }
                }
            }
        }
        setLoad(token, hi)
    }

    private fun updatePageIndicator() {
        refreshLoadIndicator()
        schedulePageSave()
        if (reflowMode && !pagesComplete && focusLock < 0 && (list.adapter?.itemCount ?: 0) > 0) reflowFocus = pageToSave()
        val n = list.adapter?.itemCount ?: 0
        if (n == 0 || !barVisible) {
            pageIndicator.visibility = View.GONE
            return
        }
        val p = (currentPage() + 1).coerceIn(1, n)
        // o nível de zoom aparece antes: "x2 Página 7 de 212". No Refluxo o nível é o tamanho do texto
        // em relação ao padrão (A+ / A− / pinça); no tamanho padrão (x1) não aparece
        val level = if (reflowMode) textSp / TEXT_DEFAULT else zoom
        val zoomTxt = if (kotlin.math.abs(level - 1f) > 0.005f) {
            "x" + String.format(java.util.Locale.US, "%.2f", level).trimEnd('0').trimEnd('.') + " "
        } else ""
        val line1 = "${zoomTxt}Página $p de $n"
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
            // formato vertical: o começo da página no mesmo ponto das trocas de página
            lm().scrollToPositionWithOffset(p, if (isBook) 0 else pageTopY() - list.paddingTop)
        }
    }

    /**
     * Página de destino no formato vertical, contando a partir da PRIMEIRA página visível no topo
     * (e não da que está no meio da tela, que com páginas curtas era outra e fazia pular ou não voltar).
     * Para trás, se o começo dela já passou do topo, o primeiro passo volta ao começo dela.
     */
    private fun verticalTarget(delta: Int, count: Int): Int {
        val first = lm().findFirstVisibleItemPosition().coerceAtLeast(0)
        val v = lm().findViewByPosition(first)
        val partial = v != null && v.top < pageTopY() - dp(8)
        val t = when {
            delta > 0 -> first + delta
            partial -> first + delta + 1
            else -> first + delta
        }
        return t.coerceIn(0, count - 1)
    }

    /**
     * Vai para a página [t] e confere em seguida: durante o processamento do Refluxo a lista é
     * atualizada várias vezes e o pedido de rolagem podia se perder (o toque lateral falhava).
     */
    /**
     * Onde o começo da página fica ao trocar de página no formato vertical: no Refluxo, no topo da tela
     * (a lista tem uma margem de 30 dp acima, que deixava aparecer o fim da página anterior).
     */
    private fun pageTopY(): Int = if (reflowMode) 0 else list.paddingTop

    private fun turnTo(t: Int) {
        val off = if (isBook) 0 else pageTopY() - list.paddingTop
        lm().scrollToPositionWithOffset(t, off)
        // confere uma vez: se a lista ficou parada fora da página, repete o pedido
        list.postDelayed({
            if ((list.adapter?.itemCount ?: 0) > t &&
                list.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                lm().findFirstVisibleItemPosition() != t
            ) lm().scrollToPositionWithOffset(t, off)
        }, 80)
    }

    /**
     * Quadrinhos com zoom, formato vertical: a página que entra pelo toque lateral aparece na mesma
     * posição relativa (topo, meio, base…) da que saiu.
     */
    private fun comicStepVertical(delta: Int, count: Int) {
        // referência: o topo da tela (y = 0), o mesmo alvo do encaixe após o deslize; a posição relativa
        // dentro da página (0 = topo ... 1 = base) é levada para a página que entra
        val vpH = list.height
        val first = lm().findFirstVisibleItemPosition().coerceAtLeast(0)
        val fv = lm().findViewByPosition(first)
        val cur = (if (fv != null && fv.bottom < list.height * 0.25f) first + 1 else first).coerceIn(0, count - 1)
        val cv = lm().findViewByPosition(cur)
        val range = if (cv != null) cv.height - vpH else 0
        val frac = if (cv != null && range > 0) ((0 - cv.top).toFloat() / range).coerceIn(0f, 1f) else 0f
        val t = (cur + delta).coerceIn(0, count - 1)
        if (t == cur) return
        val tv = lm().findViewByPosition(t)
        // a página vizinha costuma estar fora da tela (páginas altas com zoom): então não está na lista ainda.
        // Calcula onde ela ficaria: altura pela proporção da imagem e o espaço de 6 dp entre as páginas
        val sz = sizes.getOrNull(t)
        val estH = if (sz != null && sz[0] > 0) (listWidth.toLong() * sz[1] / sz[0]).toInt() else 0
        val gap = dp(6)
        val tvTop: Int
        val tvH: Int
        if (tv != null) {
            tvTop = tv.top
            tvH = tv.height
        } else if (cv != null && estH > 0) {
            tvH = estH
            tvTop = if (t > cur) cv.top + cv.height + gap else cv.top - gap - estH
        } else {
            turnTo(t)
            return
        }
        val newRange = tvH - vpH
        val newOff = if (newRange > 0) (frac * newRange).toInt() else 0
        snapAfterFling = false
        alignTarget = -1
        list.smoothScrollBy(0, tvTop + newOff, null, 140)
        list.postDelayed({
            if (list.scrollState != RecyclerView.SCROLL_STATE_IDLE) return@postDelayed
            val v2 = lm().findViewByPosition(t)
            if (v2 == null) {
                lm().scrollToPositionWithOffset(t, -newOff - list.paddingTop)
            } else {
                val d = v2.top + newOff
                if (abs(d) > 1) list.scrollBy(0, d)
            }
        }, 450)
    }

    /** Quadrinhos com zoom, formato horizontal: a página nova entra com a mesma rolagem vertical relativa. */
    private fun keepBookVerticalPos(cur: Int, t: Int) {
        val sv = lm().findViewByPosition(cur) as? ScrollView ?: return
        val child = sv.getChildAt(0) ?: return
        val range = child.height - sv.height
        val frac = if (range > 0) (sv.scrollY.toFloat() / range).coerceIn(0f, 1f) else 0f
        val apply = Runnable {
            val nv = lm().findViewByPosition(t) as? ScrollView
            val c = nv?.getChildAt(0)
            if (nv != null && c != null) {
                val r = c.height - nv.height
                nv.scrollTo(0, if (r > 0) (frac * r).toInt() else 0)
            }
        }
        for (d in longArrayOf(20, 80, 200, 400, 700)) list.postDelayed(apply, d)
    }

    /** Toque na borda: vai para a página seguinte (+1) ou anterior (-1), em qualquer modo e formato. */
    private fun pageStep(delta: Int) {
        val count = list.adapter?.itemCount ?: 0
        if (count == 0) return
        if (!isBook && !reflowMode && zoom > 1f) { // Original (PDF e quadrinhos) com zoom
            comicStepVertical(delta, count)
            return
        }
        if (!isBook) {
            val first = lm().findFirstVisibleItemPosition().coerceAtLeast(0)
            val t = verticalTarget(delta, count)
            if (t == first && delta > 0) return
            // a nova página mantém a posição lateral (zoom e centralização) da anterior
            val tv = lm().findViewByPosition(t)
            // no Refluxo vertical, enquanto o texto ainda está sendo processado, as páginas mudam de
            // altura o tempo todo: aí o salto direto (que funciona) vale mais que o deslizamento
            val loadingVertical = reflowMode && !pagesComplete
            if (loadingVertical) {
                glideToPage(t)
            } else if (tv != null) {
                slideBy(0, tv.top - pageTopY(), t, false)
            } else {
                turnTo(t)
            }
            return
        }
        // página atual = a que ocupa mais da tela (não depende do auxiliar de encaixe)
        var cur = lm().findFirstVisibleItemPosition().coerceAtLeast(0)
        val fv = lm().findViewByPosition(cur)
        if (fv != null && fv.right < list.width / 2) cur++
        cur = cur.coerceIn(0, count - 1)
        val t = (cur + delta).coerceIn(0, count - 1)
        if (t == cur) return
        if ((docIsComic || !reflowMode) && zoom > 1f) keepBookVerticalPos(cur, t)
        val first = lm().findFirstVisibleItemPosition()
        val v0 = lm().findViewByPosition(first)
        if (v0 != null) {
            slideBy(v0.left + (t - first) * list.width, 0, t, true)
        } else {
            turnTo(t)
        }
        // modo original: mantém a centralização (deslocamento horizontal) ajustada no zoom;
        // só no refluxo volta ao início
        if (isBook && reflowMode) hScroll.scrollTo(0, 0)
    }

    /**
     * Troca de página pelo toque: um rápido deslizamento (a mesma rolagem do deslize, que não se perde
     * quando a lista está sendo atualizada) até a página [t]; se não chegar, salta direto.
     */
    private fun slideBy(dx: Int, dy: Int, t: Int, snap: Boolean) {
        if (dx == 0 && dy == 0) return
        snapAfterFling = snap
        alignTarget = t
        // no Refluxo horizontal a página que entra começa no topo (a rolagem interna da página é zerada)
        if (reflowMode && isBook) {
            main.removeCallbacks(resetTopOff)
            resetPageTop = true
            main.postDelayed(resetTopOff, 700)
        }
        list.smoothScrollBy(dx, dy, null, 140)
        list.postDelayed({
            if (alignTarget == t && list.scrollState == RecyclerView.SCROLL_STATE_IDLE) alignToPage(t)
        }, 450)
    }

    /**
     * Rolagem um pouco mais lenta até a página [t] (Refluxo vertical em processamento): o deslizador
     * recalcula a distância a cada quadro, então acompanha as páginas que mudam de altura enquanto o
     * texto é processado. Ao parar, o alinhamento exato é conferido (alignToPage).
     */
    private var glideSeq = 0

    // Durante um salto, as atualizações da lista (páginas que acabaram de ser processadas) ficam em
    // espera: com a lista mudando o tempo todo a rolagem não estabilizava na página de destino.
    private var uiPaused = false
    private val pausedChanges = LinkedHashSet<Int>()

    private fun notifyPage(i: Int) {
        if (uiPaused) pausedChanges.add(i) else reflowAdapter.notifyItemChanged(i)
    }

    private fun resumeUi() {
        if (!uiPaused) return
        uiPaused = false
        val l = ArrayList(pausedChanges)
        pausedChanges.clear()
        for (i in l) if (i < pages.size) reflowAdapter.notifyItemChanged(i)
    }

    /** Fim do salto: libera o processamento e as atualizações, e confere o alinhamento final. */
    /**
     * O texto selecionável de uma página antiga mantém o foco; a cada atualização da lista o
     * LinearLayoutManager se ancora nessa página e devolve a lista ao ponto de partida. Tira o foco.
     */
    private fun dropFocus() {
        val old = list.descendantFocusability
        list.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        list.focusedChild?.clearFocus()
        list.clearFocus()
        list.descendantFocusability = old
    }

    private fun endGlide(t: Int) {
        focusLock = -1
        resumeUi()
        list.post { alignToPage(t) }
    }

    /**
     * Confere a chegada à página [t] após a rolagem rápida: se ela parou antes (a lista é atualizada o
     * tempo todo durante o processamento e podia interromper a rolagem), repete o pedido.
     */
    private fun watchGlide(seq: Int, t: Int, fast: Boolean, retries: Int, waits: Int = 16) {
        list.postDelayed({
            if (seq != glideSeq) return@postDelayed // outro salto, ou o usuário tocou na tela
            if (list.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                if (waits > 0) watchGlide(seq, t, fast, retries, waits - 1)
                else {
                    focusLock = -1
                    resumeUi()
                }
                return@postDelayed
            }
            if (lm().findFirstVisibleItemPosition() == t) {
                endGlide(t)
            } else if (retries > 0) {
                glideToPage(t, fast, retries - 1)
            } else {
                endGlide(t)
            }
        }, 400)
    }

    /**
     * Salto para uma página distante durante o processamento do Refluxo (link do índice, botão da
     * página, botão voltar). Em vez da rolagem do Android (que anda em blocos de 10.000 px e podia
     * passar direto pela página, indo e voltando conforme a altura das páginas no caminho), faz o
     * salto direto e o repete até a lista ficar na página: as atualizações da lista ficam em espera
     * e o processamento se concentra no destino até lá.
     */
    private fun jumpToPage(t: Int, tries: Int = 8) {
        val seq = ++glideSeq
        focusLock = t
        reflowFocus = t
        uiPaused = true
        if (reflowMode && isBook) {
            main.removeCallbacks(resetTopOff)
            resetPageTop = true
            main.postDelayed(resetTopOff, 2000)
        }
        applyJump(t)
        watchJump(seq, t, tries)
    }

    private fun applyJump(t: Int) {
        if ((list.adapter?.itemCount ?: 0) <= t) return
        dropFocus()
        lm().scrollToPositionWithOffset(t, if (isBook) 0 else pageTopY() - list.paddingTop)
        if (reflowMode && isBook) hScroll.scrollTo(0, 0)
    }

    private fun watchJump(seq: Int, t: Int, tries: Int, waits: Int = 20) {
        list.postDelayed({
            if (seq != glideSeq) {
                return@postDelayed // outro salto, ou o usuário tocou na tela
            }
            if (list.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                if (waits > 0) watchJump(seq, t, tries, waits - 1) else endGlide(t)
                return@postDelayed
            }
            val tv = lm().findViewByPosition(t)
            val ok = lm().findFirstVisibleItemPosition() == t && tv != null &&
                (if (isBook) abs(tv.left) <= 1 else abs(tv.top - pageTopY()) <= 1)
            if (ok || tries <= 0) {
                endGlide(t)
            } else {
                applyJump(t)
                watchJump(seq, t, tries - 1)
            }
        }, 120)
    }

    private fun glideToPage(t: Int, fast: Boolean = false, retries: Int = 3) {
        val seq = ++glideSeq
        if (reflowMode && !pagesComplete) {
            // as páginas por onde a rolagem passa NÃO viram prioridade (isso mudava a altura delas no
            // meio do caminho e a rolagem não estabilizava): o processamento se concentra no destino
            focusLock = t
            reflowFocus = t
            uiPaused = true
        }
        if (reflowMode && isBook) {
            // a página que entra começa no topo (zera a rolagem interna dela ao aparecer)
            main.removeCallbacks(resetTopOff)
            resetPageTop = true
            main.postDelayed(resetTopOff, if (fast) 2000 else 700)
        }
        val sc = object : LinearSmoothScroller(this) {
            override fun getVerticalSnapPreference() = SNAP_TO_START
            override fun getHorizontalSnapPreference() = SNAP_TO_START
            override fun calculateDyToMakeVisible(view: View, snapPreference: Int): Int {
                val l = layoutManager ?: return 0
                if (!l.canScrollVertically()) return 0
                val lp = view.layoutParams as RecyclerView.LayoutParams
                return pageTopY() - (l.getDecoratedTop(view) - lp.topMargin)
            }
            // salto para uma página distante: percorre rápido (a distância pode ser de dezenas de páginas)
            override fun calculateSpeedPerPixel(displayMetrics: android.util.DisplayMetrics) =
                if (fast) 0.002f else 0.12f
            override fun calculateTimeForDeceleration(dx: Int) = if (fast) 120 else 260
        }
        sc.targetPosition = t
        alignTarget = t
        lm().startSmoothScroll(sc)
        watchGlide(seq, t, fast, retries)
    }

    /** Página de destino de uma troca por toque: ao parar a rolagem, ajusta o alinhamento exato. */
    private var alignTarget = -1
    private var resetPageTop = false
    private val resetTopOff = Runnable { resetPageTop = false }

    /** Deixa o começo da página [t] exatamente no começo da tela (esquerda no horizontal, topo no vertical). */
    private fun alignToPage(t: Int) {
        alignTarget = -1
        if (t < 0 || (list.adapter?.itemCount ?: 0) <= t) return
        val tv = lm().findViewByPosition(t)
        if (tv == null) {
            lm().scrollToPositionWithOffset(t, if (isBook) 0 else pageTopY() - list.paddingTop)
            return
        }
        if (isBook) {
            if (reflowMode) (tv as? ScrollView)?.scrollTo(0, 0)
            if (tv.left != 0) list.scrollBy(tv.left, 0)
        } else {
            val d = tv.top - pageTopY()
            if (abs(d) > 1) list.scrollBy(0, d)
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
        // mantém a centralização (deslocamento horizontal) ajustada ao trocar de página
    }

    /** Página a lembrar: no vertical, a que está no topo da tela (como a restauração a coloca). */
    private fun pageToSave(): Int {
        if (isBook) return currentPage()
        val count = list.adapter?.itemCount ?: 0
        val first = lm().findFirstVisibleItemPosition().coerceAtLeast(0)
        val v = lm().findViewByPosition(first)
        // se quase toda a página do topo já saiu da tela, o que se lê é a seguinte
        val p = if (v != null && v.bottom < list.height * 0.25f) first + 1 else first
        return p.coerceIn(0, (count - 1).coerceAtLeast(0))
    }

    private val savePageRunnable = Runnable { savePage() }

    /** Guarda a página pouco depois de parar de mexer, para não depender de o app fechar direito. */
    private fun schedulePageSave() {
        if (!rememberPage) return
        main.removeCallbacks(savePageRunnable)
        main.postDelayed(savePageRunnable, 700)
    }

    private fun savePage(sync: Boolean = false) {
        if (!rememberPage || currentUri == null || pendingPage >= 0) return
        if ((list.adapter?.itemCount ?: 0) == 0 || list.layoutManager == null) return
        val e = getSharedPreferences("leitor", MODE_PRIVATE).edit().putInt(currentKey, pageToSave())
        if (sync) e.commit() else e.apply()
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
        val mime = try { contentResolver.getType(uri) } catch (_: Exception) { null }
        val lowName = (name ?: uri.lastPathSegment ?: "").lowercase()
        docIsComic = lowName.endsWith(".cbz") || lowName.endsWith(".cbr") || lowName.endsWith(".cb7") ||
            mime == "application/x-cb7" || mime == "application/x-7z-compressed" ||
            mime == "application/vnd.comicbook+zip" || mime == "application/vnd.comicbook-rar" ||
            mime == "application/x-cbz" || mime == "application/x-cbr"
        docIsEpub = !docIsComic && (mime == "application/epub+zip" ||
            (name ?: uri.lastPathSegment ?: "").lowercase().endsWith(".epub"))
        docIsDocx = mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
            (name ?: uri.lastPathSegment ?: "").lowercase().endsWith(".docx")
        docIsDoc = mime == "application/msword" ||
            (name ?: uri.lastPathSegment ?: "").lowercase().endsWith(".doc")
        if (docIsDoc) docIsDocx = true
        currentName = (name ?: uri.lastPathSegment ?: "").replace(Regex("(?i)\\.(pdf|epub|docx|doc|cbz|cbr|cb7)$"), "")
        // o nome do PDF aparece na lista de apps recentes, para distinguir as janelas
        try { setTaskDescription(android.app.ActivityManager.TaskDescription(currentName)) } catch (_: Throwable) {}
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
        if (reflowMode && !pagesComplete) {
            // durante o processamento do Refluxo o salto direto se perdia (a lista é atualizada o
            // tempo todo): usa a rolagem rápida até a página, que acompanha as mudanças de altura
            jumpToPage(n)
        } else {
            lm().scrollToPositionWithOffset(n, 0)
        }
        // modo Original: mantém a centralização ajustada ao seguir o link; no Refluxo volta ao início
        if (reflowMode) hScroll.scrollTo(0, 0)
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
        if (!hasPdf) {
            hasPdf = true
            windowsWithPdf++
        }
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
        swipeLock = false
        applySwipeLock()
        backStack.clear()
        // ajustes deste PDF: cada "Lembrar" começa em Não e só vale se foi marcado neste mesmo PDF
        val dp0 = getSharedPreferences("leitor", MODE_PRIVATE)
        rememberPage = dp0.getBoolean(dk("remP"), false)
        keepZoom = dp0.getBoolean(dk("remZ"), false)
        keepMode = dp0.getBoolean(dk("remM"), false)
        keepLayout = dp0.getBoolean(dk("remL"), false)
        keepNight = dp0.getBoolean(dk("remN"), false)
        keepRot = dp0.getBoolean(dk("remR"), false)
        rotation = if (keepRot) dp0.getInt(dk("rot"), 0).coerceIn(0, 3) else 0
        applyRotation()
        pendingPage = if (rememberPage) dp0.getInt(currentKey, -1) else -1
        if (pagesUri != uri) {
            pages.clear()
            reflowTargets.clear()
            pagesUri = null
            pagesComplete = false
        }
        hScroll.scrollTo(0, 0)
        origScrollX = if (keepZoom) dp0.getInt(dk("scrollX"), 0).coerceAtLeast(0) else 0
        // aplica os ajustes lembrados deste PDF (ou o padrão)
        val savedReflow = keepMode && dp0.getBoolean(dk("reflow"), false)
        val newReflow = !docIsComic && (docIsEpub || docIsDocx || savedReflow)
        val newLayout = if (keepLayout) dp0.getInt(dk("layout"), 1).coerceIn(0, 1) else 1
        val newZoom = if (keepZoom) dp0.getFloat(dk("zoom"), 1f).coerceIn(ZOOM_MIN, ZOOM_MAX) else 1f
        val newText = if (keepZoom) dp0.getFloat(dk("textSp"), TEXT_DEFAULT).coerceIn(TEXT_MIN, TEXT_MAX) else TEXT_DEFAULT
        val newNight = keepNight && dp0.getBoolean(dk("night"), false)
        val changed = newReflow != reflowMode || newLayout != pageLayout ||
            newZoom != zoom || newText != textSp || newNight != night
        reflowMode = newReflow
        night = newNight
        pageLayout = newLayout
        zoom = newZoom
        textSp = newText
        if (changed) {
            applyMode()
            applyTheme()
            updateLabels()
        }
        updateLabels()
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
        loadPct = -1
        refreshLoadIndicator()
        clearSelection()
        val t0 = SystemClock.uptimeMillis()
        closeRenderer()
        if (docIsComic) showComic(uri, token)
        else if (docIsEpub || docIsDocx) showEpub(uri, token)
        else if (reflowMode) showReflow(uri, token) else showOriginal(uri, token)
    }

    private fun showReflow(uri: Uri, token: Int) {
        if (pagesUri == uri && pagesComplete) {
            reflowAdapter.notifyDataSetChanged()
            status.text = "${pages.size} páginas · refluxo"
            loadPct = -1
            restorePending()
            pendingPage = -1
            updatePageIndicator()
            return
        }
        uiPaused = false
        pausedChanges.clear()
        focusLock = -1
        // voltou ao Refluxo antes do carregamento terminar: continua de onde parou (páginas já extraídas ficam)
        val resume = pagesUri == uri && pages.isNotEmpty() && !pagesComplete
        if (!resume) {
            pages.clear()
            reflowTargets.clear()
        }
        pagesUri = uri
        pagesComplete = false
        val seedPages: List<PageText> = if (resume) ArrayList(pages) else emptyList()
        val seedTargets: List<LinkTarget> = if (resume) ArrayList(reflowTargets) else emptyList()
        reflowAdapter.notifyDataSetChanged()
        status.text = "Abrindo…"
        if (resume) {
            val ld = seedPages.count { it.loaded }
            loadPct = if (seedPages.isNotEmpty()) (100L * ld / seedPages.size).toInt().coerceAtMost(99) else -1
            restorePending()
            updatePageIndicator()
        }
        // veio do modo Original: o número de páginas já é conhecido, então as páginas aparecem na hora
        val knownCount = if (!resume && !docIsComic && sizesUri == uri) sizes.size else 0
        if (knownCount > 0) {
            for (k in 0 until knownCount) pages.add(PageText("", emptyList(), false))
            reflowAdapter.notifyDataSetChanged()
            status.text = "Carregando… 0 de $knownCount páginas"
            restorePending()
            updatePageIndicator()
        }
        val cacheFile = reflowCacheFile(currentKey)
        val startHint = pendingPage // página em que você está: é extraída primeiro

        Thread {
            android.os.Process.setThreadPriority(9) // a tela tem prioridade sobre o carregamento
            try {
                val tr0 = SystemClock.uptimeMillis()
                // 1) texto já extraído numa abertura anterior: abre na hora
                val cached = readReflowCache(cacheFile)
                if (cached != null) {
                    main.post {
                        if (token != loadToken) return@post
                        pages.clear()
                        reflowTargets.clear()
                        reflowTargets.addAll(cached.targets)
                        pages.addAll(cached.pages)
                        reflowAdapter.notifyDataSetChanged()
                        status.text = "${pages.size} páginas · refluxo"
                        pagesComplete = true
                        loadPct = -1
                        restorePending()
                        pendingPage = -1
                        updatePageIndicator()
                    }
                    return@Thread
                }

                // 2) extração: começa pela página atual e depois completa o resto
                // (com o documento já aberto, não há cópia: a extração usa a barra inteira)
                val base = if (synchronized(docLock) { sharedDoc != null && sharedDocUri == uri }) 0 else 30
                if (seedPages.isEmpty()) setLoad(token, 0)
                val stripper = LinkStripper().apply { sortByPosition = true }
                val targets = ArrayList<LinkTarget>(seedTargets)
                // abre já: o número de páginas vem do leitor nativo (rápido), enquanto o arquivo é copiado e analisado
                var early = -1
                if (seedPages.isEmpty() && knownCount <= 0) try {
                    contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                        val r = PdfRenderer(fd)
                        early = r.pageCount
                        r.close()
                    }
                } catch (_: Throwable) {
                    early = -1
                }
                if (early > 0) {
                    main.post {
                        if (token != loadToken) return@post
                        if (pages.isEmpty()) {
                            for (k in 0 until early) pages.add(PageText("", emptyList(), false))
                            reflowAdapter.notifyDataSetChanged()
                            status.text = "Carregando… 0 de $early páginas"
                            restorePending()
                            updatePageIndicator()
                        }
                    }
                }
                val te0 = SystemClock.uptimeMillis()
                val total = synchronized(docLock) { ensureDoc(uri, token).numberOfPages }
                val start = if (startHint in 0 until total) startHint else 0
                main.post {
                    if (token != loadToken) return@post
                    if (pages.size != total) {
                        pages.clear()
                        for (k in 0 until total) pages.add(PageText("", emptyList(), false))
                        reflowAdapter.notifyDataSetChanged()
                        restorePending()
                    }
                    status.text = "Carregando… 0 de $total páginas"
                    updatePageIndicator()
                }
                val local = arrayOfNulls<PageText>(total)
                var withText = 0
                var done = 0
                // ordem de extração: sempre a página mais próxima de onde você está agora (para a frente
                // e para trás), acompanhando as trocas de página enquanto o Refluxo é processado
                reflowFocus = start
                val finished = BooleanArray(total)
                // continuação: páginas já extraídas antes da troca de modo não são refeitas
                for (i in 0 until minOf(seedPages.size, total)) {
                    val sp = seedPages[i]
                    if (sp.loaded) {
                        local[i] = sp
                        finished[i] = true
                        done++
                        if (sp.text.isNotBlank()) withText++
                    }
                }
                if (done >= total) {
                    main.post {
                        if (token != loadToken) return@post
                        pagesComplete = true
                        pendingPage = -1
                        status.text = "$total páginas · refluxo"
                        loadPct = -1
                        refreshLoadIndicator()
                        resumeUi()
                    }
                }
                // extração em paralelo: dois trabalhadores, cada um com sua própria cópia aberta do PDF
                // (o documento do PDFBox não aceita uso simultâneo). Os links são lidos um a um no
                // documento compartilhado, para os números dos links continuarem em ordem.
                val doneA = java.util.concurrent.atomic.AtomicInteger(done)
                val withTextA = java.util.concurrent.atomic.AtomicInteger(withText)
                val claimLock = Any()
                val pdfFile = File(cacheDir, "atual.pdf")
                val runWorker: (Int) -> Unit = worker@{ wid ->
                    var myDoc: PDDocument? = null
                    try {
                        try {
                            myDoc = PDDocument.load(pdfFile)
                        } catch (t: Throwable) {
                            myDoc = null
                            if (wid != 0) return@worker
                        }
                        val myStripper = LinkStripper().apply { sortByPosition = true }
                        var sinceOpen = 0
                        while (true) {
                            if (token != loadToken) return@worker
                            // o PDFBox guarda na memória tudo o que já leu: reabre a cópia de tempos em tempos
                            // para liberar a memória (com a memória cheia a extração fica várias vezes mais lenta)
                            if (myDoc != null && sinceOpen >= 60) {
                                try { myDoc?.close() } catch (_: Throwable) {}
                                myDoc = null
                                myDoc = try { PDDocument.load(pdfFile) } catch (_: Throwable) { null }
                                sinceOpen = 0
                                if (myDoc == null && wid != 0) return@worker
                            }
                            var i = -1
                            var links: List<PageLink> = emptyList()
                            synchronized(claimLock) {
                                val f = reflowFocus.coerceIn(0, total - 1)
                                var sel = -1
                                var dist = 0
                                while (sel < 0 && dist < total) {
                                    if (f + dist < total && !finished[f + dist]) sel = f + dist
                                    else if (f - dist >= 0 && !finished[f - dist]) sel = f - dist
                                    dist++
                                }
                                if (sel >= 0) {
                                    finished[sel] = true
                                    i = sel
                                    val before = targets.size
                                    links = synchronized(docLock) {
                                        val doc = ensureDoc(uri, token)
                                        try {
                                            extractLinks(doc, doc.getPage(sel), targets)
                                        } catch (e: Exception) {
                                            emptyList()
                                        }
                                    }
                                    val added = ArrayList(targets.subList(before, targets.size))
                                    if (added.isNotEmpty()) main.post {
                                        if (token == loadToken) reflowTargets.addAll(added)
                                    }
                                }
                            }
                            if (i < 0) break
                            val tp0 = SystemClock.uptimeMillis()
                            val raw: String = try {
                                val md = myDoc
                                if (md != null) {
                                    val page = md.getPage(i)
                                    myStripper.pageLinks = links
                                    myStripper.extractPageText(md, i + 1, pageWidth(page))
                                } else {
                                    synchronized(docLock) {
                                        val doc = ensureDoc(uri, token)
                                        val page = doc.getPage(i)
                                        myStripper.pageLinks = links
                                        myStripper.extractPageText(doc, i + 1, pageWidth(page))
                                    }
                                }
                            } catch (t: Throwable) {
                                synchronized(claimLock) { finished[i] = false }
                                if (wid != 0) return@worker
                                throw t
                            }
                            val tpText = SystemClock.uptimeMillis() - tp0
                            sinceOpen++
                            val res = Reflow.reflow(raw)
                            if (token != loadToken) return@worker
                            if (res.text.isNotBlank()) withTextA.incrementAndGet()
                            val pt = PageText(res.text, res.spans)
                            val d: Int
                            synchronized(claimLock) {
                                local[i] = pt
                                d = doneA.incrementAndGet()
                            }
                            val pi = i
                            main.post {
                                if (token != loadToken) return@post
                                if (pi < pages.size) {
                                    pages[pi] = pt
                                    notifyPage(pi)
                                }
                                status.text = if (d < total) "Carregando… $d de $total páginas"
                                else "$total páginas · refluxo"
                                val np = if (d >= total) -1 else base + ((100 - base) * d.toLong() / total).toInt()
                                if (d >= total || np > loadPct) loadPct = np
                                refreshLoadIndicator()
                                if (d >= total) resumeUi()
                                if (d == total) {
                                    pagesComplete = true
                                    pendingPage = -1
                                }
                            }
                            if (d == total) {
                                if (local.all { it != null }) {
                                    writeReflowCache(cacheFile, targets, local.map { it!! })
                                }
                                if (withTextA.get() == 0) {
                                    main.post {
                                        if (token != loadToken) return@post
                                        status.text =
                                            "Este PDF não tem texto (é só imagem). Use o modo Original ou faça o OCR."
                                    }
                                }
                            }
                        }
                    } finally {
                        try { myDoc?.close() } catch (_: Throwable) {}
                    }
                }
                if (done < total) {
                    Thread {
                        android.os.Process.setThreadPriority(9)
                        try { runWorker(1) } catch (_: Throwable) {}
                    }.start()
                    runWorker(0)
                }
            } catch (e: Exception) {
                main.post {
                    if (token != loadToken) return@post
                    status.text = "Não foi possível abrir o PDF."
                    loadPct = -1
                    refreshLoadIndicator()
                    Toast.makeText(this@MainActivity, "Não foi possível abrir o PDF.", Toast.LENGTH_LONG).show()
                    Toast.makeText(this, e.message ?: "Erro", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** EPUB: lê o livro inteiro de uma vez (é rápido) e mostra no mesmo Refluxo do PDF. */
    private fun showEpub(uri: Uri, token: Int) {
        if (pagesUri == uri && pagesComplete) {
            reflowAdapter.notifyDataSetChanged()
            status.text = "${pages.size} páginas · ${if (docIsComic) "quadrinhos" else if (docIsDoc) "DOC" else if (docIsDocx) "DOCX" else "EPUB"}"
            loadPct = -1
            restorePending()
            pendingPage = -1
            updatePageIndicator()
            return
        }
        uiPaused = false
        pausedChanges.clear()
        focusLock = -1
        pages.clear()
        reflowTargets.clear()
        pagesUri = uri
        pagesComplete = false
        reflowAdapter.notifyDataSetChanged()
        status.text = "Abrindo…"
        Thread {
            try {
                // copia o EPUB para um arquivo: o zip precisa de acesso direto para ler as figuras
                setLoad(token, 0)
                val old = epubZip
                epubZip = null
                try { old?.close() } catch (_: Throwable) {}
                val f = File(cacheDir, "epub_${System.identityHashCode(this@MainActivity)}.epub")
                copyUri(uri, f, token, 0, 60)
                epubFile = f
                var zipSrc = f
                val book = if (docIsComic) {
                    val ld = ComicBook.load(f, File(cacheDir, "comic_${System.identityHashCode(this@MainActivity)}.cbz"))
                    zipSrc = ld.zip
                    ld.result
                } else if (docIsDoc) DocBook.load(f) else if (docIsDocx) DocxBook.load(f) else EpubBook.load(f)
                epubZip = try { java.util.zip.ZipFile(zipSrc) } catch (_: Throwable) { null } // .doc não é zip
                main.post {
                    if (token != loadToken) return@post
                    epubBmp.evictAll()
                    reflowTargets.addAll(book.targets)
                    for (p in book.pages) pages.add(PageText(p.text, p.spans, true, p.image, p.fmts))
                    reflowAdapter.notifyDataSetChanged()
                    status.text = "${pages.size} páginas · ${if (docIsComic) "quadrinhos" else if (docIsDoc) "DOC" else if (docIsDocx) "DOCX" else "EPUB"}"
                    pagesComplete = true
                    loadPct = -1
                    restorePending()
                    pendingPage = -1
                    updatePageIndicator()
                }
            } catch (e: Throwable) {
                main.post {
                    if (token != loadToken) return@post
                    loadPct = -1
                    refreshLoadIndicator()
                    status.text = "Não foi possível abrir o arquivo."
                    Toast.makeText(this@MainActivity, "Não foi possível abrir o arquivo: ${e.message ?: "erro"}", Toast.LENGTH_LONG).show()
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
        // medição interrompida numa saída anterior do modo Original: continua de onde parou
        val snapSame = scanUri == uri
        val snapArr: List<IntArray> = if (snapSame) ArrayList(scanArr) else emptyList()
        val snapNext = if (snapSame) scanNext else 1

        Thread {
            android.os.Process.setThreadPriority(9) // a tela tem prioridade sobre a medição das páginas
            var fd: ParcelFileDescriptor? = null
            var pr: PdfRenderer? = null
            try {
                val to0 = SystemClock.uptimeMillis()
                val f = contentResolver.openFileDescriptor(uri, "r")!!
                fd = f
                val r = PdfRenderer(f)
                pr = r
                val count = r.pageCount
                if (count == 0) throw IllegalStateException("PDF sem páginas")

                val cachedSizes = if (origScanUri == uri) origScanSizes else null
                if (cachedSizes != null && cachedSizes.size == count) {
                    // este PDF já foi todo medido numa abertura anterior: abre direto, sem novo carregamento
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
                        sizes.addAll(cachedSizes)
                        sizesUri = uri
                        imageAdapter.notifyDataSetChanged()
                        status.text = "$count páginas · original"
                        loadPct = -1
                        restorePending()
                        pendingPage = -1
                        updatePageIndicator()
                    }
                    return@Thread
                }

                // só a primeira página é medida agora: as demais começam com o mesmo tamanho
                // e são corrigidas em segundo plano (assim a leitura começa na hora)
                val resumeOk = snapArr.size == count && snapNext > 1
                val first: IntArray
                if (resumeOk) first = snapArr[0] else {
                    val p0 = r.openPage(0)
                    first = intArrayOf(p0.width, p0.height)
                    p0.close()
                }
                val startAt = if (resumeOk) snapNext else 1

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
                    if (resumeOk) sizes.addAll(snapArr) else for (i in 0 until count) sizes.add(first)
                    if (!resumeOk) {
                        scanUri = uri
                        scanArr = ArrayList(sizes)
                        scanNext = 1
                    }
                    sizesUri = uri
                    imageAdapter.notifyDataSetChanged()
                    status.text = "$count páginas · original"
                    loadPct = if (count > 1) maxOf(1, (100L * (startAt - 1) / (count - 1)).toInt()) else -1
                    restorePending()
                    pendingPage = -1
                    updatePageIndicator()
                }

                // a medição usa um leitor próprio: assim não disputa a trava com o desenho das páginas
                var fd2: ParcelFileDescriptor? = null
                var r2: PdfRenderer? = null
                try {
                    fd2 = contentResolver.openFileDescriptor(uri, "r")
                    r2 = PdfRenderer(fd2!!)
                } catch (_: Throwable) {
                    try { r2?.close() } catch (_: Throwable) {}
                    try { fd2?.close() } catch (_: Throwable) {}
                    r2 = null
                    fd2 = null
                }
                try {
                    var diffs = ArrayList<IntArray>()
                    for (i in startAt until count) {
                        if (token != loadToken) return@Thread
                        var w = 0
                        var h = 0
                        val sr = r2
                        if (sr != null) {
                            val p = sr.openPage(i)
                            w = p.width
                            h = p.height
                            p.close()
                        } else synchronized(rendererLock) {
                            val p = r.openPage(i)
                            w = p.width
                            h = p.height
                            p.close()
                        }
                        if (w != first[0] || h != first[1]) diffs.add(intArrayOf(i, w, h))
                        if (i % 20 == 0 || i == count - 1) setLoad(token, (100L * i / (count - 1)).toInt())
                        if (i % 20 == 0 || i == count - 1) {
                            val batch = diffs
                            diffs = ArrayList()
                            val nextI = i + 1
                            main.post {
                                if (token != loadToken) return@post
                                for (d in batch) {
                                    if (d[0] < sizes.size) {
                                        sizes[d[0]] = intArrayOf(d[1], d[2])
                                        if (d[0] < scanArr.size) scanArr[d[0]] = sizes[d[0]]
                                        imageAdapter.notifyItemChanged(d[0])
                                    }
                                }
                                scanNext = nextI
                            }
                        }
                    }
                    main.post {
                        if (token != loadToken) return@post
                        origScanUri = uri
                        origScanSizes = ArrayList(sizes)
                        loadPct = -1
                        refreshLoadIndicator()
                    }
                } catch (_: Throwable) {
                } finally {
                    try { r2?.close() } catch (_: Throwable) {}
                    try { fd2?.close() } catch (_: Throwable) {}
                }
            } catch (e: Throwable) {
                try { pr?.close() } catch (_: Throwable) {}
                try { fd?.close() } catch (_: Throwable) {}
                main.post {
                    if (token != loadToken) return@post
                    status.text = "Não foi possível abrir o PDF no modo Original."
                    loadPct = -1
                    refreshLoadIndicator()
                    Toast.makeText(this@MainActivity, "Não foi possível abrir o PDF no modo Original.", Toast.LENGTH_LONG).show()
                    Toast.makeText(this, e.message ?: "Erro", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** CBZ/CBR: abre as imagens como páginas do visualizador do modo Original (zoom, pinça e arrasto iguais). */
    private fun showComic(uri: Uri, token: Int) {
        sizes.clear()
        imageAdapter.notifyDataSetChanged()
        infoRequested.clear()
        status.text = "Abrindo…"
        Thread {
            android.os.Process.setThreadPriority(9)
            try {
                setLoad(token, 0)
                val old = epubZip
                epubZip = null
                try { old?.close() } catch (_: Throwable) {}
                val f = File(cacheDir, "comic_src_${System.identityHashCode(this@MainActivity)}")
                copyUri(uri, f, token, 0, 40)
                epubFile = f
                val ld = ComicBook.load(f, File(cacheDir, "comic_${System.identityHashCode(this@MainActivity)}.cbz"))
                setLoad(token, 55)
                val z = java.util.zip.ZipFile(ld.zip, ld.charset)
                val names = ld.result.pages.map { it.image ?: "" }
                fun measure(n: String): IntArray {
                    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    try {
                        val e = z.getEntry(n)
                        if (e != null) z.getInputStream(e).use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
                    } catch (_: Throwable) {
                    }
                    return if (o.outWidth > 0 && o.outHeight > 0) intArrayOf(o.outWidth, o.outHeight) else intArrayOf(1000, 1500)
                }
                // só a primeira imagem é medida agora: as páginas aparecem na hora e o resto é corrigido em segundo plano
                val first = if (names.isNotEmpty()) measure(names[0]) else intArrayOf(1000, 1500)
                main.post {
                    if (token != loadToken) {
                        try { z.close() } catch (_: Throwable) {}
                        return@post
                    }
                    epubZip = z
                    comicNames = names
                    for (k in names.indices) sizes.add(first)
                    imageAdapter.notifyDataSetChanged()
                    status.text = "${names.size} páginas · quadrinhos"
                    loadPct = if (names.size > 1) 55 else -1
                    restorePending()
                    pendingPage = -1
                    updatePageIndicator()
                }
                var diffs = ArrayList<IntArray>()
                for (i in 1 until names.size) {
                    if (token != loadToken) return@Thread
                    val d = measure(names[i])
                    if (d[0] != first[0] || d[1] != first[1]) diffs.add(intArrayOf(i, d[0], d[1]))
                    if (i % 10 == 0 || i == names.size - 1) {
                        setLoad(token, 55 + (45L * i / (names.size - 1)).toInt())
                        val batch = diffs
                        diffs = ArrayList()
                        main.post {
                            if (token != loadToken) return@post
                            for (x in batch) {
                                if (x[0] < sizes.size) {
                                    sizes[x[0]] = intArrayOf(x[1], x[2])
                                    imageAdapter.notifyItemChanged(x[0])
                                }
                            }
                        }
                    }
                }
                main.post {
                    if (token != loadToken) return@post
                    loadPct = -1
                    refreshLoadIndicator()
                }
            } catch (e: Throwable) {
                main.post {
                    if (token != loadToken) return@post
                    loadPct = -1
                    refreshLoadIndicator()
                    status.text = "Não foi possível abrir o arquivo."
                    Toast.makeText(this@MainActivity, "Não foi possível abrir o arquivo: ${e.message ?: "erro"}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ documento compartilhado

    /**
     * Chame com [docLock]. Copia o PDF para o cache (leitura por arquivo é bem mais rápida
     * que ler tudo para a memória) e abre o documento uma única vez para texto, links e seleção.
     */
    private fun ensureDoc(uri: Uri, token: Int = -1): PDDocument {
        val d = sharedDoc
        if (d != null && sharedDocUri == uri) return d
        try { sharedDoc?.close() } catch (_: Throwable) {}
        sharedDoc = null
        sharedDocUri = null
        val f = File(cacheDir, "atual.pdf")
        if (token >= 0) copyUri(uri, f, token, 0, 30)
        else contentResolver.openInputStream(uri)!!.use { input ->
            f.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
        }
        val doc = PDDocument.load(f)
        sharedDoc = doc
        sharedDocUri = uri
        return doc
    }

    /** Lê os links de uma página do modo original, só quando ela aparece. */
    private fun requestLinks(pos: Int) {
        if (docIsComic) return
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
            Toast.makeText(this, "Abra um arquivo primeiro.", Toast.LENGTH_SHORT).show()
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
        // EPUB: o texto já está na memória (só as páginas já carregadas, que no EPUB são todas)
        val epubTexts: List<String>? =
            if (docIsEpub || docIsDocx) pages.map { it.text.replace(wsRegex, " ").trim() } else null
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
                val total = epubTexts?.size ?: synchronized(docLock) { ensureDoc(uri).numberOfPages }
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
                    if (epubTexts != null) {
                        txt = epubTexts[i]
                        plain = fold(txt)
                    } else if (i < snapshot.size) {
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
                    Toast.makeText(this, "Não foi possível buscar neste arquivo.", Toast.LENGTH_LONG).show()
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
                .setMessage("Nenhum resultado para “$query”." + if (docIsEpub || docIsDocx) "" else "\n\nPDFs só de imagem não têm texto para buscar.")
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
        if (docIsComic) return
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

    // ---- nitidez com zoom alto: redesenha só a parte visível na resolução total ----
    // null = ainda não calibrado; true = a matriz do PdfRenderer age sobre a página já ajustada ao bitmap;
    // false = a matriz age sobre a página em pontos (72 dpi). Varia entre versões do Android.
    @Volatile
    private var tileRelative: Boolean? = null
    private val tileRunnable = Runnable { updateTiles() }

    private fun scheduleTiles() {
        main.removeCallbacks(tileRunnable)
        main.postDelayed(tileRunnable, 250)
    }

    /** Descobre como esta versão do Android interpreta a matriz (chamado com a página já aberta). */
    private fun probeRelative(page: PdfRenderer.Page, pw: Int, ph: Int): Boolean? {
        val w = (pw / 2).coerceAtLeast(32)
        val h = (ph / 2).coerceAtLeast(32)
        val a = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            a.eraseColor(Color.WHITE)
            b.eraseColor(Color.WHITE)
            page.render(a, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.render(b, null, Matrix(), PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val pa = IntArray(w * h)
            val pb = IntArray(w * h)
            a.getPixels(pa, 0, w, 0, 0, w, h)
            b.getPixels(pb, 0, w, 0, 0, w, h)
            if (pa.all { it == Color.WHITE }) return null // página em branco: não dá para concluir
            return pa.contentEquals(pb)
        } finally {
            a.recycle()
            b.recycle()
        }
    }

    private fun updateTiles() {
        if (reflowMode || scaling || currentUri == null) return
        val r = renderer ?: return
        for (i in 0 until list.childCount) {
            val h = list.getChildViewHolder(list.getChildAt(i)) as? ImageAdapter.ImgVH ?: continue
            val img = h.img
            val pos = h.boundPos
            if (pos < 0 || pos >= sizes.size) continue
            val dw = img.width
            val dh = img.height
            if (dw <= 0 || dh <= 0) continue
            val base = (img.drawable as? BitmapDrawable)?.bitmap ?: continue
            if (base.width >= dw - 8) {
                // o desenho da página já está na resolução total: sem pedaço extra
                if (img.tileKey.isNotEmpty()) img.setTile(null, 0, 0, null, "")
                continue
            }
            val vis = Rect()
            if (!img.getLocalVisibleRect(vis) || vis.width() < 8 || vis.height() < 8) continue
            val key = "$pos|$dw|${vis.left},${vis.top},${vis.right},${vis.bottom}"
            if (key == img.tileKey) continue
            val vw = vis.width()
            val vh = vis.height()
            renderExec.execute {
                if (h.boundPos != pos) return@execute
                var out: Bitmap? = null
                synchronized(rendererLock) {
                    val rr = renderer
                    if (rr == null || rr !== r) return@execute
                    try {
                        val page = rr.openPage(pos)
                        try {
                            val pw = page.width
                            val ph = page.height
                            if (tileRelative == null) tileRelative = probeRelative(page, pw, ph)
                            val rel = tileRelative
                            if (rel != null && pw > 0 && ph > 0) {
                                // bitmap com a mesma proporção da página, grande o bastante para a parte visível
                                val tw = maxOf(vw, Math.ceil(vh.toDouble() * pw / ph).toInt())
                                val th = maxOf(vh, Math.ceil(tw.toDouble() * ph / pw).toInt())
                                if (tw.toLong() * th <= 16_000_000L) {
                                    val b = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                                    b.eraseColor(Color.WHITE)
                                    val m = Matrix()
                                    if (rel) m.setScale(dw.toFloat() / tw, dh.toFloat() / th)
                                    else m.setScale(dw.toFloat() / pw, dh.toFloat() / ph)
                                    m.postTranslate(-vis.left.toFloat(), -vis.top.toFloat())
                                    page.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    out = b
                                }
                            }
                        } finally {
                            page.close()
                        }
                    } catch (_: Throwable) {
                    }
                }
                val done = out ?: return@execute
                main.post {
                    if (h.boundPos == pos && img.width == dw && img.height == dh) {
                        img.setTile(done, vw, vh, vis, key)
                    }
                }
            }
        }
    }

    /** Lê e reduz uma figura do EPUB fora da tela principal; [done] roda na tela principal. */
    private fun loadEpubImage(name: String, done: (Bitmap?) -> Unit) {
        epubBmp.get(name)?.let { done(it); return }
        val zip = epubZip ?: return
        val maxW = resources.displayMetrics.widthPixels
        val maxH = resources.displayMetrics.heightPixels
        try {
            imgExec.execute {
                val bmp = try {
                    val e = zip.getEntry(name) ?: throw IllegalStateException()
                    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    zip.getInputStream(e).use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
                    var sample = 1
                    while (o.outWidth / (sample * 2) >= maxW && o.outHeight / (sample * 2) >= maxH) sample *= 2
                    val o2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    zip.getInputStream(e).use { android.graphics.BitmapFactory.decodeStream(it, null, o2) }
                } catch (_: Throwable) {
                    null
                }
                if (bmp != null) epubBmp.put(name, bmp)
                main.post { done(bmp) }
            }
        } catch (_: Throwable) {
        }
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

        inner class VH(val root: View, val label: TextView, val body: LinkTextView, val img: ImageView) :
            RecyclerView.ViewHolder(root)

        override fun getItemViewType(position: Int): Int = if (isBook) 1 else 0

        override fun onViewAttachedToWindow(holder: VH) {
            super.onViewAttachedToWindow(holder)
            // página que entra por troca de página: começa no topo, e não na posição em que a anterior estava
            if (resetPageTop) (holder.root as? ScrollView)?.scrollTo(0, 0)
        }

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
            val img = ImageView(parent.context).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
            }
            box.addView(label)
            box.addView(
                img,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            box.addView(body)
            if (book) {
                // uma página por tela; se o texto for maior que a tela, rola na vertical dentro dela
                val sv = NoAutoScrollView(parent.context).apply {
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
                return VH(sv, label, body, img)
            }
            box.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            return VH(box, label, body, img)
        }

        override fun getItemCount() = pages.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val fg = if (night) Color.parseColor("#D6D3CB") else Color.parseColor("#1B1B1B")
            val pt = pages[position]
            val content = if (!pt.loaded) "Carregando página…" else pt.text.ifBlank { "(página sem texto)" }

            h.label.setTextColor(fg)
            h.label.text = "— ${position + 1} —"

            // EPUB: página com figura
            val imgName = pt.image
            if (imgName != null) {
                h.img.visibility = View.VISIBLE
                h.img.maxHeight = (resources.displayMetrics.heightPixels * (if (docIsComic) 0.95f else 0.85f)).toInt()
                h.img.tag = imgName
                h.img.setImageDrawable(null)
                loadEpubImage(imgName) { bmp -> if (h.img.tag == imgName) h.img.setImageBitmap(bmp) }
            } else {
                h.img.tag = null
                h.img.setImageDrawable(null)
                h.img.visibility = View.GONE
            }
            h.body.visibility = if (imgName != null && pt.text.isBlank()) View.GONE else View.VISIBLE

            // desliga a seleção antes de trocar o texto (evita falhas ao reciclar)
            h.body.setTextIsSelectable(false)
            h.body.setTextColor(fg)
            h.body.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            val hl = if (position == hlPage) hlRange(content) else null
            val styled = docIsDocx && docxOriginal && pt.loaded && pt.fmts.isNotEmpty()
            if (pt.spans.isEmpty() && hl == null && !styled) {
                h.body.text = content
            } else {
                val ss = SpannableString(content)
                if (styled) {
                    for (f in pt.fmts) {
                        if (f.start < 0 || f.end <= f.start || f.end > content.length) continue
                        val what: Any = when (f.kind) {
                            0 -> android.text.style.StyleSpan(android.graphics.Typeface.BOLD)
                            1 -> android.text.style.StyleSpan(android.graphics.Typeface.ITALIC)
                            2 -> android.text.style.UnderlineSpan()
                            3 -> android.text.style.RelativeSizeSpan(f.value)
                            4 -> android.text.style.AlignmentSpan.Standard(android.text.Layout.Alignment.ALIGN_CENTER)
                            else -> android.text.style.AlignmentSpan.Standard(android.text.Layout.Alignment.ALIGN_OPPOSITE)
                        }
                        ss.setSpan(what, f.start, f.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
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
                displayW = (if (zoom < 1f) (listWidth * zoom).toInt() else listWidth).coerceAtLeast(1)
                displayH = (displayW.toLong() * sz[1] / sz[0]).toInt().coerceAtLeast(1)
                val lp = if (zoom < 1f) RecyclerView.LayoutParams(displayW, displayH).also {
                    it.leftMargin = (listWidth - displayW) / 2
                } else RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, displayH)
                lp.bottomMargin = dp(6)
                h.img.layoutParams = lp
            }

            h.img.colorFilter = if (night) invertFilter else null
            h.img.setBackgroundColor(if (night) Color.parseColor("#1A1A1A") else Color.WHITE)
            h.img.setImageBitmap(null)
            h.boundPos = position
            h.img.setTile(null, 0, 0, null, "") // o pedaço nítido antigo não vale mais

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

            if (docIsComic) {
                bindComic(h, position, displayW, sz)
                return
            }
            val r = renderer ?: return
            // limita o bitmap para não estourar a memória em telas grandes com zoom
            var renderW = minOf(displayW, RENDER_MAX_W)
            // páginas altas: reduz a largura para o bitmap não passar do limite de memória do desenho
            if (renderW.toLong() * renderW * sz[1] / sz[0] > RENDER_MAX_PIXELS) {
                renderW = Math.sqrt(RENDER_MAX_PIXELS.toDouble() * sz[0] / sz[1]).toInt().coerceAtLeast(1)
            }
            val renderH = (renderW.toLong() * sz[1] / sz[0]).toInt().coerceAtLeast(1)
            val key = "$position|$renderW"
            val hit = bmpCache.get(key)
            if (hit != null) {
                h.img.setImageBitmap(hit)
                scheduleTiles()
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
                main.post {
                    if (h.boundPos == position) {
                        h.img.setImageBitmap(done)
                        scheduleTiles()
                    }
                }
            }
        }

        /** Quadrinhos: lê a imagem do arquivo já reduzida para a largura que aparece na tela. */
        private fun bindComic(h: ImgVH, position: Int, displayW: Int, sz: IntArray) {
            var renderW = minOf(displayW, RENDER_MAX_W).coerceAtLeast(1)
            if (renderW.toLong() * renderW * sz[1] / sz[0] > RENDER_MAX_PIXELS) {
                renderW = Math.sqrt(RENDER_MAX_PIXELS.toDouble() * sz[0] / sz[1]).toInt().coerceAtLeast(1)
            }
            val key = "$position|$renderW"
            val hit = bmpCache.get(key)
            if (hit != null) {
                h.img.setImageBitmap(hit)
                return
            }
            val zip = epubZip ?: return
            val name = comicNames.getOrNull(position) ?: return
            val srcW = sz[0]
            renderExec.execute {
                if (h.boundPos != position) return@execute
                val bmp = try {
                    val e = zip.getEntry(name) ?: throw IllegalStateException()
                    var sample = 1
                    while (srcW / (sample * 2) >= renderW) sample *= 2
                    val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    zip.getInputStream(e).use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
                } catch (_: Throwable) {
                    null
                }
                val done = bmp ?: return@execute
                bmpCache.put(key, done)
                main.post { if (h.boundPos == position) h.img.setImageBitmap(done) }
            }
        }

        override fun onViewRecycled(h: ImgVH) {
            h.boundPos = -1
            h.img.selRects = emptyList()
            h.img.setTile(null, 0, 0, null, "")
            h.img.setImageBitmap(null)
        }
    }
}
