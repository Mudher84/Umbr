package app.umbra.browser

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * Shiny liquid that flows into the address pill from start to end while a page loads:
 * a sky-to-violet water with a rippling front, moving ripples and a glossy streak of light.
 */
class LiquidView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private val dp = resources.displayMetrics.density
    private var shown = 0f          // fade in/out, 0..1
    private var target = 0f         // progress asked for, 0..1
    private var level = 0f          // progress drawn, eases toward target
    private var phase = 0f          // 0..1, loops
    private var loading = false

    private val clip = Path()
    private val water = Path()
    private val rect = RectF()
    private val waterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val ripple = Path()

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            step()
            invalidate()
        }
    }

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setProgress(progress: Int, isLoading: Boolean) {
        if (isLoading) {
            if (!loading && shown == 0f) level = 0f
            target = (progress.coerceIn(0, 100) / 100f).coerceAtLeast(0.08f)
            if (!ticker.isStarted && isAttachedToWindow) ticker.start()
        } else if (!ticker.isStarted) {
            shown = 0f; level = 0f; target = 0f
        }
        loading = isLoading
        invalidate()
    }

    fun reset() {
        loading = false; target = 0f; level = 0f; shown = 0f
        ticker.cancel()
        invalidate()
    }

    private fun step() {
        level += (target - level) * 0.12f
        if (loading) {
            shown = (shown + 0.08f).coerceAtMost(1f)
        } else {
            level += (1f - level) * 0.2f
            shown = (shown - 0.05f).coerceAtLeast(0f)
            if (shown == 0f) { level = 0f; target = 0f; ticker.cancel() }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (loading) ticker.start()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        clip.reset()
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.addRoundRect(rect, h / 2f, h / 2f, Path.Direction.CW)
        waterPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(Color.argb(140, 56, 189, 248), Color.argb(148, 99, 102, 241), Color.argb(158, 168, 85, 247)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        glossPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.argb(150, 255, 255, 255), Color.argb(30, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(46, 255, 255, 255)),
            floatArrayOf(0f, 0.46f, 0.54f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (shown <= 0f || width == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val alpha = (shown * 255).toInt()
        val fill = (level * w).coerceIn(0f, w)

        canvas.save()
        canvas.clipPath(clip)
        if (rtl) canvas.scale(-1f, 1f, w / 2f, h / 2f)

        // water body with a wavy front edge
        val amp = 3.5f * dp
        val wave = 24f * dp
        water.reset()
        water.moveTo(0f, 0f)
        water.lineTo(fill, 0f)
        var y = 0f
        while (y <= h) {
            val x = fill + amp * sin(((y / wave) + phase * 1.8f) * 2f * PI.toFloat())
            water.lineTo(x, y)
            y += 2f * dp
        }
        water.lineTo(fill, h)
        water.lineTo(0f, h)
        water.close()
        waterPaint.alpha = alpha
        canvas.drawPath(water, waterPaint)

        canvas.save()
        canvas.clipPath(water)
        // two ripples drifting along the water
        val len = 90f * dp
        val shift = phase * len
        drawRipple(canvas, fill, h * 0.65f, 2.6f * dp, len, shift, 1.6f * dp, (0.45f * alpha).toInt())
        drawRipple(canvas, fill, h * 0.35f, -2.2f * dp, len, shift, 1.2f * dp, (0.22f * alpha).toInt())
        // glossy top like glass
        glossPaint.alpha = alpha
        canvas.drawRect(0f, 0f, fill + amp, h, glossPaint)
        // a streak of light running through
        val streak = 160f * dp
        val sx = -streak + (w + 2 * streak) * ((phase * 1.23f) % 1f)
        shinePaint.shader = LinearGradient(
            sx, 0f, sx + streak, h * 0.4f,
            intArrayOf(Color.TRANSPARENT, Color.argb((0.75f * alpha).toInt(), 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0.38f, 0.48f, 0.58f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, fill + amp, h, shinePaint)
        canvas.restore()

        canvas.restore()
    }

    private fun drawRipple(c: Canvas, fill: Float, mid: Float, amp: Float, len: Float, shift: Float, stroke: Float, a: Int) {
        ripple.reset()
        var x = -len + shift
        ripple.moveTo(x, mid)
        while (x < fill + len) {
            ripple.quadTo(x + len / 4, mid - amp, x + len / 2, mid)
            ripple.quadTo(x + 3 * len / 4, mid + amp, x + len, mid)
            x += len
        }
        ripplePaint.strokeWidth = stroke
        ripplePaint.alpha = a
        c.drawPath(ripple, ripplePaint)
    }
}
