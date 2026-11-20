package com.leitor.reflow

import android.graphics.BitmapFactory
import com.leitor.reflow.EpubBook.Fmt
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipFile

/**
 * Leitor de DOCX: lê o texto, a formatação básica (negrito, itálico, sublinhado, tamanho, títulos,
 * alinhamento), listas, tabelas simples, links, índice e figuras, e divide em "páginas" como o EPUB.
 */
object DocxBook {

    fun load(file: File): EpubBook.Result = ZipFile(file).use { load(it) }

    private fun load(zip: ZipFile): EpubBook.Result {
        fun read(name: String): String? {
            val e = zip.getEntry(name) ?: return null
            return EpubBook.str(zip.getInputStream(e).use { it.readBytes() })
        }

        val docXml = read("word/document.xml") ?: throw IllegalArgumentException("DOCX sem texto")

        // relações: id -> (destino, é externo?)
        val rels = HashMap<String, Pair<String, Boolean>>()
        read("word/_rels/document.xml.rels")?.let { x ->
            for (r in Jsoup.parse(x, "", Parser.xmlParser()).getElementsByTag("Relationship")) {
                rels[r.attr("Id")] = Pair(r.attr("Target"), r.attr("TargetMode").equals("External", true))
            }
        }
        // estilos: id -> nome
        val styleNames = HashMap<String, String>()
        read("word/styles.xml")?.let { x ->
            for (st in Jsoup.parse(x, "", Parser.xmlParser()).getElementsByTag("w:style")) {
                val nm = st.getElementsByTag("w:name").firstOrNull()?.attr("w:val") ?: ""
                styleNames[st.attr("w:styleId")] = nm.lowercase()
            }
        }

        val imgCache = HashMap<String, String?>()
        fun imageEntry(rid: String): String? {
            val rel = rels[rid] ?: return null
            if (rel.second) return null
            val path = EpubBook.normalize("word", EpubBook.decode(rel.first))
            return imgCache.getOrPut(path) {
                val e = zip.getEntry(path)
                if (e == null) {
                    null
                } else {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    try {
                        zip.getInputStream(e).use { BitmapFactory.decodeStream(it, null, o) }
                    } catch (_: Throwable) {
                    }
                    if (o.outWidth in 1..79 || o.outHeight in 1..79) null else e.name
                }
            }
        }

        val paras = ArrayList<EpubBook.Para>()
        val links = ArrayList<EpubBook.LinkRef>()
        val anchors = HashMap<String, Int>()

        // parágrafo em construção
        val sb = StringBuilder()
        var fmts = ArrayList<Fmt>()
        var spans = ArrayList<Reflow.Span>()
        val pendingImages = ArrayList<String>()
        var forceBreak = false // o próximo parágrafo começa uma página nova
        var breakAfter = false // quebra de página dentro do parágrafo atual

        fun flushPara() {
            var end = sb.length
            while (end > 0 && sb[end - 1].isWhitespace()) end--
            if (end > 0) {
                val text = sb.substring(0, end)
                val kf = ArrayList<Fmt>()
                for (f in fmts) {
                    val e = minOf(f.end, end)
                    if (f.start in 0 until e) kf.add(Fmt(f.start, e, f.kind, f.value))
                }
                val ks = ArrayList<Reflow.Span>()
                for (s in spans) {
                    val e = minOf(s.end, end)
                    if (s.start in 0 until e) ks.add(Reflow.Span(s.start, e, s.id))
                }
                paras.add(EpubBook.Para(text, ks, forceBreak, null, kf))
                forceBreak = false
            }
            for (img in pendingImages) {
                paras.add(EpubBook.Para("", emptyList(), false, img))
                forceBreak = false
            }
            pendingImages.clear()
            if (breakAfter) forceBreak = true
            breakAfter = false
            sb.setLength(0)
            fmts = ArrayList()
            spans = ArrayList()
        }

        fun on(e: Element?): Boolean {
            if (e == null) return false
            val v = e.attr("w:val").lowercase()
            return v != "0" && v != "false" && v != "off" && v != "none"
        }

        fun child(e: Element, tag: String): Element? = e.children().firstOrNull { it.tagName() == tag }

        fun run(r: Element) {
            val rPr = child(r, "w:rPr")
            if (rPr != null && child(rPr, "w:vanish") != null) return
            val start = sb.length
            for (c in r.children()) {
                when (c.tagName()) {
                    "w:t" -> sb.append(c.wholeText())
                    "w:tab" -> sb.append('\t')
                    "w:br" -> if (c.attr("w:type") == "page") breakAfter = true else sb.append('\n')
                    "w:cr" -> sb.append('\n')
                    "w:noBreakHyphen" -> sb.append('-')
                    "w:drawing", "w:pict", "w:object" -> {
                        for (b in c.getElementsByTag("a:blip")) imageEntry(b.attr("r:embed"))?.let { pendingImages.add(it) }
                        for (b in c.getElementsByTag("v:imagedata")) imageEntry(b.attr("r:id"))?.let { pendingImages.add(it) }
                    }
                }
            }
            val end = sb.length
            if (end <= start || rPr == null) return
            if (on(child(rPr, "w:b"))) fmts.add(Fmt(start, end, 0))
            if (on(child(rPr, "w:i"))) fmts.add(Fmt(start, end, 1))
            if (on(child(rPr, "w:u"))) fmts.add(Fmt(start, end, 2))
            val sz = child(rPr, "w:sz")?.attr("w:val")?.toFloatOrNull()
            if (sz != null && sz > 0f) {
                val scale = (sz / 2f / 11f).coerceIn(0.6f, 2.5f)
                if (scale < 0.88f || scale > 1.12f) fmts.add(Fmt(start, end, 3, scale))
            }
        }

        fun walkRuns(el: Element) {
            for (c in el.children()) {
                when (c.tagName()) {
                    "w:r" -> run(c)
                    "w:hyperlink" -> {
                        val start = sb.length
                        walkRuns(c)
                        val end = sb.length
                        val rid = c.attr("r:id")
                        val anchor = c.attr("w:anchor")
                        val rel = rels[rid]
                        val ref = when {
                            rel != null && rel.second -> EpubBook.LinkRef("", "", rel.first)
                            anchor.isNotEmpty() -> EpubBook.LinkRef("", anchor, null)
                            else -> null
                        }
                        if (ref != null && end > start) {
                            links.add(ref)
                            spans.add(Reflow.Span(start, end, links.size - 1))
                        }
                    }
                    "w:bookmarkStart" -> {
                        val n = c.attr("w:name")
                        if (n.isNotEmpty()) anchors["#$n"] = paras.size
                    }
                    "w:fldSimple", "w:ins", "w:smartTag", "w:sdt", "w:sdtContent", "w:customXml" -> walkRuns(c)
                }
            }
        }

        fun appendParagraph(p: Element) {
            val pPr = child(p, "w:pPr")
            val start = sb.length
            var heading = false
            var scale = 1.1f
            var align = -1
            if (pPr != null) {
                val sid = child(pPr, "w:pStyle")?.attr("w:val") ?: ""
                val name = styleNames[sid] ?: sid.lowercase()
                if (name.startsWith("heading") || name.startsWith("título") || name.startsWith("titulo") ||
                    name == "title" || name == "subtitle" || sid.lowercase().startsWith("ttulo")
                ) {
                    heading = true
                    scale = when {
                        name == "title" -> 1.6f
                        name.endsWith("1") -> 1.5f
                        name.endsWith("2") -> 1.3f
                        name.endsWith("3") -> 1.15f
                        else -> 1.1f
                    }
                }
                when (child(pPr, "w:jc")?.attr("w:val")) {
                    "center" -> align = 4
                    "right", "end" -> align = 5
                }
                if (child(pPr, "w:pageBreakBefore") != null && on(child(pPr, "w:pageBreakBefore"))) forceBreak = true
                val num = child(pPr, "w:numPr")
                if (num != null) {
                    val lvl = child(num, "w:ilvl")?.attr("w:val")?.toIntOrNull() ?: 0
                    sb.append("    ".repeat(lvl.coerceIn(0, 4))).append("• ")
                }
            }
            walkRuns(p)
            val end = sb.length
            if (end > start) {
                if (heading) {
                    fmts.add(Fmt(start, end, 0))
                    fmts.add(Fmt(start, end, 3, scale))
                }
                if (align >= 0) fmts.add(Fmt(start, end, align))
            }
        }

        fun walkBlocks(parent: Element) {
            for (c in parent.children()) {
                when (c.tagName()) {
                    "w:p" -> {
                        appendParagraph(c)
                        flushPara()
                    }
                    "w:tbl" -> {
                        for (tr in c.children().filter { it.tagName() == "w:tr" }) {
                            var firstCell = true
                            for (tc in tr.children().filter { it.tagName() == "w:tc" }) {
                                if (!firstCell && sb.isNotEmpty()) sb.append("  |  ")
                                firstCell = false
                                var firstP = true
                                for (cp in tc.getElementsByTag("w:p")) {
                                    if (!firstP && sb.isNotEmpty() && !sb[sb.length - 1].isWhitespace()) sb.append(' ')
                                    firstP = false
                                    appendParagraph(cp)
                                }
                            }
                            flushPara()
                        }
                    }
                    "w:sdt", "w:sdtContent", "w:customXml", "w:ins", "w:smartTag" -> walkBlocks(c)
                    "w:bookmarkStart" -> {
                        val n = c.attr("w:name")
                        if (n.isNotEmpty()) anchors["#$n"] = paras.size
                    }
                }
            }
        }

        val body = Jsoup.parse(docXml, "", Parser.xmlParser()).getElementsByTag("w:body").firstOrNull()
            ?: throw IllegalArgumentException("DOCX sem texto")
        walkBlocks(body)
        flushPara()
        return EpubBook.finish(paras, links, anchors, emptyMap())
    }
}
