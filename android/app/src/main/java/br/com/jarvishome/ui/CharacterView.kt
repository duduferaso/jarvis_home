package br.com.jarvishome.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import br.com.jarvishome.domain.AssistantState
import br.com.jarvishome.domain.StateBus
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Rosto do Jarvis desenhado com vetores (sem imagens estáticas por expressão).
 * Cada parte (olhos, sobrancelhas, boca, efeitos) é um parâmetro que interpola
 * suavemente até o alvo definido pelo estado atual.
 */
class CharacterView(context: Context) : View(context) {

    var reduceMotion = false
    /** Tela com painéis de informação: rosto um pouco menor e mais alto. */
    var compact = false

    private class Param(var v: Float, private val speed: Float) {
        var target = v
        fun step(dt: Float) { v += (target - v) * min(1f, dt * speed) }
    }

    private val eyeOpen = Param(1f, 10f)
    private val eyeScale = Param(1f, 6f)
    private val lookX = Param(0f, 5f)
    private val lookY = Param(0f, 5f)
    private val mouthOpen = Param(0f, 22f)
    private val smile = Param(0.35f, 5f)
    private val mouthTilt = Param(0f, 5f)
    private val happy = Param(0f, 7f)
    private val leftEyeH = Param(1f, 6f)
    private val browL = Param(0f, 6f)
    private val browR = Param(0f, 6f)
    private val alert = Param(0f, 4f)
    private val glow = Param(0.7f, 3f)
    private val rings = Param(0f, 5f)
    private val think = Param(0f, 4f)
    private val sleep = Param(0f, 2f)
    private val layoutCy = Param(0.45f, 3f)
    private val layoutS = Param(0.62f, 3f)
    private val layoutW = Param(1f, 3f)
    private val params = listOf(eyeOpen, eyeScale, lookX, lookY, mouthOpen, smile, mouthTilt, happy,
        leftEyeH, browL, browR, alert, glow, rings, think, sleep, layoutCy, layoutS, layoutW)

    private val cyan = Color.rgb(34, 229, 255)
    private val amber = Color.rgb(255, 176, 32)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val path = Path()
    private val glowBitmap: Bitmap = makeGlowBitmap()

    private var time = 0f
    private var lastFrame = 0L
    private var nextBlink = 2000L
    private var blinkStart = -1L
    private var nextSaccade = 0L
    private var saccadeX = 0f
    private var saccadeY = 0f
    private var running = false

    private fun makeGlowBitmap(): Bitmap {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(size / 2f, size / 2f, size / 2f,
            intArrayOf(Color.WHITE, Color.argb(90, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        return bmp
    }

    // ------------------------------------------------------------------ laço de animação

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            tick()
            invalidate()
            val st = StateBus.state
            val delay = when {
                reduceMotion -> 66L
                st == AssistantState.IDLE || st == AssistantState.SLEEPING -> 40L
                else -> 25L
            }
            postDelayed(this, delay)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        lastFrame = SystemClock.uptimeMillis()
        post(frame)
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        val visible = visibility == VISIBLE
        if (visible && !running) {
            running = true
            lastFrame = SystemClock.uptimeMillis()
            post(frame)
        } else if (!visible) {
            running = false
            removeCallbacks(frame)
        }
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrame).coerceIn(1, 100)) / 1000f
        lastFrame = now
        time += dt
        applyStateTargets(now)
        for (p in params) p.step(dt)
    }

