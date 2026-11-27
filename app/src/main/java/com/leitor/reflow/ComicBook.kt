package com.leitor.reflow

import com.github.junrar.Archive
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Quadrinhos (.cbz e .cbr): cada imagem do arquivo vira uma página, na ordem natural dos nomes.
 * O CBZ é um zip; o CBR é RAR (lido com a biblioteca junrar) e é regravado como zip sem compressão,
 * para que as figuras possam ser lidas depois com acesso direto, como no EPUB.
 * Um arquivo com extensão .cbr que na verdade é zip (comum) também é aceito.
 */
object ComicBook {

    private val imgExt = listOf(".jpg", ".jpeg", ".jpe", ".jfif", ".png", ".webp", ".gif", ".bmp")
    private val notImgExt = listOf(
        ".xml", ".txt", ".nfo", ".db", ".ini", ".html", ".htm", ".json", ".url", ".sfv", ".md5", ".md",
        ".pdf", ".zip", ".rar", ".cbz", ".cbr", ".thumbs", ".ds_store", ".lnk", ".log", ".css", ".js"
    )

    fun isImage(n: String): Boolean {
        val l = n.lowercase()
        if (l.substringAfterLast('/').startsWith(".")) return false
        return imgExt.any { l.endsWith(it) } && !l.startsWith("__macosx/")
    }

