package com.leitor.reflow

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Guarda o texto do refluxo já extraído, para o mesmo PDF abrir quase na hora
 * da segunda vez em diante. A VERSION muda sempre que o método de extração muda.
 */
object ReflowCache {

    private const val VERSION = 3
    private const val KEEP_FILES = 25

    class Loaded(
        val texts: List<String>,
        val spans: List<List<Reflow.Span>>,
        val targets: List<LinkTarget>
    )

    fun file(ctx: Context, key: String): File {
        val dir = File(ctx.cacheDir, "reflow")
        dir.mkdirs()
        val md = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        val hex = md.joinToString("") { "%02x".format(it) }
        return File(dir, "${hex}_v$VERSION.bin")
    }

    fun save(
        f: File,
        texts: List<String>,
        spans: List<List<Reflow.Span>>,
        targets: List<LinkTarget>
    ) {
        try {
            val tmp = File(f.path + ".tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { o ->
                o.writeInt(VERSION)
                o.writeInt(texts.size)
                for (i in texts.indices) {
                    val b = texts[i].toByteArray(Charsets.UTF_8)
                    o.writeInt(b.size)
                    o.write(b)
                    o.writeInt(spans[i].size)
                    for (s in spans[i]) {
                        o.writeInt(s.start)
                        o.writeInt(s.end)
                        o.writeInt(s.id)
                    }
                }
                o.writeInt(targets.size)
                for (t in targets) {
                    o.writeInt(t.page)
                    val u = t.uri
                    if (u == null) {
                        o.writeInt(-1)
                    } else {
                        val ub = u.toByteArray(Charsets.UTF_8)
                        o.writeInt(ub.size)
                        o.write(ub)
                    }
                }
            }
            f.delete()
            tmp.renameTo(f)
            prune(f.parentFile)
        } catch (_: Throwable) {
        }
    }

    fun load(f: File): Loaded? {
        if (!f.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(f))).use { i ->
                if (i.readInt() != VERSION) return null
                val n = i.readInt()
                if (n < 0 || n > 100000) return null
                val texts = ArrayList<String>(n)
                val spans = ArrayList<List<Reflow.Span>>(n)
                for (p in 0 until n) {
                    val len = i.readInt()
                    if (len < 0 || len > 50_000_000) return null
                    val b = ByteArray(len)
                    i.readFully(b)
                    texts.add(String(b, Charsets.UTF_8))
                    val sc = i.readInt()
                    if (sc < 0 || sc > 1_000_000) return null
                    val list = ArrayList<Reflow.Span>(sc)
                    for (k in 0 until sc) {
                        val s = i.readInt()
                        val e = i.readInt()
                        val id = i.readInt()
                        list.add(Reflow.Span(s, e, id))
                    }
                    spans.add(list)
                }
                val tc = i.readInt()
                if (tc < 0 || tc > 10_000_000) return null
                val targets = ArrayList<LinkTarget>(tc)
                for (k in 0 until tc) {
                    val page = i.readInt()
                    val ul = i.readInt()
                    if (ul < 0) {
                        targets.add(LinkTarget(page, null))
                    } else {
                        val ub = ByteArray(ul)
                        i.readFully(ub)
                        targets.add(LinkTarget(page, String(ub, Charsets.UTF_8)))
                    }
                }
                f.setLastModified(System.currentTimeMillis())
                Loaded(texts, spans, targets)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun prune(dir: File?) {
        val files = dir?.listFiles { x -> x.name.endsWith(".bin") } ?: return
        if (files.size <= KEEP_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - KEEP_FILES).forEach { it.delete() }
    }
}