    private fun applyStateTargets(now: Long) {
        var eOpen = 1f; var eScale = 1f; var lx = 0f; var ly = 0f; var mOpen = 0f
        var sm = 0.35f; var tilt = 0f; var hp = 0f; var lEye = 1f; var bl = 0f; var br = 0f
        var al = 0f; var gl = 0.7f; var rg = 0f; var th = 0f; var sl = 0f
        var allowBlink = true

        if (now > nextSaccade) {
            val r = Math.random()
            saccadeX = if (reduceMotion) 0f else ((Math.random() * 2 - 1) * 0.6).toFloat()
            saccadeY = if (reduceMotion) 0f else ((Math.random() * 2 - 1) * 0.35).toFloat()
            nextSaccade = now + (2500 + r * 4500).toLong()
        }

        when (StateBus.state) {
            AssistantState.IDLE -> {
                lx = saccadeX * 0.5f + if (reduceMotion) 0f else sin(time * 0.35f) * 0.08f
                ly = saccadeY * 0.4f
            }
            AssistantState.LISTENING -> {
                eOpen = 1.08f; eScale = 1.12f + StateBus.micLevel * 0.06f; sm = 0.2f; gl = 1f
                rg = 1f
            }
            AssistantState.THINKING -> {
                eOpen = 0.82f; eScale = 0.96f; sm = 0f; th = 1f; gl = 0.85f
                lx = 0.55f * cos(time * 1.7f)
                ly = -0.45f + 0.12f * sin(time * 3.4f)
                allowBlink = false
            }
            AssistantState.SPEAKING -> {
                mOpen = StateBus.mouthLevel; sm = 0.2f; gl = 0.85f + StateBus.mouthLevel * 0.15f
                lx = saccadeX * 0.2f
            }
            AssistantState.HAPPY -> {
                hp = 1f; sm = 1f; eScale = 1.05f; gl = 1f
                allowBlink = false
            }
            AssistantState.CONFUSED -> {
                lEye = 0.55f; br = 1f; sm = -0.15f; tilt = 0.6f; lx = 0.25f; ly = -0.1f
            }
            AssistantState.ERROR -> {
                al = 1f; eOpen = 0.72f; bl = -0.8f; br = -0.8f; sm = -0.45f
            }
            AssistantState.SLEEPING -> {
                eOpen = 0.05f; sl = 1f; gl = 0.3f; sm = 0.15f
                allowBlink = false
            }
        }

        // piscadas naturais em intervalos variáveis
        var blinkFactor = 1f
        if (allowBlink) {
            if (blinkStart < 0 && now > nextBlink) blinkStart = now
            if (blinkStart >= 0) {
                val p = (now - blinkStart) / 170f
                if (p >= 1f) {
                    blinkStart = -1
                    val doubleBlink = Math.random() < 0.15
                    nextBlink = now + if (doubleBlink) 180L else (2200 + Math.random() * 4500).toLong()
                } else {
                    blinkFactor = 1f - sin(p * PI.toFloat()) * 0.95f
                }
            }
        } else {
            blinkStart = -1
        }

        eyeOpen.target = eOpen * blinkFactor
        if (blinkFactor < 1f) eyeOpen.v = eyeOpen.target // piscada é instantânea
        eyeScale.target = eScale; lookX.target = lx; lookY.target = ly
        mouthOpen.target = mOpen; smile.target = sm; mouthTilt.target = tilt; happy.target = hp
        leftEyeH.target = lEye; browL.target = bl; browR.target = br; alert.target = al
        glow.target = gl; rings.target = rg; think.target = th; sleep.target = sl
        layoutCy.target = if (compact) 0.40f else 0.45f
        layoutS.target = if (compact) 0.50f else 0.62f
        layoutW.target = if (compact) 0.85f else 1f
    }

    // ------------------------------------------------------------------ desenho