    /** Compara nomes "pagina2" antes de "pagina10". */
    val natural = Comparator<String> { a, b ->
        val x = a.lowercase()
        val y = b.lowercase()
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            if (x[i].isDigit() && y[j].isDigit()) {
                var si = i
                while (si < x.length && x[si].isDigit()) si++
                var sj = j
                while (sj < y.length && y[sj].isDigit()) sj++
                val nx = x.substring(i, si).trimStart('0')
                val ny = y.substring(j, sj).trimStart('0')
                if (nx.length != ny.length) return@Comparator nx.length - ny.length
                val c = nx.compareTo(ny)
                if (c != 0) return@Comparator c
                i = si
                j = sj
            } else {
                if (x[i] != y[j]) return@Comparator x[i].compareTo(y[j])
                i++
                j++
            }
        }
        (x.length - i) - (y.length - j)
    }

    class Loaded(val result: EpubBook.Result, val zip: File, val charset: java.nio.charset.Charset = Charsets.UTF_8)

    /** [src] é o arquivo copiado; [work] é onde guardar o zip refeito (só usado no RAR). */
    fun load(src: File, work: File, progress: ((Int) -> Unit)? = null): Loaded {
        val head = ByteArray(8)
        val got = src.inputStream().use { it.read(head) }
        val hex = head.take(maxOf(got, 0)).joinToString(" ") { "%02X".format(it) }
        val isZip = head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
        val isRar = head[0] == 0x52.toByte() && head[1] == 0x61.toByte() && head[2] == 0x72.toByte()
        val is7z = head[0] == 0x37.toByte() && head[1] == 0x7A.toByte() && head[2] == 0xBC.toByte()
        val zipFile: File = when {
            isZip -> src
            isRar -> {
                // primeiro a biblioteca nativa (bem mais rápida); se falhar, a biblioteca antiga
                var ok = false
                val tz = System.currentTimeMillis()
                try {
                    repack5(src, work, progress)
                    ok = ZipFile(work).use { it.size() > 0 }
                } catch (e: Throwable) {
                }
                if (!ok) {
                    val tj = System.currentTimeMillis()
                    repack(src, work, progress)
                }
                work
            }
            is7z -> {
                try {
                    repack5(src, work, progress)
                } catch (e: Throwable) {
                    // a primeira biblioteca (libarchive) recusou o arquivo: tenta a segunda (commons-compress)
                    try { work.delete() } catch (_: Throwable) {}
                    try {
                        repack7z(src, work, progress)
                    } catch (e2: Throwable) {
                        throw IllegalStateException("${e.message} (segunda biblioteca: ${e2.message})", e)
                    }
                }
                work
            }
            else -> {
                // formato não identificado pelo início (ex.: RAR com bytes extras antes, CB7 renomeado): tenta libarchive
                try { repack5(src, work, progress) } catch (e: Throwable) {
                    throw IllegalArgumentException("Formato de quadrinhos não reconhecido (início $hex)")
                }
                work
            }
        }
        // nomes fora do padrão UTF-8 derrubam a leitura da lista de arquivos (MALFORMED): refaz com outra codificação
        var cs: java.nio.charset.Charset = Charsets.UTF_8
        var names = ArrayList<String>()
        var total = 0
        var lastErr: Throwable? = null
        var done = false
        for (c in listOf(Charsets.UTF_8, Charsets.ISO_8859_1)) {
            try {
                val r = scan(zipFile, c)
                names = r.first
                total = r.second
                cs = c
                done = true
                break
            } catch (e: Throwable) {
                lastErr = e
            }
        }
        if (!done) throw IllegalArgumentException("Não foi possível ler a lista de páginas (${lastErr?.message})")
        if (names.isEmpty()) throw IllegalArgumentException("Nenhuma imagem encontrada (entradas: $total)")
        names.sortWith(natural)
        val pages = names.map { EpubBook.Page("", emptyList(), it) }
        return Loaded(EpubBook.Result(pages, emptyList()), zipFile, cs)
    }

    /** Lista as imagens do zip com a codificação [cs]; devolve (nomes, total de entradas). */
    private fun scan(zipFile: File, cs: java.nio.charset.Charset): Pair<ArrayList<String>, Int> {
        val names = ArrayList<String>()
        val others = ArrayList<String>()
        var total = 0
        val methods = HashSet<Int>()
        ZipFile(zipFile, cs).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory) continue
                total++
                methods.add(e.method)
                val l = e.name.lowercase()
                if (isImage(e.name)) names.add(e.name)
                else if (e.size > 2048 && notImgExt.none { l.endsWith(it) } &&
                    !l.substringAfterLast('/').startsWith(".") && !l.startsWith("__macosx/")
                ) others.add(e.name)
            }
            // nenhum arquivo com extensão de imagem: confere se os demais abrem como imagem
            if (names.isEmpty() && others.isNotEmpty()) {
                for (n in others) {
                    val e = z.getEntry(n) ?: continue
                    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    try {
                        z.getInputStream(e).use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
                    } catch (_: Throwable) {
                    }
                    if (o.outWidth > 0 && o.outHeight > 0) names.add(n)
                }
            }
        }
        return Pair(names, total)
    }

    private fun isRar5(f: File): Boolean {
        val h = ByteArray(8)
        val n = f.inputStream().use { it.read(h) }
        return n == 8 && h[3] == 0x21.toByte() && h[4] == 0x1A.toByte() && h[5] == 0x07.toByte() && h[6] == 0x01.toByte()
    }

    private fun repack5(rar: File, out: File, progress: ((Int) -> Unit)? = null) {
        val arc = me.zhanghai.android.libarchive.Archive.readNew()
        try {
            me.zhanghai.android.libarchive.Archive.readSupportFilterAll(arc)
            me.zhanghai.android.libarchive.Archive.readSupportFormatAll(arc)
            android.os.ParcelFileDescriptor.open(rar, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                me.zhanghai.android.libarchive.Archive.readOpenFd(arc, pfd.fd, 65536)
                val buf = java.nio.ByteBuffer.allocateDirect(65536)
                val tmp = ByteArray(65536)
                ZipOutputStream(out.outputStream().buffered()).use { zos ->
                    zos.setLevel(0)
                    val used = HashSet<String>()
                    val srcLen = rar.length().coerceAtLeast(1L)
                    var written = 0L
                    var lastPct = -1
                    var e = me.zhanghai.android.libarchive.Archive.readNextHeader(arc)
                    while (e != 0L) {
                        val name: String = (me.zhanghai.android.libarchive.ArchiveEntry.pathnameUtf8(e) ?: "").replace('\\', '/')
                        val size = me.zhanghai.android.libarchive.ArchiveEntry.stat(e).stSize
                        if (size > 0 && isImage(name) && used.add(name)) {
                            zos.putNextEntry(ZipEntry(name))
                            var total = 0L
                            while (total < size) {
                                buf.clear()
                                me.zhanghai.android.libarchive.Archive.readData(arc, buf)
                                val n = if (buf.position() > 0) buf.position()
                                else if (buf.limit() < buf.capacity()) buf.limit() else 0
                                if (n <= 0) break
                                buf.flip().let { if (it.limit() == 0) { buf.limit(n); buf.position(0) } }
                                buf.get(tmp, 0, n)
                                zos.write(tmp, 0, n)
                                total += n
                                written += n
                                val pct = (99L * written / srcLen).toInt().coerceAtMost(99)
                                if (pct != lastPct) { lastPct = pct; progress?.invoke(pct) }
                            }
                            zos.closeEntry()
                        }
                        e = me.zhanghai.android.libarchive.Archive.readNextHeader(arc)
                    }
                }
            }
        } finally {
            try { me.zhanghai.android.libarchive.Archive.readClose(arc) } catch (_: Throwable) {}
            try { me.zhanghai.android.libarchive.Archive.free(arc) } catch (_: Throwable) {}
        }
    }

    private fun repack(rar: File, out: File, progress: ((Int) -> Unit)? = null) {
        try {
            if (isRar5(rar)) { repack5(rar, out, progress); return }
            val rlen = rar.length().coerceAtLeast(1L)
            var lastP = -1
            Archive(rar).use { arc ->
                if (arc.mainHeader.isEncrypted) throw IllegalArgumentException("Arquivo protegido por senha")
                ZipOutputStream(out.outputStream().buffered()).use { zos ->
                    zos.setLevel(0)
                    val used = HashSet<String>()
                    var h = arc.nextFileHeader()
                    while (h != null) {
                        if (!h.isDirectory) {
                            val name = h.fileName.replace('\\', '/')
                            val pp = (99L * h.positionInFile / rlen).toInt().coerceIn(0, 99)
                            if (pp != lastP) { lastP = pp; progress?.invoke(pp) }
                            if (isImage(name) && used.add(name)) {
                                zos.putNextEntry(ZipEntry(name))
                                arc.extractFile(h, zos)
                                zos.closeEntry()
                            }
                        }
                        h = arc.nextFileHeader()
                    }
                }
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Throwable) {
            try {
                repack5(rar, out, progress)
                return
            } catch (e2: Throwable) {
            }
            throw IllegalArgumentException("Não foi possível ler este CBR (arquivo corrompido ou formato RAR não suportado)")
        }
    }

    /** Segunda leitura de 7z (commons-compress): usada só quando a primeira falha. */
    private fun repack7z(src: File, out: File, progress: ((Int) -> Unit)?) {
        val total = src.length().coerceAtLeast(1L)
        var done = 0L
        var lastP = -1
        val used = HashSet<String>()
        val buf = ByteArray(65536)
        var n = 0
        org.apache.commons.compress.archivers.sevenz.SevenZFile(src).use { sz ->
            ZipOutputStream(out.outputStream().buffered(65536)).use { zos ->
                zos.setLevel(0)
                var e = sz.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.hasStream()) {
                        val name = (e.name ?: "").replace('\\', '/')
                        if (isImage(name) && used.add(name)) {
                            zos.putNextEntry(ZipEntry(name))
                            var left = e.size
                            while (left > 0) {
                                val r = sz.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (r < 0) break
                                zos.write(buf, 0, r)
                                left -= r
                                done += r
                            }
                            zos.closeEntry()
                            n++
                            val pp = (99L * done / total).toInt().coerceIn(0, 99)
                            if (pp != lastP) { lastP = pp; progress?.invoke(pp) }
                        }
                    }
                    e = sz.nextEntry
                }
            }
        }
        if (n == 0) throw IllegalStateException("nenhuma imagem encontrada")
    }
}
