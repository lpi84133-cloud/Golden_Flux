package com.goldenflux.goldenfluxgame.flux.entry

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import com.goldenflux.goldenfluxgame.R

/**
 * Branded splash / loading view. Two modes:
 *
 *  * indeterminate — the bar eases toward ~92% with a moving shimmer and
 *    never finishes on its own. The router runs it while it routes; on
 *    [complete] the bar runs out to 100% and hands over after a 420 ms hold
 *    (kotlin_gray_pitfalls.mdc #26). The user is never moved on by a bar
 *    stopped at 70% and never left staring at a full one either.
 *  * timed — the bar fills over [totalMs] then calls [onComplete]. Used by
 *    the notification-permission screen while the shell page is coming up
 *    behind it.
 */
class LaunchSplash(
    ctx: Context,
    private val totalMs: Long = 2400L,
    private val indeterminate: Boolean = false,
    private val onComplete: () -> Unit = {}
) : View(ctx) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val gold = 0xFFF5C542.toInt()

    private var startedAt = 0L
    private var done = false

    private var closingFrom = 0f
    private var closingAt = 0L
    private var handOver: (() -> Unit)? = null

    private var shown = 0f

    // Cached orientation-aware background bitmaps. Decoded once — decoding in
    // onDraw stutters the bar (custom_screens.md §2).
    private var bgPortrait: Bitmap? = null
    private var bgLandscape: Bitmap? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startedAt = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bgPortrait?.recycle(); bgPortrait = null
        bgLandscape?.recycle(); bgLandscape = null
    }

    /** Routing done: run the bar out to 100%, hold briefly, then hand over. */
    fun complete(after: () -> Unit) {
        if (closingAt != 0L) return
        handOver = after
        closingFrom = shown
        closingAt = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        drawArtwork(canvas, w, h)

        val elapsed = SystemClock.uptimeMillis() - startedAt

        // Caption dots — always animating.
        val dots = ".".repeat(((elapsed / 400L) % 4L).toInt())
        text.textSize = h * 0.028f
        text.color = gold
        text.setShadowLayer(h * 0.006f, 0f, h * 0.003f, Color.BLACK)
        val caption = context.getString(R.string.flux_loading) + dots
        canvas.drawText(caption, w / 2f, h * 0.885f, text)
        text.clearShadowLayer()

        if (indeterminate) {
            val progress = if (closingAt != 0L) {
                val run = ((SystemClock.uptimeMillis() - closingAt).toFloat() / CLOSE_MS)
                    .coerceIn(0f, 1f)
                closingFrom + (1f - closingFrom) * run
            } else {
                val t = elapsed / 1000f
                (0.92f * (1f - Math.exp((-t / 1.1f).toDouble()).toFloat())).coerceIn(0f, 0.95f)
            }
            shown = progress
            drawBar(canvas, w, h, progress, shimmer = true, phase = elapsed)
            if (progress >= 1f && !done) {
                done = true
                val cb = handOver
                handOver = null
                postDelayed({ cb?.invoke() }, HOLD_MS)
                return
            }
        } else {
            val progress = (elapsed.toFloat() / totalMs).coerceIn(0f, 1f)
            shown = progress
            drawBar(canvas, w, h, progress, shimmer = false, phase = 0L)
            if (progress >= 1f && !done) {
                done = true
                post { onComplete() }
                return
            }
        }
        postInvalidateOnAnimation()
    }

    private fun drawArtwork(canvas: Canvas, w: Float, h: Float) {
        val landscape = w > h
        val res = if (landscape) R.drawable.flux_loading_landscape else R.drawable.flux_loading_portrait

        val bmp = cached(res, landscape)
        if (bmp == null) {
            paint.shader = LinearGradient(0f, 0f, 0f, h,
                0xFF2A1650.toInt(), 0xFF120823.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
            return
        }
        // CENTER_CROP: scale to cover, then centre.
        val scale = maxOf(w / bmp.width, h / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        canvas.drawBitmap(
            bmp, null,
            RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f),
            null
        )
    }

    private fun cached(res: Int, landscape: Boolean): Bitmap? {
        val existing = if (landscape) bgLandscape else bgPortrait
        if (existing != null) return existing
        val fresh = runCatching {
            BitmapFactory.decodeResource(resources, res, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            })
        }.getOrNull() ?: return null
        if (landscape) bgLandscape = fresh else bgPortrait = fresh
        return fresh
    }

    private fun drawBar(
        canvas: Canvas, w: Float, h: Float, progress: Float,
        shimmer: Boolean, phase: Long
    ) {
        val barW = w * 0.62f
        val barH = h * 0.022f
        val x0 = (w - barW) / 2f
        val y0 = h * 0.915f
        val r = barH / 2f

        paint.style = Paint.Style.FILL
        paint.color = 0x88000000.toInt()
        canvas.drawRoundRect(x0, y0, x0 + barW, y0 + barH, r, r, paint)

        if (progress > 0f) {
            val fillW = barW * progress
            paint.shader = LinearGradient(
                x0, y0, x0 + barW, y0,
                0xFFB8871A.toInt(), gold, Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(x0, y0, x0 + fillW, y0 + barH, r, r, paint)
            paint.shader = null

            if (shimmer && fillW > barH) {
                val sw = barW * 0.18f
                val cycle = 1400f
                val ph = (phase % cycle.toLong()) / cycle
                val cx = x0 + (fillW + sw) * ph - sw
                val left = cx.coerceIn(x0, x0 + fillW)
                val right = (cx + sw).coerceIn(x0, x0 + fillW)
                if (right > left) {
                    paint.shader = LinearGradient(
                        left, y0, right, y0,
                        0x00FFFFFF, 0x66FFFFFF, Shader.TileMode.CLAMP
                    )
                    canvas.drawRoundRect(left, y0, right, y0 + barH, r, r, paint)
                    paint.shader = null
                }
            }
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.0035f
        paint.color = gold
        canvas.drawRoundRect(x0, y0, x0 + barW, y0 + barH, r, r, paint)
        paint.style = Paint.Style.FILL
    }

    private companion object {
        const val CLOSE_MS = 280f
        const val HOLD_MS  = 420L
    }
}
