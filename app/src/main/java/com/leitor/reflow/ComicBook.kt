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

    private val imgExt = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp")

    private fun isImage(n: String): Boolean {
        val l = n.lowercase()
        if (l.substringAfterLast('/').startsWith(".")) return false
        return imgExt.any { l.endsWith(it) } && !l.startsWith("__macosx/")
    }

    /** Compara nomes "pagina2" antes de "pagina10". */
    private val natural = Comparator<String> { a, b ->
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

    class Loaded(val result: EpubBook.Result, val zip: File)

    /** [src] é o arquivo copiado; [work] é onde guardar o zip refeito (só usado no RAR). */
    fun load(src: File, work: File): Loaded {
        val head = ByteArray(4)
        src.inputStream().use { it.read(head) }
        val isZip = head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
        val isRar = head[0] == 0x52.toByte() && head[1] == 0x61.toByte() && head[2] == 0x72.toByte()
        val zipFile: File = when {
            isZip -> src
            isRar -> {
                repack(src, work)
                work
            }
            else -> throw IllegalArgumentException("Formato de quadrinhos não reconhecido")
        }
        val names = ArrayList<String>()
        ZipFile(zipFile).use { z ->
            for (e in z.entries()) if (!e.isDirectory && isImage(e.name)) names.add(e.name)
        }
        if (names.isEmpty()) throw IllegalArgumentException("Nenhuma imagem encontrada")
        names.sortWith(natural)
        val pages = names.map { EpubBook.Page("", emptyList(), it) }
        return Loaded(EpubBook.Result(pages, emptyList()), zipFile)
    }

    private fun repack(rar: File, out: File) {
        try {
            Archive(rar).use { arc ->
                if (arc.mainHeader.isEncrypted) throw IllegalArgumentException("Arquivo protegido por senha")
                ZipOutputStream(out.outputStream().buffered()).use { zos ->
                    zos.setLevel(0)
                    val used = HashSet<String>()
                    var h = arc.nextFileHeader()
                    while (h != null) {
                        if (!h.isDirectory) {
                            val name = h.fileName.replace('\\', '/')
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
            throw IllegalArgumentException("Não foi possível ler este CBR (o formato RAR5 não é suportado)")
        }
    }
}
