package com.neoprars.voice.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import kotlin.math.hypot

/**
 * Botón de grabar, monocromático (grafito / blanco), de un solo toque: tocar = empezar, tocar = parar.
 * Si el dedo se mueve más que el "touch slop" del sistema, deja de ser un toque y pasa a ser un arrastre.
 *
 *  - IDLE:       disco grafito con micrófono blanco.
 *  - RECORDING:  disco blanco con cuadrado de "parar" y un campo (anillos que laten) alrededor.
 *  - PROCESSING: disco grafito con un arco que gira.
 *
 * El área de la vista es más grande que el disco para dejar lugar al campo; solo el disco responde al toque.
 * Todo se dibuja con Canvas: sin imágenes ni emojis, así se ve igual en todos los teléfonos.
 */
class RecordButtonView(context: Context) : View(context) {

    enum class Mode { IDLE, RECORDING, PROCESSING }

    private val d = resources.displayMetrics.density
    private val discRadius = 26 * d
    private val haloReach = 18 * d
    /** Tamaño total que debe tener la ventana/vista (disco + campo). */
    val totalSize: Int = ((discRadius + haloReach) * 2).toInt()
    /** Margen entre el borde de la vista y el disco; sirve para compensar la posición en pantalla. */
    val haloMargin: Int = haloReach.toInt()

    var onToggle: (() -> Unit)? = null

    /** Arrastre del disco. [onDrag] recibe el desplazamiento total, en píxeles de pantalla, desde donde se apoyó el dedo. */
    var onDragStart: (() -> Unit)? = null
    var onDrag: ((dx: Float, dy: Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    var mode: Mode = Mode.IDLE
        set(value) {
            if (field == value) return
            field = value
            updateAnimation()
            invalidate()
            contentDescription = context.getString(
                if (value == Mode.RECORDING) R.string.floating_button_stop_description
                else R.string.floating_button_description
            )
        }

    private val graphite = 0xFF17181A.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val haloColor = 0xFF8E939B.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    /** 0..1 que se repite; mueve el campo al grabar y el arco al procesar. */
    private var phase = 0f
    private var animator: ValueAnimator? = null
    private var pressed = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var tracking = false
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f

    init {
        contentDescription = context.getString(R.string.floating_button_description)
        isClickable = true
    }

    private fun updateAnimation() {
        animator?.cancel()
        animator = null
        if (mode == Mode.IDLE || !isAttachedToWindow) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (mode == Mode.RECORDING) 1600 else 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateAnimation() }
    override fun onDetachedFromWindow() { animator?.cancel(); animator = null; super.onDetachedFromWindow() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(totalSize, totalSize)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val scale = if (pressed) 0.94f else 1f
        val r = discRadius * scale

        when (mode) {
            Mode.RECORDING -> {
                // Campo: un halo suave fijo y dos anillos que se expanden y se desvanecen.
                fill.color = haloColor; fill.alpha = 36
                canvas.drawCircle(cx, cy, discRadius + haloReach * 0.35f, fill)
                for (i in 0..1) {
                    val t = (phase + i * 0.5f) % 1f
                    stroke.color = haloColor
                    stroke.strokeWidth = 2 * d
                    stroke.alpha = ((1f - t) * 150).toInt()
                    canvas.drawCircle(cx, cy, discRadius + haloReach * t, stroke)
                }
                // Disco blanco con borde grafito y cuadrado de parar.
                fill.color = white; fill.alpha = 255
                canvas.drawCircle(cx, cy, r, fill)
                stroke.color = graphite; stroke.alpha = 255; stroke.strokeWidth = 2.5f * d
                canvas.drawCircle(cx, cy, r - 1.25f * d, stroke)
                glyph.color = graphite
                val s = 9 * d * scale
                rect.set(cx - s, cy - s, cx + s, cy + s)
                canvas.drawRoundRect(rect, 3 * d, 3 * d, glyph)
            }
            Mode.PROCESSING -> {
                drawGraphiteDisc(canvas, cx, cy, r)
                stroke.color = white; stroke.alpha = 255; stroke.strokeWidth = 3 * d
                val a = r * 0.5f
                rect.set(cx - a, cy - a, cx + a, cy + a)
                canvas.drawArc(rect, phase * 360f, 100f, false, stroke)
                stroke.alpha = 70
                canvas.drawArc(rect, phase * 360f + 180f, 60f, false, stroke)
            }
            Mode.IDLE -> {
                drawGraphiteDisc(canvas, cx, cy, r)
                drawMic(canvas, cx, cy, scale)
            }
        }
    }

    private fun drawGraphiteDisc(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        fill.color = graphite; fill.alpha = 255
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = white; stroke.alpha = 60; stroke.strokeWidth = 1.5f * d
        canvas.drawCircle(cx, cy, r - 0.75f * d, stroke)
    }

    private fun drawMic(canvas: Canvas, cx: Float, cy: Float, scale: Float) {
        val u = d * scale
        glyph.color = white
        // Cápsula
        rect.set(cx - 5 * u, cy - 12 * u, cx + 5 * u, cy + 3 * u)
        canvas.drawRoundRect(rect, 5 * u, 5 * u, glyph)
        // Arco, pie y base
        stroke.color = white; stroke.alpha = 255; stroke.strokeWidth = 2 * u
        path.reset()
        rect.set(cx - 9 * u, cy - 8 * u, cx + 9 * u, cy + 8 * u)
        path.addArc(rect, 0f, 180f)
        canvas.drawPath(path, stroke)
        canvas.drawLine(cx, cy + 8 * u, cx, cy + 12 * u, stroke)
        canvas.drawLine(cx - 4 * u, cy + 12 * u, cx + 4 * u, cy + 12 * u, stroke)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val inside = hypot(event.x - width / 2f, event.y - height / 2f) <= discRadius
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!inside) return false
                tracking = true; dragging = false
                downRawX = event.rawX; downRawY = event.rawY
                pressed = true; invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlop) {
                    dragging = true
                    pressed = false; invalidate()
                    onDragStart?.invoke()
                }
                if (dragging) onDrag?.invoke(dx, dy)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasPressed = pressed
                val wasDragging = dragging
                tracking = false; dragging = false
                pressed = false; invalidate()
                if (wasDragging) {
                    onDragEnd?.invoke()
                } else if (wasPressed && inside && mode != Mode.PROCESSING) {
                    performClick()
                    onToggle?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragging
                tracking = false; dragging = false
                pressed = false; invalidate()
                if (wasDragging) onDragEnd?.invoke()
                return true
            }
        }
        return tracking
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
