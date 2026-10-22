package com.leitor.reflow

import kotlin.math.abs
import kotlin.math.max

/**
 * Uma palavra da página. A caixa está em pontos (1/72"), com origem no canto
 * superior esquerdo da área visível da página. [link] é o índice do destino (-1 = sem link).
 */
class Word(
    val text: String,
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val link: Int
) {
    val yc: Float get() = (y0 + y1) / 2f
    val h: Float get() = y1 - y0
}

/** Palavras na ordem de leitura, agrupadas em linhas. */
class Ordered(val lines: List<List<Word>>, val twoColumns: Boolean) {
    val words: List<Word> by lazy { lines.flatten() }
}

/**
 * Ordem de leitura de uma página a partir da posição das palavras.
 * Detecta páginas em duas colunas (um corredor vertical vazio no meio) e lê a coluna
 * da esquerda inteira antes da direita. Linhas que cruzam as duas colunas (títulos)
 * ficam no lugar delas.
 */
object Columns {

    /**
     * [needLines] = false: só detecta colunas; se a página não tiver duas colunas devolve
     * uma lista vazia (o chamador usa o texto normal, sem custo extra).
     */
    fun order(all: List<Word>, needLines: Boolean): Ordered {
        if (all.isEmpty()) return Ordered(emptyList(), false)
        val g = findGutter(all)
        if (g == null) {
            return if (needLines) Ordered(groupLines(all), false) else Ordered(emptyList(), false)
        }
        val gs = g[0]
        val ge = g[1]
        val mid = (gs + ge) / 2f
        val left = ArrayList<Word>()
        val right = ArrayList<Word>()
        val span = ArrayList<Word>()
        for (w in all) {
            when {
                w.x0 < ge - 1f && w.x1 > gs + 1f -> span.add(w)
                (w.x0 + w.x1) / 2f < mid -> left.add(w)
                else -> right.add(w)
            }
        }
        val spanLines = groupLines(span)
        val cuts = spanLines.map { l -> l.map { it.yc }.average().toFloat() }
        val bandsL = List(cuts.size + 1) { ArrayList<Word>() }
        val bandsR = List(cuts.size + 1) { ArrayList<Word>() }
        fun band(y: Float): Int {
            var b = 0
            while (b < cuts.size && cuts[b] < y) b++
            return b
        }
        for (w in left) bandsL[band(w.yc)].add(w)
        for (w in right) bandsR[band(w.yc)].add(w)

        val out = ArrayList<List<Word>>()
        for (b in 0..cuts.size) {
            out.addAll(groupLines(bandsL[b]))
            out.addAll(groupLines(bandsR[b]))
            if (b < spanLines.size) out.add(spanLines[b])
        }
        return Ordered(out, true)
    }

    /** Texto bruto (uma linha por linha) com as marcas de link que o Reflow entende. */
    fun toRaw(o: Ordered): String {
        val pitches = ArrayList<Float>()
        for (i in 1 until o.lines.size) {
            val p = o.lines[i][0].yc - o.lines[i - 1][0].yc
            if (p > 0f) pitches.add(p)
        }
        pitches.sort()
        val median = if (pitches.isEmpty()) 0f else pitches[pitches.size / 2]

        val sb = StringBuilder()
        var prev: List<Word>? = null
        for (line in o.lines) {
            val p = prev
            if (p != null && median > 0f) {
                val pitch = line[0].yc - p[0].yc
                if (pitch > median * 1.5f) sb.append('\n') // espaço maior entre linhas = novo parágrafo
            }
            appendLine(sb, line)
            sb.append('\n')
            prev = line
        }
        return sb.toString()
    }

