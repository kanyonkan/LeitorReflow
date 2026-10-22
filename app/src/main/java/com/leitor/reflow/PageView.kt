package com.leitor.reflow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.ImageView
import kotlin.math.max
import kotlin.math.min

/**
 * Página do modo Original. Além da imagem, guarda a caixa das palavras para marcar texto:
 * pressione e segure sobre uma palavra e arraste para estender a marcação.
 */
class PageView(context: Context) : ImageView(context) {

    private var words: List<Word> = emptyList()
    private var pageW = 1f
    private var pageH = 1f
    private var maxRight = 0f

    private var selA = -1
    private var selB = -1
    private var dragging = false

    /** Avisa quando a marcação começa (done = false) e quando o dedo é solto (done = true). */
    var onSelection: ((PageView, Boolean) -> Unit)? = null

    private val paint = Paint().apply { color = 0x663B82F6 }

    private val longPress = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            val i = wordAt(e.x, e.y, 14f)
            if (i < 0) return
            selA = i
            selB = i
            dragging = true
            parent?.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
            onSelection?.invoke(this@PageView, false)
        }
    })

    fun setPageData(w: List<Word>, pw: Int, ph: Int) {
        words = w
        pageW = max(1, pw).toFloat()
        pageH = max(1, ph).toFloat()
        maxRight = if (w.isEmpty()) 0f else w.maxOf { it.x1 }
    }

    fun hasSelection(): Boolean = selA >= 0

    fun clearSelection() {
        if (selA < 0 && !dragging) return
        selA = -1
        selB = -1
        dragging = false
        invalidate()
    }

    /** Índice da palavra mais próxima do ponto (em pixels da view); -1 se estiver longe demais. */
    private fun wordAt(x: Float, y: Float, maxDist: Float): Int {
        if (words.isEmpty() || width == 0 || height == 0) return -1
        val px = x / width * pageW
        val py = y / height * pageH
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in words.indices) {
            val w = words[i]
            val dx = when {
                px < w.x0 -> w.x0 - px
                px > w.x1 -> px - w.x1
                else -> 0f
            }
            val dy = when {
                py < w.y0 -> w.y0 - py
                py > w.y1 -> py - w.y1
                else -> 0f
            }
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return if (best >= 0 && bestD <= maxDist * maxDist) best else -1
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        longPress.onTouchEvent(ev)
        if (dragging) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val i = wordAt(ev.x, ev.y, 120f)
                    if (i >= 0 && i != selB) {
                        selB = i
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    onSelection?.invoke(this, true)
                }
            }
            return true
        }
        return super.onTouchEvent(ev)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (selA < 0 || words.isEmpty() || pageW <= 0f) return
        val sx = width / pageW
        val sy = height / pageH
        val from = min(selA, selB)
        val to = max(selA, selB)
        for (i in from..min(to, words.size - 1)) {
            val w = words[i]
            canvas.drawRect(w.x0 * sx, w.y0 * sy, w.x1 * sx, w.y1 * sy, paint)
        }
    }

    /** Texto marcado: junta as linhas num parágrafo e desfaz hifenização no fim da linha. */
    fun selectedText(): String {
        if (selA < 0 || words.isEmpty()) return ""
        val from = min(selA, selB)
        val to = min(max(selA, selB), words.size - 1)
        val sb = StringBuilder()
        for (i in from..to) {
            val w = words[i]
            if (i > from) {
                val p = words[i - 1]
                val newLine = w.yc - p.yc > p.h * 0.6f || w.x0 < p.x0 - 2f
                if (!newLine) {
                    sb.append(' ')
                } else {
                    val last = sb.lastOrNull()
                    val shortLine = p.x1 < maxRight * 0.85f
                    when {
                        last == '-' && w.text.firstOrNull()?.isLowerCase() == true ->
                            sb.setLength(sb.length - 1)
                        shortLine && last != null && last in ".!?:;…”\")»" -> sb.append('\n')
                        else -> sb.append(' ')
                    }
                }
            }
            sb.append(w.text)
        }
        return sb.toString()
    }
}
