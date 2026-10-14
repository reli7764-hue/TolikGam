package com.tolik.mishar

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** A vinyl record with Tolik on the label. The whole view rotates when a song is drawn. */
class RecordView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val face = BitmapFactory.decodeResource(resources, R.drawable.tolik)
    private val vinyl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF161616.toInt() }
    private val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF2C2C2C.toInt()
    }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x26FFFFFF
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFE8336D.toInt()
    }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    private val box = RectF()

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = min(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy)

        canvas.drawCircle(cx, cy, r, vinyl)
        groove.strokeWidth = r * 0.012f
        var g = r * 0.96f
        while (g > r * 0.62f) {
            canvas.drawCircle(cx, cy, g, groove)
            g -= r * 0.04f
        }
        // two light reflections so you can see it spin
        shine.strokeWidth = r * 0.22f
        box.set(cx - r * 0.79f, cy - r * 0.79f, cx + r * 0.79f, cy + r * 0.79f)
        canvas.drawArc(box, 200f, 45f, false, shine)
        canvas.drawArc(box, 20f, 45f, false, shine)

        // label: Tolik
        val lr = r * 0.58f
        clip.reset()
        clip.addCircle(cx, cy, lr, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        box.set(cx - lr, cy - lr, cx + lr, cy + lr)
        canvas.drawBitmap(face, null, box, bmpPaint)
        canvas.restore()
        ring.strokeWidth = r * 0.035f
        canvas.drawCircle(cx, cy, lr, ring)
    }
}
