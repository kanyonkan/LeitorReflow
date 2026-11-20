package com.leitor.reflow

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Leitor de .doc (Word 97-2003): lê o contêiner OLE, acha a tabela de trechos de texto do Word e
 * monta as páginas só com o texto (sem formatação nem figuras). Um .docx com extensão .doc também é aceito.
 */
object DocBook {

    private const val MAX_FILE = 60 * 1024 * 1024

    fun load(file: File): EpubBook.Result {
        if (file.length() > MAX_FILE) throw IllegalArgumentException("Arquivo muito grande")
        val data = file.readBytes()
        if (data.size > 4 && data[0] == 0x50.toByte() && data[1] == 0x4B.toByte()) {
            return DocxBook.load(file) // na verdade é um .docx
        }
        val text = try {
            extractText(data)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Throwable) {
            throw IllegalArgumentException("Não foi possível ler este arquivo .doc")
        }
        return build(text)
    }

    // ------------------------------------------------------------------ bytes

    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun i32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    // ------------------------------------------------------------------ contêiner OLE

    private class Cfb(val data: ByteArray) {
        private val ss: Int
        private val mss: Int
        private val cutoff: Int
        private val fat: IntArray
        private val minifat: IntArray
        private val miniStream: ByteArray
        private val names = ArrayList<String>()
        private val types = ArrayList<Int>()
        private val starts = ArrayList<Int>()
        private val sizes = ArrayList<Int>()

        init {
            val sig = longArrayOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)
            if (data.size < 512) throw IllegalArgumentException("Não é um documento do Word")
            for (i in 0 until 8) {
                if ((data[i].toLong() and 0xFF) != sig[i]) throw IllegalArgumentException("Não é um documento do Word")
            }
            ss = 1 shl u16(data, 30)
            mss = 1 shl u16(data, 32)
            if (ss < 512 || ss > 65536 || mss < 16 || mss > ss) throw IllegalArgumentException("Documento inválido")
            val firstDir = i32(data, 48)
            cutoff = i32(data, 56)
            val firstMini = i32(data, 60)
            val nMini = i32(data, 64)
            var dif = i32(data, 68)
            val nDif = i32(data, 72)

            // setores da FAT: 109 no cabeçalho + os da cadeia DIFAT
            val fatSectors = ArrayList<Int>()
            for (i in 0 until 109) fatSectors.add(i32(data, 76 + 4 * i))
            var guard = 0
            while (dif >= 0 && guard < nDif && guard < 100000) {
                val off = (dif + 1).toLong() * ss
                if (off + ss > data.size) break
                val per = ss / 4 - 1
                for (k in 0 until per) fatSectors.add(i32(data, off.toInt() + 4 * k))
                dif = i32(data, off.toInt() + 4 * per)
                guard++
            }
            val fl = ArrayList<Int>()
            for (f in fatSectors) {
                if (f < 0) continue
                val off = (f + 1).toLong() * ss
                if (off + ss > data.size) continue
                for (k in 0 until ss / 4) fl.add(i32(data, off.toInt() + 4 * k))
            }
            fat = fl.toIntArray()

            // diretório
            val dir = chain(firstDir)
            var rootStart = -1
            var rootSize = 0
            for (i in 0 until dir.size / 128) {
                val o = i * 128
                val nl = u16(dir, o + 64)
                val name = if (nl >= 2 && nl <= 64) String(dir, o, nl - 2, Charsets.UTF_16LE) else ""
                val type = dir[o + 66].toInt() and 0xFF
                names.add(name)
                types.add(type)
                starts.add(i32(dir, o + 116))
                sizes.add(i32(dir, o + 120))
                if (type == 5 && rootStart == -1) {
                    rootStart = i32(dir, o + 116)
                    rootSize = i32(dir, o + 120)
                }
            }
            // mini FAT e mini stream (arquivos pequenos dentro do contêiner)
            if (nMini > 0 && firstMini >= 0) {
                val mb = chain(firstMini)
                minifat = IntArray(mb.size / 4) { i32(mb, 4 * it) }
            } else {
                minifat = IntArray(0)
            }
            val ms = if (rootStart >= 0) chain(rootStart) else ByteArray(0)
            miniStream = if (rootSize in 0..ms.size) ms.copyOf(rootSize) else ms
        }