    private fun appendLine(sb: StringBuilder, line: List<Word>) {
        var cur = -1
        for ((i, w) in line.withIndex()) {
            if (w.link != cur) {
                if (cur >= 0) sb.append(Reflow.END)
                if (i > 0) sb.append(' ')
                if (w.link >= 0) sb.append(Reflow.OPEN).append(w.link).append(Reflow.MID)
                cur = w.link
            } else if (i > 0) {
                sb.append(' ')
            }
            sb.append(w.text)
        }
        if (cur >= 0) sb.append(Reflow.END)
    }

    /** Agrupa palavras em linhas (de cima para baixo), cada linha da esquerda para a direita. */
    fun groupLines(ws: List<Word>): List<List<Word>> {
        if (ws.isEmpty()) return emptyList()
        val sorted = ws.sortedBy { it.yc }
        val lines = ArrayList<List<Word>>()
        var cur = ArrayList<Word>()
        var sumY = 0f
        for (w in sorted) {
            if (cur.isNotEmpty()) {
                val avg = sumY / cur.size
                if (abs(w.yc - avg) > max(w.h, cur[0].h) * 0.55f) {
                    lines.add(cur.sortedBy { it.x0 })
                    cur = ArrayList()
                    sumY = 0f
                }
            }
            cur.add(w)
            sumY += w.yc
        }
        if (cur.isNotEmpty()) lines.add(cur.sortedBy { it.x0 })
        return lines
    }

    /** Procura o corredor vazio entre duas colunas. Devolve [início, fim] em x, ou null. */
    private fun findGutter(all: List<Word>): FloatArray? {
        if (all.size < 40) return null
        val minX = all.minOf { it.x0 }
        val maxX = all.maxOf { it.x1 }
        val ext = maxX - minX
        if (ext < 200f) return null
        val n = ext.toInt() + 2

        val diff = IntArray(n + 1)
        for (w in all) {
            val a = (w.x0 - minX).toInt().coerceIn(0, n - 1)
            val b = (w.x1 - minX).toInt().coerceIn(0, n - 1)
            diff[a]++
            diff[b + 1]--
        }
        val cnt = IntArray(n)
        var run = 0
        for (i in 0 until n) {
            run += diff[i]
            cnt[i] = run
        }

        val thr = max(1, (all.size * 0.03f).toInt())
        val lo = (n * 0.25f).toInt()
        val hi = (n * 0.75f).toInt()
        var bestS = -1
        var bestE = -1
        var s = -1
        for (i in lo..hi) {
            if (cnt[i] <= thr) {
                if (s < 0) s = i
            } else if (s >= 0) {
                if (i - 1 - s > bestE - bestS) {
                    bestS = s
                    bestE = i - 1
                }
                s = -1
            }
        }
        if (s >= 0 && hi - s > bestE - bestS) {
            bestS = s
            bestE = hi
        }
        if (bestS < 0) return null
        // o corredor precisa ser fechado dentro da janela central
        if (bestS <= lo || bestE >= hi) return null
        val gw = bestE - bestS + 1
        if (gw < 8 || gw > ext * 0.3f) return null

        val gs = minX + bestS
        val ge = minX + bestE + 1
        val mid = (gs + ge) / 2f
        val left = ArrayList<Word>()
        val right = ArrayList<Word>()
        for (w in all) {
            if (w.x0 < ge - 1f && w.x1 > gs + 1f) continue
            if ((w.x0 + w.x1) / 2f < mid) left.add(w) else right.add(w)
        }
        if (left.size < all.size * 0.2f || right.size < all.size * 0.2f) return null
        if (groupLines(left).size < 6 || groupLines(right).size < 6) return null

        // as duas colunas precisam estar na mesma altura da página
        val lTop = left.minOf { it.y0 }
        val lBot = left.maxOf { it.y1 }
        val rTop = right.minOf { it.y0 }
        val rBot = right.maxOf { it.y1 }
        val overlap = minOf(lBot, rBot) - max(lTop, rTop)
        val shorter = minOf(lBot - lTop, rBot - rTop)
        if (shorter <= 0f || overlap < shorter * 0.5f) return null

        return floatArrayOf(gs, ge)
    }
}
