package com.leitor.reflow

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.math.abs

/** Destino de um link: página interna (page >= 0) ou endereço externo (uri). */
class LinkTarget(val page: Int, val uri: String?)

/**
 * Área de um link na página, em pontos, com origem no canto superior esquerdo
 * da área visível da página. [id] é o índice na lista de LinkTarget.
 */
class PageLink(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val id: Int)

/** Um caractere da página: posição (pontos, origem no canto superior esquerdo), largura e corpo da fonte. */
class Glyph(val ch: String, val x: Float, val y: Float, val w: Float, val size: Float)

/** Uma palavra com seu retângulo na página (pontos, origem no canto superior esquerdo). */
class Word(val text: String, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/** Faixa vertical da página: de largura total ou dividida em duas colunas. */
class Block(val y0: Float, val y1: Float, val columns: Boolean)

class ColumnLayout(val gutter: Float, val blocks: List<Block>)

/** Largura da página como o PDFBox a enxerga (com a rotação aplicada). */
fun pageWidth(page: PDPage): Float {
    val c = page.getCropBox()
    val r = page.getRotation()
    return if (r == 90 || r == 270) c.getHeight() else c.getWidth()
}

/**
 * Extrai o texto e marca as palavras que estão sobre um link.
 * Páginas em duas colunas são lidas coluna da esquerda e depois a da direita.
 */
class LinkStripper : PDFTextStripper() {

    var pageLinks: List<PageLink> = emptyList()

    private val glyphs = ArrayList<Glyph>()
    private var collect = false
    private var fx0 = -1e9f
    private var fx1 = 1e9f
    private var fy0 = -1e9f
    private var fy1 = 1e9f

    private fun setFilter(x0: Float, x1: Float, y0: Float, y1: Float) {
        fx0 = x0; fx1 = x1; fy0 = y0; fy1 = y1
    }

    private fun clearFilter() = setFilter(-1e9f, 1e9f, -1e9f, 1e9f)

    override fun processTextPosition(text: TextPosition) {
        val x = text.getXDirAdj()
        val w = text.getWidthDirAdj()
        val y = text.getYDirAdj()
        if (collect) glyphs.add(Glyph(text.getUnicode() ?: "", x, y, w, text.getFontSizeInPt()))
        val cx = x + w / 2f
        if (cx < fx0 || cx >= fx1 || y < fy0 || y >= fy1) return
        super.processTextPosition(text)
    }

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        val id = findLink(textPositions)
        if (id >= 0) {
            super.writeString("${Reflow.OPEN}$id${Reflow.MID}$text${Reflow.END}", textPositions)
        } else {
            super.writeString(text, textPositions)
        }
    }

    private fun findLink(positions: List<TextPosition>): Int {
        if (pageLinks.isEmpty()) return -1
        for (p in positions) {
            val cx = p.getXDirAdj() + p.getWidthDirAdj() / 2f
            val cy = p.getYDirAdj() - p.getHeightDir() * 0.3f
            for (l in pageLinks) {
                if (cx >= l.x0 - 2f && cx <= l.x1 + 2f && cy >= l.y0 - 2f && cy <= l.y1 + 2f) {
                    return l.id
                }
            }
        }
        return -1
    }

    /** Texto bruto de uma página (1 = primeira), respeitando duas colunas. */
    fun extractPageText(doc: PDDocument, page1: Int, pageW: Float): String {
        setStartPage(page1)
        setEndPage(page1)
        glyphs.clear()
        collect = true
        clearFilter()
        val plain = try {
            getText(doc)
        } finally {
            collect = false
        }
        val layout = detectColumns(glyphs, pageW) ?: return plain

        val parts = ArrayList<String>()
        try {
            for (b in layout.blocks) {
                if (b.columns) {
                    setFilter(-1e9f, layout.gutter, b.y0, b.y1)
                    parts.add(getText(doc))
                    setFilter(layout.gutter, 1e9f, b.y0, b.y1)
                    parts.add(getText(doc))
                } else {
                    setFilter(-1e9f, 1e9f, b.y0, b.y1)
                    parts.add(getText(doc))
                }
            }
        } finally {
            clearFilter()
        }
        return parts.map { it.trim('\n', '\r', ' ') }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** Palavras da página (1 = primeira) com os retângulos, para a seleção no modo Original. */
    fun extractWords(doc: PDDocument, page1: Int): List<Word> {
        setStartPage(page1)
        setEndPage(page1)
        glyphs.clear()
        collect = true
        clearFilter()
        try {
            getText(doc)
        } finally {
            collect = false
        }
        return buildWords(glyphs)
    }
}

/** Agrupa os caracteres (na ordem do fluxo do PDF) em palavras. */
fun buildWords(gs: List<Glyph>): List<Word> {
    val out = ArrayList<Word>()
    val sb = StringBuilder()
    var x0 = 0f
    var x1 = 0f
    var y = 0f
    var size = 10f
    var lastRight = 0f
    var open = false

    fun flush() {
        if (open && sb.isNotEmpty()) {
            out.add(Word(sb.toString(), x0, y - size * 0.85f, x1, y + size * 0.22f))
        }
        sb.setLength(0)
        open = false
    }

    for (gl in gs) {
        if (gl.ch.isBlank()) {
            flush()
            continue
        }
        if (open) {
            val newLine = abs(gl.y - y) > maxOf(2f, size * 0.5f)
            val back = gl.x < lastRight - size * 0.6f
            val gap = gl.x - lastRight > size * 0.45f
            if (newLine || back || gap) flush()
        }
        if (!open) {
            open = true
            x0 = gl.x
            y = gl.y
            size = if (gl.size > 0f) gl.size else 10f
        }
        sb.append(gl.ch)
        x1 = gl.x + gl.w
        lastRight = x1
    }
    flush()
    return out
}

private class Line(var y: Float, val glyphs: ArrayList<Glyph>) {
    var type = 'N' // C = duas colunas, W = largura total, N = indefinida (só um lado)
}

/**
 * Procura uma calha vertical vazia perto do centro da página. Se houver, devolve as faixas
 * da página (largura total ou duas colunas). Devolve null para páginas de coluna única.
 */
fun detectColumns(all: List<Glyph>, pageW: Float): ColumnLayout? {
    val g = all.filter { it.ch.isNotBlank() && it.w > 0f }
    if (g.size < 150 || pageW < 100f) return null

    // cobertura horizontal: quantos caracteres passam por cada ponto da largura
    val n = pageW.toInt() + 2
    val diff = IntArray(n + 2)
    for (gl in g) {
        val a = gl.x.toInt().coerceIn(0, n - 1)
        val b = (gl.x + gl.w).toInt().coerceIn(a, n - 1)
        diff[a]++
        diff[b + 1]--
    }
    val tol = maxOf(3, g.size / 80)
    val lo = (pageW * 0.30f).toInt()
    val hi = (pageW * 0.70f).toInt().coerceAtMost(n - 1)
    var cover = 0
    for (i in 0 until lo) cover += diff[i]
    var bestStart = -1
    var bestLen = 0
    var runStart = -1
    for (i in lo..hi) {
        cover += diff[i]
        if (cover <= tol) {
            if (runStart < 0) runStart = i
            val len = i - runStart + 1
            if (len > bestLen) {
                bestLen = len
                bestStart = runStart
            }
        } else {
            runStart = -1
        }
    }
    if (bestLen < 7) return null
    val gutter = bestStart + bestLen / 2f
    val gw = bestLen.toFloat()

    val left = g.count { it.x + it.w / 2f < gutter }
    val right = g.size - left
    if (left < g.size * 0.25f || right < g.size * 0.25f) return null

    // linhas
    val sorted = g.sortedBy { it.y }
    val lines = ArrayList<Line>()
    for (gl in sorted) {
        val last = lines.lastOrNull()
        if (last != null && gl.y - last.y <= maxOf(2f, gl.size * 0.45f)) {
            last.glyphs.add(gl)
        } else {
            lines.add(Line(gl.y, arrayListOf(gl)))
        }
    }
    for (ln in lines) {
        var lMax = -1e9f
        var rMin = 1e9f
        var hasL = false
        var hasR = false
        for (gl in ln.glyphs) {
            if (gl.x + gl.w / 2f < gutter) {
                hasL = true
                lMax = maxOf(lMax, gl.x + gl.w)
            } else {
                hasR = true
                rMin = minOf(rMin, gl.x)
            }
        }
        ln.type = if (hasL && hasR) (if (rMin - lMax >= gw * 0.6f) 'C' else 'W') else 'N'
    }
    val paired = lines.count { it.type == 'C' }
    if (paired < maxOf(6, (lines.size * 0.3f).toInt())) return null

    // linhas de um lado só herdam o tipo da linha anterior
    var prevType = ' '
    for (ln in lines) {
        if (ln.type != 'N') prevType = ln.type
        else if (prevType != ' ') ln.type = prevType
    }
    val firstKnown = lines.firstOrNull { it.type != 'N' }?.type ?: return null
    for (ln in lines) {
        if (ln.type == 'N') ln.type = firstKnown else break
    }

    // faixas verticais
    val blocks = ArrayList<Block>()
    var startY = -1e9f
    var curType = lines[0].type
    for (i in 1 until lines.size) {
        if (lines[i].type != curType) {
            val mid = (lines[i - 1].y + lines[i].y) / 2f
            blocks.add(Block(startY, mid, curType == 'C'))
            startY = mid
            curType = lines[i].type
        }
    }
    blocks.add(Block(startY, 1e9f, curType == 'C'))
    if (blocks.size > 9 || blocks.none { it.columns }) return null
    return ColumnLayout(gutter, blocks)
}

/** Lê os links da página. Os destinos novos são acrescentados em [targets]. */
fun extractLinks(doc: PDDocument, page: PDPage, targets: MutableList<LinkTarget>): List<PageLink> {
    val out = ArrayList<PageLink>()
    if (page.getRotation() != 0) return out // páginas giradas: links não suportados
    val crop = page.getCropBox()
    for (a in page.getAnnotations()) {
        if (a !is PDAnnotationLink) continue
        val r = a.getRectangle() ?: continue
        val t = resolveTarget(doc, a) ?: continue
        val id = targets.size
        targets.add(t)
        val x0 = r.getLowerLeftX() - crop.getLowerLeftX()
        val x1 = r.getUpperRightX() - crop.getLowerLeftX()
        val top = crop.getHeight() - (r.getUpperRightY() - crop.getLowerLeftY())
        val bottom = crop.getHeight() - (r.getLowerLeftY() - crop.getLowerLeftY())
        out.add(PageLink(minOf(x0, x1), minOf(top, bottom), maxOf(x0, x1), maxOf(top, bottom), id))
    }
    return out
}

private fun resolveTarget(doc: PDDocument, link: PDAnnotationLink): LinkTarget? {
    try {
        var dest: PDDestination? = link.getDestination()
        if (dest == null) {
            val act = link.getAction()
            if (act is PDActionURI) {
                val u = act.getURI()
                return if (u.isNullOrBlank()) null else LinkTarget(-1, u)
            }
            if (act is PDActionGoTo) dest = act.getDestination()
        }
        if (dest is PDNamedDestination) {
            dest = doc.getDocumentCatalog().findNamedDestinationPage(dest)
        }
        if (dest is PDPageDestination) {
            val n = dest.retrievePageNumber()
            if (n >= 0) return LinkTarget(n, null)
        }
    } catch (e: Exception) {
    }
    return null
}
