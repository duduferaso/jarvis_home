package br.com.jarvishome.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import br.com.jarvishome.home.FloorPlan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * A planta da casa em 3D (isométrica, estilo The Sims): piso de cada cômodo acende em amarelo
 * quando a luz está ligada, paredes baixas só onde existem. Toque liga/desliga; toque longo
 * escolhe a luz do cômodo.
 */
class FloorPlanView(context: Context) : View(context) {

    /** Estado de cada cômodo: null = sem luz; true/false = acesa/apagada. */
    var states: Map<String, Boolean?> = emptyMap()
        set(v) { field = v; animateTo(); invalidate() }
    var dim = false
        set(v) { field = v; invalidate() }
    var onZoneTap: ((FloorPlan.Zone) -> Unit)? = null
    var onZoneLongPress: ((FloorPlan.Zone) -> Unit)? = null

    private val cosA = cos(Math.PI / 6).toFloat()
    private val wallH = 0.7f
    private var scale = 20f
    private var ox = 0f
    private var oy = 0f

    // brilho animado de cada cômodo (0 apagado … 1 aceso)
    private val glow = HashMap<String, Float>()
    private var lastFrame = 0L
    private var flashZone: String? = null
    private var flashUntil = 0L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val path = Path()
    private val label = android.graphics.RectF()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun px(x: Float, y: Float, z: Float = 0f) = ox + (x - y) * cosA * scale
    private fun py(x: Float, y: Float, z: Float = 0f) = oy + (x + y) * 0.5f * scale - z * scale

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        // projeção com escala 1, para caber na view com margem
        val xs = floatArrayOf(0f, FloorPlan.WIDTH, FloorPlan.WIDTH, 0f)
        val ys = floatArrayOf(0f, 0f, FloorPlan.DEPTH, FloorPlan.DEPTH)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0..3) {
            val sx = (xs[i] - ys[i]) * cosA
            val sy = (xs[i] + ys[i]) * 0.5f
            minX = min(minX, sx); maxX = max(maxX, sx); minY = min(minY, sy - wallH); maxY = max(maxY, sy)
        }
        val pad = dp(14f)
        scale = min((w - 2 * pad) / (maxX - minX), (h - 2 * pad) / (maxY - minY))
        ox = (w - (maxX - minX) * scale) / 2 - minX * scale
        oy = (h - (maxY - minY) * scale) / 2 - minY * scale
        text.textSize = min(dp(12f), max(dp(9.5f), scale * 0.36f))
    }

    private fun animateTo() {
        if (lastFrame == 0L) for (z in FloorPlan.zones) glow[z.id] = if (states[z.id] == true) 1f else 0f
        postInvalidateOnAnimation()
    }

    private fun quad(x1: Float, y1: Float, x2: Float, y2: Float, z: Float) {
        path.reset()
        path.moveTo(px(x1, y1), py(x1, y1))
        path.lineTo(px(x2, y2), py(x2, y2))
        path.lineTo(px(x2, y2), py(x2, y2, z))
        path.lineTo(px(x1, y1), py(x1, y1, z))
        path.close()
    }

    private fun zonePath(z: FloorPlan.Zone): Path {
        val p = Path()
        for (r in z.rects) {
            p.moveTo(px(r[0], r[1]), py(r[0], r[1]))
            p.lineTo(px(r[2], r[1]), py(r[2], r[1]))
            p.lineTo(px(r[2], r[3]), py(r[2], r[3]))
            p.lineTo(px(r[0], r[3]), py(r[0], r[3]))
            p.close()
        }
        return p
    }

    override fun onDraw(c: Canvas) {
        val now = System.currentTimeMillis()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 350f).coerceAtMost(1f)
        lastFrame = now
        var animating = false
        val a = if (dim) 0.45f else 1f

        // pisos
        for (z in FloorPlan.zones) {
            val target = if (states[z.id] == true) 1f else 0f
            var g = glow[z.id] ?: target
            if (g != target) {
                g = if (g < target) min(target, g + dt) else max(target, g - dt)
                animating = true
            }
            glow[z.id] = g
            val zp = zonePath(z)
            c.save()
            c.clipPath(zp)
            // apagado: azul-escuro; sem luz no Home Assistant: mais escuro ainda
            val base = if (states[z.id] == null) Color.rgb(9, 15, 18) else Color.rgb(14, 25, 29)
            fill.shader = null
            fill.color = base; fill.alpha = (255 * a).toInt()
            c.drawPaint(fill)
            if (g > 0f) {
                fill.color = Color.rgb(150, 95, 30); fill.alpha = (215 * g * a).toInt()
                c.drawPaint(fill)
                for (l in z.lightPoints) {
                    val cx = px(l[0], l[1]); val cy = py(l[0], l[1])
                    fill.shader = RadialGradient(cx, cy, scale * 3.6f,
                        intArrayOf(Color.argb((245 * g * a).toInt(), 255, 226, 150), Color.argb(0, 255, 180, 70)),
                        null, Shader.TileMode.CLAMP)
                    c.drawPaint(fill)
                }
                fill.shader = null
            }
            if (flashZone == z.id && now < flashUntil) {
                fill.color = Color.argb(70, 34, 229, 255); c.drawPaint(fill); animating = true
            }
            c.restore()
        }

        // paredes: das de trás para as da frente
        stroke.strokeWidth = dp(1.4f)
        for (w in FloorPlan.walls.sortedBy { it[0] + it[1] + it[2] + it[3] }) {
            quad(w[0], w[1], w[2], w[3], wallH)
            fill.color = if (w[0] == w[2]) Color.rgb(18, 44, 52) else Color.rgb(28, 66, 76)
            fill.alpha = (238 * a).toInt()
            c.drawPath(path, fill)
            stroke.color = Color.rgb(34, 229, 255); stroke.alpha = (255 * a).toInt()
            c.drawLine(px(w[0], w[1]), py(w[0], w[1], wallH), px(w[2], w[3]), py(w[2], w[3], wallH), stroke)
        }

        // nomes por cima de tudo, numa etiqueta (legível sobre piso aceso ou parede)
        for (z in FloorPlan.zones) {
            if (!z.showLabel) continue
            val g = glow[z.id] ?: 0f
            val has = states[z.id] != null
            val lit = g > 0.5f
            // nome centralizado no cômodo
            val cx = px(z.label[0], z.label[1])
            val cy = py(z.label[0], z.label[1]) + text.textSize * 0.35f
            val w = text.measureText(z.name) / 2 + dp(6f)
            val top = cy - text.textSize * 0.95f
            val bottom = cy + text.textSize * 0.38f
            fill.shader = null
            fill.color = if (lit) Color.rgb(70, 44, 8) else Color.rgb(6, 14, 17)
            fill.alpha = ((if (lit) 225 else 200) * a).toInt()
            label.set(cx - w, top, cx + w, bottom)
            c.drawRoundRect(label, dp(6f), dp(6f), fill)
            text.color = if (lit) Color.rgb(255, 233, 176) else Color.rgb(214, 238, 243)
            text.alpha = ((if (has || lit) 245 else 130) * a).toInt()
            c.drawText(z.name, cx, cy, text)
        }
        if (animating) postInvalidateOnAnimation()
    }

    /** Ponto da tela → ponto da planta (no chão). */
    private fun zoneAt(sx: Float, sy: Float): FloorPlan.Zone? {
        val u = (sx - ox) / (cosA * scale)   // x - y
        val v = (sy - oy) / (0.5f * scale)   // x + y
        return FloorPlan.zoneAt((u + v) / 2, (v - u) / 2)
    }

    fun flash(z: FloorPlan.Zone) {
        flashZone = z.id; flashUntil = System.currentTimeMillis() + 250
        postInvalidateOnAnimation()
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = zoneAt(e.x, e.y) != null
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val z = zoneAt(e.x, e.y) ?: return false
            flash(z)
            onZoneTap?.invoke(z)
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            zoneAt(e.x, e.y)?.let { onZoneLongPress?.invoke(it) }
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)
}
