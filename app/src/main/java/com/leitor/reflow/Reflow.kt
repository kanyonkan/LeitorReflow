package com.leitor.reflow

/**
 * Refluxo de texto: junta as linhas quebradas do PDF em parágrafos,
 * remove hifenização de fim de linha e descarta números de página soltos.
 *
 * O texto bruto pode trazer marcas de link no formato OPEN id MID texto END
 * (inseridas por LinkStripper). Elas viram intervalos (Span) no texto final.
 */
object Reflow {

    const val OPEN = ''
    const val MID = ''
    const val END = ''

    class Span(var start: Int, var end: Int, val id: Int)
    class Result(val text: String, val spans: List<Span>)
    private class Line(val text: String, val spans: List<Span>)

    private val bullet = Regex("^([•·▪◦*\\-–—]|\\d{1,3}[.)])\\s+\\S")
    private const val SENTENCE_END = ".!?:;…”\")»"

    fun reflow(raw: String): Result {
        val all = raw.replace("\r", "").split("\n").map { parse(it.trim()) }.toMutableList()

        // remove número de página isolado no começo e no fim da página
        dropEdgePageNumber(all, fromStart = true)
        dropEdgePageNumber(all, fromStart = false)

        val nonEmpty = all.filter { it.text.isNotEmpty() }
        if (nonEmpty.isEmpty()) return Result("", emptyList())

        // comprimento de uma "linha cheia": percentil 90 dos comprimentos
        val lens = nonEmpty.map { it.text.length }.sorted()
        val ref = lens[((lens.size - 1) * 0.9).toInt()]

        val sb = StringBuilder()
        val out = ArrayList<Span>()
        var prev: Line? = null

        for (line in all) {
            if (line.text.isEmpty()) {
                if (sb.isNotEmpty() && !sb.endsWith("\n\n")) sb.append("\n\n")
                prev = null
                continue
            }
            val p = prev
            if (p != null) {
                if (isHyphenated(p.text, line.text)) {
                    sb.setLength(sb.length - 1) // tira o hífen
                    val n = sb.length
                    val iter = out.iterator()
                    while (iter.hasNext()) {
                        val s = iter.next()
                        if (s.end > n) s.end = n
                        if (s.end <= s.start) iter.remove()
                    }
                } else if (isBreak(p, line, ref)) {
                    sb.append("\n\n")
                } else {
                    sb.append(' ')
                }
            }
            val base = sb.length
            sb.append(line.text)
            for (s in line.spans) out.add(Span(base + s.start, base + s.end, s.id))
            prev = line
        }

        val text = sb.toString().trimEnd()
        val spans = out.filter { it.start < text.length }.onEach { if (it.end > text.length) it.end = text.length }
        return Result(text, spans)
    }

    /** Separa as marcas de link do texto limpo da linha. */
    private fun parse(line: String): Line {
        if (line.indexOf(OPEN) < 0) return Line(line, emptyList())
        val sb = StringBuilder()
        val spans = ArrayList<Span>()
        var curId = -1
        var curStart = 0
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == OPEN) {
                val close = line.indexOf(MID, i)
                if (close < 0) break
                curId = line.substring(i + 1, close).toIntOrNull() ?: -1
                curStart = sb.length
                i = close + 1
            } else if (c == END) {
                if (curId >= 0 && sb.length > curStart) spans.add(Span(curStart, sb.length, curId))
                curId = -1
                i++
            } else {
                sb.append(c)
                i++
            }
        }
        return Line(sb.toString(), spans)
    }

    private fun dropEdgePageNumber(lines: MutableList<Line>, fromStart: Boolean) {
        val idx = if (fromStart) lines.indexOfFirst { it.text.isNotEmpty() }
        else lines.indexOfLast { it.text.isNotEmpty() }
        if (idx >= 0 && lines[idx].text.length <= 4 && lines[idx].text.all { it.isDigit() }) {
            lines.removeAt(idx)
        }
    }

    private fun isHyphenated(prev: String, next: String): Boolean =
        prev.length > 1 &&
            prev.endsWith("-") &&
            prev[prev.length - 2].isLetter() &&
            next.first().isLowerCase()

    /** Linha de índice: o link cobre a maior parte da linha. */
    private fun isLinkedEntry(line: Line): Boolean {
        if (line.spans.isEmpty()) return false
        val covered = line.spans.sumOf { it.end - it.start }
        return covered >= line.text.length * 0.6
    }

    private fun isBreak(prevLine: Line, nextLine: Line, ref: Int): Boolean {
        val prev = prevLine.text
        val next = nextLine.text
        if (bullet.containsMatchIn(next)) return true
        // entradas de índice clicáveis ficam uma por linha
        if (isLinkedEntry(prevLine) && nextLine.spans.isNotEmpty()) return true
        val shortPrev = prev.length < ref * 0.7
        val endsSentence = prev.last() in SENTENCE_END
        val heading = shortPrev && prev.any { it.isLetter() } && prev == prev.uppercase()
        return (shortPrev && endsSentence) || heading
    }
}
