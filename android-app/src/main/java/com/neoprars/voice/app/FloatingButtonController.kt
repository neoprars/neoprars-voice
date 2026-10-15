package com.neoprars.voice.app

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowManager

/**
 * Botón circular superpuesto. Un toque = empezar a hablar; otro toque = enviar. Se puede arrastrar:
 * al soltarlo se pega al borde horizontal más cercano y la posición se recuerda por orientación.
 * Si nunca se movió, va a la derecha, justo arriba del teclado. Nunca queda detrás del teclado.
 * No toma el foco (FLAG_NOT_FOCUSABLE): el campo de texto y el teclado siguen como estaban.
 * Se dibuja con TYPE_ACCESSIBILITY_OVERLAY, que solo puede usar un servicio de accesibilidad.
 */
class FloatingButtonController(
    private val context: Context,
    private val callbacks: Callbacks,
    private val settings: Settings,
) {

    interface Callbacks {
        fun onToggle()
    }

    enum class State(val mode: RecordButtonView.Mode) {
        IDLE(RecordButtonView.Mode.IDLE),
        LISTENING(RecordButtonView.Mode.RECORDING),
        PROCESSING(RecordButtonView.Mode.PROCESSING),
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private val margin = (12 * density).toInt()

    private var view: RecordButtonView? = null
    private var params: WindowManager.LayoutParams? = null
    private var shown = false
    private var state = State.IDLE

    private var keyboardTop: Int? = null
    private var dragging = false
    private var dragStartX = 0
    private var dragStartY = 0

    private val screenWidth get() = context.resources.displayMetrics.widthPixels
    private val screenHeight get() = context.resources.displayMetrics.heightPixels
    private val landscape get() = screenWidth > screenHeight

    /** Muestra (o reubica) el botón. [keyboardTop] es el borde superior del teclado (coordenada Y de pantalla). */
    fun show(keyboardTop: Int) {
        this.keyboardTop = keyboardTop
        if (dragging) return // mientras se arrastra manda el dedo
        val button = view ?: createView().also { view = it }
        val disc = discSize(button)
        val chosen = settings.buttonPosition(landscape)
            ?: ButtonPlacement.defaultPosition(screenHeight, disc, margin, keyboardTop)
        place(
            button,
            ButtonPlacement.discX(chosen.side, screenWidth, disc, margin),
            ButtonPlacement.clampY(chosen.y, screenHeight, disc, margin, keyboardTop),
        )
    }

    fun hide() {
        val button = view ?: return
        if (!shown) return
        try {
            windowManager.removeView(button)
        } catch (e: IllegalArgumentException) {
            // ya no estaba en pantalla
        }
        shown = false
    }

    fun setState(newState: State) {
        state = newState
        view?.mode = newState.mode
    }

    fun destroy() {
        hide()
        view = null
        params = null
    }

    private fun discSize(button: RecordButtonView) = button.totalSize - 2 * button.haloMargin

    /** Ubica la ventana para que el disco quede en ([discX], [discY]); la ventana incluye el halo. */
    private fun place(button: RecordButtonView, discX: Int, discY: Int) {
        val layout = params ?: createParams(button).also { params = it }
        val x = discX - button.haloMargin
        val y = discY - button.haloMargin
        if (shown && layout.x == x && layout.y == y) return
        layout.x = x
        layout.y = y
        try {
            if (shown) windowManager.updateViewLayout(button, layout)
            else windowManager.addView(button, layout)
            shown = true
        } catch (e: RuntimeException) {
            shown = false
        }
    }

    private fun createView(): RecordButtonView = RecordButtonView(context).apply {
        mode = state.mode
        onToggle = {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            callbacks.onToggle()
        }
        onDragStart = {
            val layout = params
            if (shown && layout != null) {
                dragging = true
                dragStartX = layout.x + haloMargin
                dragStartY = layout.y + haloMargin
            }
        }
        onDrag = { dx, dy ->
            if (dragging && shown) {
                val disc = discSize(this)
                place(
                    this,
                    ButtonPlacement.clampX(dragStartX + dx.toInt(), screenWidth, disc),
                    ButtonPlacement.clampY(dragStartY + dy.toInt(), screenHeight, disc, margin, keyboardTop = null),
                )
            }
        }
        onDragEnd = {
            val layout = params
            if (dragging && layout != null) {
                dragging = false
                val disc = discSize(this)
                val side = ButtonPlacement.nearestSide(layout.x + haloMargin, screenWidth, disc)
                val y = ButtonPlacement.clampY(layout.y + haloMargin, screenHeight, disc, margin, keyboardTop)
                settings.saveButtonPosition(landscape, ButtonPlacement.Position(side, y))
                if (shown) place(this, ButtonPlacement.discX(side, screenWidth, disc, margin), y)
            }
            dragging = false
        }
    }

    private fun createParams(button: RecordButtonView) = WindowManager.LayoutParams(
        button.totalSize,
        button.totalSize,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }
}
