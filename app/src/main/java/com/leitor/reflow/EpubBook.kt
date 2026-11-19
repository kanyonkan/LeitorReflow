package com.leitor.reflow

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Leitor de EPUB: lê o zip, segue a ordem de leitura (spine) e transforma o texto dos capítulos
 * em "páginas" de tamanho parecido, com os links internos e externos. O resultado tem o mesmo
 * formato do Refluxo de PDF (texto + intervalos de link + destinos).
 */
object EpubBook {

    class Page(val text: String, val spans: List<Reflow.Span>)
    class Result(val pages: List<Page>, val targets: List<LinkTarget>)

    private const val PAGE_CHARS = 1700 // tamanho aproximado de cada "página"
    private const val MAX_ENTRY = 12 * 1024 * 1024

    private class Para(val text: String, val spans: List<Reflow.Span>, val chapterStart: Boolean)
    private class LinkRef(val path: String, val frag: String, val external: String?)

    fun load(input: InputStream): Result {
        // 1) lê só os arquivos de texto do zip (imagens e fontes são ignoradas)
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.isDirectory) continue
                val n = e.name
                val low = n.lowercase()
                val wanted = low.endsWith(".xhtml") || low.endsWith(".html") || low.endsWith(".htm") ||
                    low.endsWith(".xml") || low.endsWith(".opf") || low.endsWith(".ncx")
                if (!wanted) continue
                val bytes = zin.readBytes()
                if (bytes.size <= MAX_ENTRY) files[n] = bytes
            }
        }
        if (files.isEmpty()) throw IllegalArgumentException("EPUB vazio")

        // 2) acha o arquivo OPF
        val container = files["META-INF/container.xml"]
        val opfPath = container?.let {
            Jsoup.parse(str(it), "", Parser.xmlParser()).getElementsByTag("rootfile")
                .firstOrNull()?.attr("full-path")
        } ?: files.keys.firstOrNull { it.lowercase().endsWith(".opf") }
            ?: throw IllegalArgumentException("EPUB sem OPF")
        val opfBytes = files[opfPath] ?: throw IllegalArgumentException("EPUB sem OPF")
        val opf = Jsoup.parse(str(opfBytes), "", Parser.xmlParser())
        val baseDir = opfPath.substringBeforeLast('/', "")

        // 3) ordem de leitura
        val manifest = HashMap<String, String>() // id -> caminho completo
        for (it in opf.getElementsByTag("item")) {
            val id = it.attr("id")
            val href = it.attr("href")
            if (id.isNotEmpty() && href.isNotEmpty()) manifest[id] = normalize(baseDir, decode(href))
        }
        val spine = ArrayList<String>()
        for (r in opf.getElementsByTag("itemref")) {
            val p = manifest[r.attr("idref")] ?: continue
            if (!spine.contains(p)) spine.add(p)
        }
        if (spine.isEmpty()) {
            spine.addAll(files.keys.filter {
                val l = it.lowercase()
                l.endsWith(".xhtml") || l.endsWith(".html") || l.endsWith(".htm")
            }.sorted())
        }

        // 4) extrai os parágrafos de cada capítulo
        val paras = ArrayList<Para>()
        val links = ArrayList<LinkRef>()
        val anchors = HashMap<String, Int>() // "caminho#id" -> índice do parágrafo
        val fileStart = HashMap<String, Int>() // caminho -> primeiro parágrafo
        for (path in spine) {
            val bytes = files[path] ?: files.entries.firstOrNull { decode(it.key) == path }?.value ?: continue
            fileStart[path] = paras.size
            parseChapter(str(bytes), path, paras, links, anchors)
        }

        // 5) agrupa em páginas
        val paraPage = IntArray(paras.size)
        val pages = ArrayList<Page>()
        val sb = StringBuilder()
        var spans = ArrayList<Reflow.Span>()
        fun flush() {
            if (sb.isNotEmpty()) pages.add(Page(sb.toString(), spans))
            sb.setLength(0)
            spans = ArrayList()
        }
        for ((i, p) in paras.withIndex()) {
            val addLen = p.text.length + (if (sb.isEmpty()) 0 else 2)
            if (sb.isNotEmpty() && (p.chapterStart || sb.length + addLen > PAGE_CHARS)) flush()
            if (sb.isNotEmpty()) sb.append("\n\n")
            val off = sb.length
            sb.append(p.text)
            for (s in p.spans) spans.add(Reflow.Span(s.start + off, s.end + off, s.id))
            paraPage[i] = pages.size
        }
        flush()
        if (pages.isEmpty()) throw IllegalArgumentException("EPUB sem texto")

        // 6) destinos dos links
        fun pageOfPara(i: Int): Int = if (i in paraPage.indices) paraPage[i].coerceAtMost(pages.size - 1) else -1
        val targets = links.map { l ->
            if (l.external != null) {
                LinkTarget(-1, l.external)
            } else {
                val idx = anchors[l.path + "#" + l.frag].takeIf { l.frag.isNotEmpty() }
                    ?: fileStart[l.path]
                LinkTarget(if (idx == null) -1 else pageOfPara(idx.coerceAtMost(paras.size - 1)), null)
            }
        }
        return Result(pages, targets)
    }

    // ------------------------------------------------------------------ capítulo

    private val blockTags = setOf(
        "p", "div", "section", "article", "aside", "header", "footer", "nav", "main", "figure",
        "figcaption", "blockquote", "pre", "ul", "ol", "li", "dl", "dt", "dd", "table", "tr",
        "h1", "h2", "h3", "h4", "h5", "h6", "hr", "address", "body"
    )
    private val skipTags = setOf("script", "style", "svg", "img", "image", "head", "title", "audio", "video")

    private fun parseChapter(
        html: String, path: String, paras: ArrayList<Para>, links: ArrayList<LinkRef>,
        anchors: HashMap<String, Int>
    ) {
        val doc = Jsoup.parse(html)
        val body = doc.body()
        val dir = path.substringBeforeLast('/', "")
        val sb = StringBuilder()
        var spans = ArrayList<Reflow.Span>()
        var first = true // o primeiro parágrafo do capítulo abre uma página nova

        fun flush() {
            // tira o espaço do fim e ajusta os links que passariam do fim
            var end = sb.length
            while (end > 0 && (sb[end - 1] == ' ' || sb[end - 1] == '\n')) end--
            if (end > 0) {
                val text = sb.substring(0, end)
                val kept = ArrayList<Reflow.Span>()
                for (s in spans) {
                    val e = minOf(s.end, end)
                    if (s.start in 0 until e) kept.add(Reflow.Span(s.start, e, s.id))
                }
                paras.add(Para(text, kept, first))
                first = false
            }
            sb.setLength(0)
            spans = ArrayList()
        }

        fun appendText(t: String) {
            for (ch in t) {
                val c = if (ch == ' ') ' ' else ch
                if (c.isWhitespace()) {
                    if (sb.isNotEmpty() && sb[sb.length - 1] != ' ' && sb[sb.length - 1] != '\n') sb.append(' ')
                } else {
                    sb.append(c)
                }
            }
        }

        fun walk(n: Node, linkStart: IntArray) {
            if (n is TextNode) {
                appendText(n.wholeText)
                return
            }
            if (n !is Element) return
            val tag = n.tagName().lowercase()
            if (tag in skipTags) return
            val id = n.attr("id").ifEmpty { n.attr("name") }
            val block = tag in blockTags
            if (block) flush()
            if (id.isNotEmpty()) anchors["$path#$id"] = paras.size
            if (tag == "br") {
                if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append('\n')
                return
            }
            if (tag == "li") sb.append("• ")
            var linkId = -1
            var start = 0
            if (tag == "a" && n.hasAttr("href")) {
                val ref = makeLink(n.attr("href").trim(), path, dir)
                if (ref != null) {
                    links.add(ref)
                    linkId = links.size - 1
                    start = sb.length
                }
            }
            for (c in n.childNodes()) walk(c, linkStart)
            if (linkId >= 0 && sb.length > start) spans.add(Reflow.Span(start, sb.length, linkId))
            if (block) flush()
        }
        walk(body, IntArray(1))
        flush()
    }

    private fun makeLink(href: String, path: String, dir: String): LinkRef? {
        if (href.isEmpty()) return null
        val low = href.lowercase()
        if (low.startsWith("http:") || low.startsWith("https:") || low.startsWith("mailto:")) {
            return LinkRef("", "", href)
        }
        if (low.contains(":")) return null
        val h = decode(href)
        val frag = h.substringAfter('#', "")
        val file = h.substringBefore('#')
        val target = if (file.isEmpty()) path else normalize(dir, file)
        return LinkRef(target, frag, null)
    }

    // ------------------------------------------------------------------ utilidades

    private fun str(b: ByteArray): String {
        var s = String(b, Charsets.UTF_8)
        if (s.startsWith("﻿")) s = s.substring(1)
        return s
    }

    private fun decode(s: String): String = try {
        java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        s
    }

    /** Junta uma pasta e um caminho relativo, resolvendo "." e "..". */
    private fun normalize(dir: String, rel: String): String {
        val parts = ArrayList<String>()
        val all = (if (rel.startsWith("/")) "" else dir) + "/" + rel
        for (seg in all.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }
}