        private fun chain(start: Int): ByteArray {
            val out = ByteArrayOutputStream()
            var c = start
            var n = 0
            while (c >= 0 && c < fat.size && n <= fat.size) {
                val off = (c + 1).toLong() * ss
                if (off >= data.size) break
                val len = minOf(ss.toLong(), data.size - off).toInt()
                out.write(data, off.toInt(), len)
                c = fat[c]
                n++
            }
            return out.toByteArray()
        }

        fun stream(name: String): ByteArray? {
            for (i in names.indices) {
                if (types[i] != 2 || names[i] != name) continue
                val size = sizes[i]
                if (size < 0) return null
                if (size < cutoff) {
                    val out = ByteArrayOutputStream()
                    var c = starts[i]
                    var n = 0
                    while (c >= 0 && c < minifat.size && n <= minifat.size) {
                        val off = c.toLong() * mss
                        if (off >= miniStream.size) break
                        val len = minOf(mss.toLong(), miniStream.size - off).toInt()
                        out.write(miniStream, off.toInt(), len)
                        c = minifat[c]
                        n++
                    }
                    val b = out.toByteArray()
                    return if (size <= b.size) b.copyOf(size) else b
                }
                val b = chain(starts[i])
                return if (size <= b.size) b.copyOf(size) else b
            }
            return null
        }
    }

    // ------------------------------------------------------------------ texto do Word

    private fun extractText(data: ByteArray): String {
        val cfb = Cfb(data)
        val wd = cfb.stream("WordDocument") ?: throw IllegalArgumentException("Não é um documento do Word")
        if (wd.size < 0x40 || u16(wd, 0) != 0xA5EC) throw IllegalArgumentException("Não é um documento do Word")
        if (u16(wd, 2) < 193) throw IllegalArgumentException("Formato do Word anterior ao 97 não é suportado")
        val flags = u16(wd, 0x0A)
        if ((flags and 0x0100) != 0) throw IllegalArgumentException("Documento protegido por senha")
        val tbl = cfb.stream(if ((flags and 0x0200) != 0) "1Table" else "0Table")
            ?: throw IllegalArgumentException("Documento inválido")

        val csw = u16(wd, 32)
        var o = 34 + csw * 2
        val cslw = u16(wd, o)
        val lw = o + 2
        val ccpText = i32(wd, lw + 3 * 4)
        o = lw + cslw * 4
        val base = o + 2 // início de fibRgFcLcb
        val fcClx = i32(wd, base + 33 * 8)
        val lcbClx = i32(wd, base + 33 * 8 + 4)
        if (fcClx < 0 || lcbClx <= 0 || fcClx + lcbClx > tbl.size) throw IllegalArgumentException("Documento inválido")

        // tabela de trechos (Clx): pula as modificações (tipo 1) até a tabela de trechos (tipo 2)
        var p = fcClx
        val end = fcClx + lcbClx
        while (p < end && tbl[p].toInt() == 1) p += 3 + u16(tbl, p + 1)
        if (p >= end || tbl[p].toInt() != 2) throw IllegalArgumentException("Documento inválido")
        val plcLen = i32(tbl, p + 1)
        val plc = p + 5
        val n = (plcLen - 4) / 12
        val sb = StringBuilder()
        val cp1252 = charset("windows-1252")
        for (i in 0 until n) {
            val cpA = i32(tbl, plc + 4 * i)
            val cpB = i32(tbl, plc + 4 * (i + 1))
            var len = cpB - cpA
            if (len <= 0) continue
            var fc = i32(tbl, plc + 4 * (n + 1) + 8 * i + 2)
            if ((fc and 0x40000000) != 0) {
                fc = (fc and 0x3FFFFFFF) / 2 // texto de 8 bits (cp1252)
                if (fc < 0 || fc >= wd.size) continue
                if (fc + len > wd.size) len = wd.size - fc
                sb.append(String(wd, fc, len, cp1252))
            } else {
                if (fc < 0 || fc >= wd.size) continue
                if (fc + 2 * len > wd.size) len = (wd.size - fc) / 2
                sb.append(String(wd, fc, 2 * len, Charsets.UTF_16LE))
            }
        }
        return if (ccpText in 1..sb.length) sb.substring(0, ccpText) else sb.toString()
    }

    // ------------------------------------------------------------------ páginas

    private class Field {
        val instr = StringBuilder()
        var inResult = false
        var resultStart = 0
    }

    private val linkRegex = Regex("HYPERLINK\\s+\"([^\"]+)\"")

    private fun build(raw: String): EpubBook.Result {
        val paras = ArrayList<EpubBook.Para>()
        val links = ArrayList<EpubBook.LinkRef>()
        val sb = StringBuilder()
        var spans = ArrayList<Reflow.Span>()
        var forceBreak = false
        val fields = ArrayList<Field>()
        var prev07 = false

        fun flush() {
            var end = sb.length
            while (end > 0 && sb[end - 1].isWhitespace()) end--
            if (end > 0) {
                val ks = ArrayList<Reflow.Span>()
                for (s in spans) {
                    val e = minOf(s.end, end)
                    if (s.start in 0 until e) ks.add(Reflow.Span(s.start, e, s.id))
                }
                paras.add(EpubBook.Para(sb.substring(0, end), ks, forceBreak, null, emptyList()))
                forceBreak = false
            }
            sb.setLength(0)
            spans = ArrayList()
        }

        for (ch in raw) {
            val code = ch.code
            // campos: 0x13 começa (instrução), 0x14 separa (resultado), 0x15 termina
            if (code == 0x13) {
                fields.add(Field())
                prev07 = false
                continue
            }
            if (code == 0x14) {
                fields.lastOrNull()?.let { it.inResult = true; it.resultStart = sb.length }
                prev07 = false
                continue
            }
            if (code == 0x15) {
                val f = fields.removeLastOrNull()
                if (f != null && f.inResult && sb.length > f.resultStart) {
                    val m = linkRegex.find(f.instr.toString())
                    val url = m?.groupValues?.get(1)
                    if (url != null && (url.startsWith("http", true) || url.startsWith("mailto:", true))) {
                        links.add(EpubBook.LinkRef("", "", url))
                        spans.add(Reflow.Span(f.resultStart, sb.length, links.size - 1))
                    }
                }
                prev07 = false
                continue
            }
            val instrField = fields.firstOrNull { !it.inResult }
            if (instrField != null) { // dentro da instrução de um campo: não aparece no texto
                if (code >= 0x20) instrField.instr.append(ch)
                continue
            }
            when (code) {
                0x0D -> {
                    flush()
                    prev07 = false
                }
                0x07 -> { // fim de célula; dois seguidos = fim de linha da tabela
                    if (prev07) {
                        while (sb.endsWith(" | ")) sb.setLength(sb.length - 3)
                        flush()
                        prev07 = false
                    } else {
                        sb.append(" | ")
                        prev07 = true
                    }
                }
                0x0B -> {
                    sb.append('\n')
                    prev07 = false
                }
                0x0C -> {
                    flush()
                    forceBreak = true
                    prev07 = false
                }
                0x09 -> {
                    sb.append('\t')
                    prev07 = false
                }
                0x1E -> {
                    sb.append('-')
                    prev07 = false
                }
                0xA0 -> {
                    sb.append(' ')
                    prev07 = false
                }
                else -> {
                    if (code >= 0x20) sb.append(ch)
                    prev07 = false
                }
            }
        }
        flush()
        return EpubBook.finish(paras, links, emptyMap(), emptyMap())
    }
}
