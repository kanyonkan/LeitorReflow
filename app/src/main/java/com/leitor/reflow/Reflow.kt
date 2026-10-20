package com.leitor.reflow

/**
 * Refluxo de texto: junta as linhas quebradas do PDF em parágrafos,
 * remove hifenização de fim de linha e descarta números de página soltos.
 */
object Reflow {

    private val bullet = Regex("^([•·▪◦*\\-–—]|\\d{1,3}[.)])\\s+\\S")
    private const val SENTENCE_END = ".!?:;…”\")»"

    fun reflow(raw: String): String {
        val all = raw.replace("\r", "").split("\n").map { it.trim() }.toMutableList()

        // remove número de página isolado no começo e no fim da página
        dropEdgePageNumber(all, fromStart = true)
        dropEdgePageNumber(all, fromStart = false)

        val nonEmpty = all.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return ""

        // comprimento de uma "linha cheia": percentil 90 dos comprimentos
        val lens = nonEmpty.map { it.length }.sorted()
        val ref = lens[((lens.size - 1) * 0.9).toInt()]

        val sb = StringBuilder()
        var prev: String? = null

        for (line in all) {
            if (line.isEmpty()) {
                if (sb.isNotEmpty() && !sb.endsWith("\n\n")) sb.append("\n\n")
                prev = null
                continue
            }
            val p = prev
            if (p == null) {
                sb.append(line)
            } else if (isHyphenated(p, line)) {
                sb.setLength(sb.length - 1) // tira o hífen
                sb.append(line)
            } else if (isBreak(p, line, ref)) {
                sb.append("\n\n").append(line)
            } else {
                sb.append(' ').append(line)
            }
            prev = line
        }
        return sb.toString().trim()
    }

    private fun dropEdgePageNumber(lines: MutableList<String>, fromStart: Boolean) {
        val idx = if (fromStart) lines.indexOfFirst { it.isNotEmpty() }
        else lines.indexOfLast { it.isNotEmpty() }
        if (idx >= 0 && lines[idx].length <= 4 && lines[idx].all { it.isDigit() }) {
            lines.removeAt(idx)
        }
    }

    private fun isHyphenated(prev: String, next: String): Boolean =
        prev.length > 1 &&
            prev.endsWith("-") &&
            prev[prev.length - 2].isLetter() &&
            next.first().isLowerCase()

    private fun isBreak(prev: String, next: String, ref: Int): Boolean {
        if (bullet.containsMatchIn(next)) return true
        val shortPrev = prev.length < ref * 0.7
        val endsSentence = prev.last() in SENTENCE_END
        val heading = shortPrev && prev.any { it.isLetter() } && prev == prev.uppercase()
        return (shortPrev && endsSentence) || heading
    }
}
