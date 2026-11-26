package com.leitor.reflow

import com.tom_roush.pdfbox.io.RandomAccessRead
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Leitura com acesso aleatório direto de um descritor de arquivo (sem copiar o arquivo).
 * Usa leituras por posição, então vários leitores podem compartilhar o mesmo [ch].
 * Fechar este objeto NÃO fecha o canal (quem abriu o canal é quem o fecha).
 */
class FdAccess(private val ch: FileChannel, private val len: Long) : RandomAccessRead {
    private var pos = 0L
    private var closed = false
    private val buf = ByteArray(65536)
    private var bufStart = 0L
    private var bufLen = 0

    private fun fill(at: Long): Boolean {
        if (at >= len) return false
        val bb = ByteBuffer.wrap(buf)
        var p = at
        var total = 0
        while (bb.hasRemaining()) {
            val n = ch.read(bb, p)
            if (n <= 0) break
            p += n
            total += n
        }
        bufStart = at
        bufLen = total
        return total > 0
    }

    override fun read(): Int {
        if (pos >= len) return -1
        if (pos < bufStart || pos >= bufStart + bufLen) {
            if (!fill(pos)) return -1
        }
        val b = buf[(pos - bufStart).toInt()].toInt() and 0xFF
        pos++
        return b
    }

    override fun read(b: ByteArray): Int = read(b, 0, b.size)

    override fun read(b: ByteArray, off: Int, length: Int): Int {
        if (length == 0) return 0
        if (pos >= len) return -1
        var done = 0
        while (done < length && pos < len) {
            if (pos < bufStart || pos >= bufStart + bufLen) {
                if (length - done >= buf.size) {
                    // pedido grande: lê direto no destino
                    val bb = ByteBuffer.wrap(b, off + done, length - done)
                    val n = ch.read(bb, pos)
                    if (n <= 0) break
                    pos += n
                    done += n
                    continue
                }
                if (!fill(pos)) break
            }
            val s = (pos - bufStart).toInt()
            val c = minOf(bufLen - s, length - done)
            System.arraycopy(buf, s, b, off + done, c)
            pos += c
            done += c
        }
        return if (done == 0) -1 else done
    }

    override fun getPosition(): Long = pos

    override fun seek(position: Long) {
        if (position < 0) throw java.io.IOException("Posição inválida: $position")
        pos = position
    }

    override fun peek(): Int {
        val r = read()
        if (r >= 0) pos--
        return r
    }

    override fun rewind(bytes: Int) {
        pos -= bytes
        if (pos < 0) pos = 0
    }

    override fun readFully(length: Int): ByteArray {
        val out = ByteArray(length)
        var got = 0
        while (got < length) {
            val n = read(out, got, length - got)
            if (n < 0) throw EOFException("Fim do arquivo antes de ler $length bytes")
            got += n
        }
        return out
    }

    override fun isEOF(): Boolean = pos >= len

    override fun available(): Int = (len - pos).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    override fun length(): Long = len

    override fun isClosed(): Boolean = closed

    override fun close() {
        closed = true
    }
}
