package com.leitor.reflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/** Imagem com zoom por pinça, arrasto e toque duplo (usada para ampliar figuras do EPUB). */
class ZoomImageView(context: Context) : View(context) {
    private var bmp: Bitmap? = null
    private val m = Matrix()
    private val v = FloatArray(9)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var fitScale = 1f
    private var lastX = 0f
    private var lastY = 0f

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAt(d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (curScale() > fitScale * 1.3f) fit() else zoomAt(fitScale * 3f / curScale(), e.x, e.y)
            return true
        }
    })

    fun setBitmap(b: Bitmap?) {
        bmp = b
        fit()
    }

    private fun curScale(): Float {
        m.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    fun fit() {
        val b = bmp
        if (b == null || width == 0 || height == 0) {
            invalidate()
            return
        }
        fitScale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        m.reset()
        m.postScale(fitScale, fitScale)
        clamp()
        invalidate()
    }

    /** Zoom pelos botões: em torno do centro da tela. */
    fun zoomBy(f: Float) = zoomAt(f, width / 2f, height / 2f)

    private fun zoomAt(f: Float, fx: Float, fy: Float) {
        if (bmp == null) return
        val cur = curScale()
        val target = (cur * f).coerceIn(fitScale, fitScale * 12f)
        val k = target / cur
        m.postScale(k, k, fx, fy)
        clamp()
        invalidate()
    }

    private fun clamp() {
        val b = bmp ?: return
        m.getValues(v)
        val s = v[Matrix.MSCALE_X]
        val w = b.width * s
        val h = b.height * s
        var tx = v[Matrix.MTRANS_X]
        var ty = v[Matrix.MTRANS_Y]
        tx = if (w <= width) (width - w) / 2f else tx.coerceIn(width - w, 0f)
        ty = if (h <= height) (height - h) / 2f else ty.coerceIn(height - h, 0f)
        m.postTranslate(tx - v[Matrix.MTRANS_X], ty - v[Matrix.MTRANS_Y])
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fit()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bmp ?: return
        canvas.drawBitmap(b, m, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        taps.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(i)
                lastY = e.getY(i)
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && !scaler.isInProgress) {
                    m.postTranslate(e.x - lastX, e.y - lastY)
                    clamp()
                    invalidate()
                    lastX = e.x
                    lastY = e.y
                }
            }
        }
        return true
    }
}