    private fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
        )
    }

    private fun drawGlow(canvas: Canvas, x: Float, y: Float, rx: Float, ry: Float, color: Int, alpha: Float) {
        if (alpha <= 0.01f) return
        glowPaint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        glowPaint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        rect.set(x - rx, y - ry, x + rx, y + ry)
        canvas.drawBitmap(glowBitmap, null, rect, glowPaint)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val w = width.toFloat()
        val h = height.toFloat()
        val s = min(w * layoutW.v, h * layoutS.v)
        val cx = w / 2f
        val cy = h * layoutCy.v
        val color = mix(cyan, amber, alert.v)

        // halo de fundo bem discreto
        drawGlow(canvas, cx, cy, s * 0.75f, s * 0.75f, color, 0.07f * glow.v)

        // anéis de escuta
        if (rings.v > 0.01f) {
            stroke.color = color
            stroke.strokeWidth = s * 0.006f
            for (k in 0 until 3) {
                val frac = if (reduceMotion) k / 3f else ((time * 0.55f + k / 3f) % 1f)
                val r = s * (0.40f + frac * 0.22f)
                stroke.alpha = (rings.v * (1f - frac) * (0.25f + StateBus.micLevel * 0.6f) * 255).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx, cy, r, stroke)
            }
        }

        // pontos orbitando enquanto pensa
        if (think.v > 0.01f) {
            fill.color = color
            for (k in 0 until 3) {
                val a = time * (if (reduceMotion) 1.2f else 2.4f) - k * 0.45f
                val r = s * 0.42f
                fill.alpha = (think.v * (1f - k * 0.28f) * 255).toInt()
                canvas.drawCircle(cx + cos(a) * r, cy + sin(a) * r * 0.9f, s * (0.014f - k * 0.003f), fill)
            }
        }

        // olhos
        val eyeW = s * 0.15f * eyeScale.v
        val fullH = s * 0.19f * eyeScale.v
        val gap = s * 0.16f
        val offX = lookX.v * s * 0.06f
        val offY = lookY.v * s * 0.06f
        val eyeY = cy - s * 0.05f + offY
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * gap + offX
            val hFactor = if (side == -1) leftEyeH.v else 1f
            val eh = max(fullH * eyeOpen.v * hFactor * (1f - happy.v * 0.9f), s * 0.012f)
            // No sorriso ("^ ^") a barra do olho some e só o arco fica visível.
            val barAlpha = (1f - happy.v).coerceIn(0f, 1f)
            drawGlow(canvas, ex, eyeY, eyeW * 1.25f, max(eh, eyeW * 0.5f) * 1.2f, color, 0.38f * glow.v * max(barAlpha, 0.5f))
            fill.color = color
            fill.alpha = (255 * barAlpha).toInt()
            val radius = min(eyeW, eh) / 2.2f
            rect.set(ex - eyeW / 2f, eyeY - eh / 2f, ex + eyeW / 2f, eyeY + eh / 2f)
            canvas.drawRoundRect(rect, radius, radius, fill)

            if (happy.v > 0.02f) {
                stroke.color = color
                stroke.alpha = (happy.v * 255).toInt()
                stroke.strokeWidth = s * 0.028f
                rect.set(ex - eyeW / 2f, eyeY - eyeW * 0.30f, ex + eyeW / 2f, eyeY + eyeW * 0.55f)
                canvas.drawArc(rect, 200f, 140f, false, stroke)
            }

            val brow = if (side == -1) browL.v else browR.v
            if (abs(brow) > 0.02f) {
                stroke.color = color
                stroke.alpha = (min(1f, abs(brow)) * 230).toInt()
                stroke.strokeWidth = s * 0.016f
                val by = eyeY - fullH / 2f - s * 0.055f
                val tiltPx = brow * s * 0.035f * side
                canvas.drawLine(ex - eyeW * 0.55f, by + tiltPx, ex + eyeW * 0.55f, by - tiltPx, stroke)
            }
        }

        // boca
        val my = cy + s * 0.18f
        val mw = s * 0.17f
        stroke.color = color
        stroke.alpha = 255
        fill.color = color
        fill.alpha = 255
        if (mouthOpen.v > 0.04f) {
            val mh = s * (0.014f + 0.09f * mouthOpen.v)
            val mWidth = mw * (1f - 0.3f * mouthOpen.v)
            drawGlow(canvas, cx, my, mWidth, mh * 1.3f, color, 0.3f)
            rect.set(cx - mWidth / 2f, my - mh / 2f, cx + mWidth / 2f, my + mh / 2f)
            canvas.drawRoundRect(rect, mh / 2f, mh / 2f, fill)
        } else {
            stroke.strokeWidth = s * 0.016f
            val t = mouthTilt.v * s * 0.02f
            path.reset()
            path.moveTo(cx - mw / 2f, my - t)
            path.quadTo(cx, my + smile.v * s * 0.07f, cx + mw / 2f, my + t)
            canvas.drawPath(path, stroke)
        }

        // "z" do modo repouso
        if (sleep.v > 0.02f) {
            textPaint.color = color
            for (k in 0 until 3) {
                val frac = (time * 0.25f + k / 3f) % 1f
                textPaint.textSize = s * (0.05f + frac * 0.05f)
                textPaint.alpha = (sleep.v * sin(frac * PI.toFloat()) * 200).toInt().coerceIn(0, 255)
                canvas.drawText("z", cx + s * (0.22f + frac * 0.12f), cy - s * (0.12f + frac * 0.22f), textPaint)
            }
        }
    }
}
