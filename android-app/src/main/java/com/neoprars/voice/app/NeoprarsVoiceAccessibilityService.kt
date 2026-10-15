package com.neoprars.voice.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.neoprars.voice.engine.ChunkedDictationSession
import com.neoprars.voice.engine.EngineException
import com.neoprars.voice.engine.Pcm16
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Muestra el botón flotante cuando hay un campo de texto editable enfocado Y el teclado está visible.
 * Solo usa la pantalla para: (1) saber si hay un campo enfocado, (2) dónde está el teclado,
 * (3) escribir el texto dictado en ese campo. No lee ni guarda lo que hay en pantalla.
 */
class NeoprarsVoiceAccessibilityService : AccessibilityService(), FloatingButtonController.Callbacks {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val recorder = AudioRecorder()
    private val settings by lazy { Settings(this) }

    private lateinit var button: FloatingButtonController
    private lateinit var inserter: TextInserter
    private var state = FloatingButtonController.State.IDLE

    private var warmUpMs: Long? = null

    /** Dictado por tramos en curso (null si está desactivado o no se está grabando). */
    private var session: ChunkedDictationSession? = null

    private val refreshRunnable = Runnable { refresh() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        EngineProvider.acquire()
        button = FloatingButtonController(this, this, settings)
        inserter = AccessibilityInserter(this)
        // Tras unos segundos de silencio (o al tope de duración) la grabación se corta sola y se dicta.
        recorder.onAutoStop = { handler.post { stopAndDictate() } }
        scheduleRefresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::button.isInitialized) return
        scheduleRefresh()
    }

    override fun onInterrupt() {
        if (::button.isInitialized && state == FloatingButtonController.State.IDLE) button.hide()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recorder.onAutoStop = null
        if (::button.isInitialized) button.destroy()
        recorder.stop()
        discardSession()
        scope.cancel()
        EngineProvider.release()
        super.onDestroy()
    }

    // ---- Mostrar / ocultar ----

    /** Agrupa ráfagas de eventos: evita recalcular decenas de veces por segundo. */
    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 120)
    }

    @Suppress("DEPRECATION") // recycle() es necesario antes de Android 13
    private fun refresh() {
        if (state != FloatingButtonController.State.IDLE) return // no mover/ocultar mientras se dicta
        val focused: AccessibilityNodeInfo? = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val eligible = focused != null && focused.isEditable && !focused.isPassword
        focused?.recycle()

        val keyboardTop = if (eligible) keyboardTop() else null
        if (keyboardTop != null) button.show(keyboardTop) else button.hide()
    }

    /** Borde superior de la ventana del teclado, o null si no hay teclado en pantalla. */
    private fun keyboardTop(): Int? {
        val bounds = Rect()
        for (window in windows) {
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                window.getBoundsInScreen(bounds)
                if (bounds.height() > 0) return bounds.top
            }
        }
        return null
    }

    // ---- Dictado ----

    override fun onToggle() {
        when (state) {
            FloatingButtonController.State.IDLE -> startListening()
            FloatingButtonController.State.LISTENING -> stopAndDictate()
            FloatingButtonController.State.PROCESSING -> Unit
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toast(R.string.no_mic)
            return
        }
        if (!EngineProvider.hasTranscriberKey(settings)) {
            toast(R.string.no_key)
            return
        }
        session = startSession()
        recorder.onPcm = session?.let { s -> { pcm: ByteArray -> s.push(pcm) } }
        if (!recorder.start()) {
            discardSession()
            toast(R.string.mic_unavailable)
            return
        }
        setState(FloatingButtonController.State.LISTENING)
        // Mientras la persona habla, se abren las conexiones para que el dictado no pague el saludo TLS.
        warmUpMs = null
        scope.launch {
            try {
                val startedAt = SystemClock.elapsedRealtime()
                EngineProvider.engine(settings).warmUp()
                warmUpMs = SystemClock.elapsedRealtime() - startedAt
            } catch (e: EngineException) {
                // falta configuración: se avisa al dictar
            }
        }
    }

    /** Con el dictado por tramos activado, cada pausa se transcribe en segundo plano mientras se sigue grabando. */
    private fun startSession(): ChunkedDictationSession? {
        if (!settings.chunkedDictation) return null
        return try {
            EngineProvider.engine(settings).startSession(CoroutineScope(scope.coroutineContext + Dispatchers.Default))
        } catch (e: EngineException) {
            null // falta configuración: se avisa al dictar
        }
    }

    private fun discardSession() {
        recorder.onPcm = null
        session?.cancel()
        session = null
    }

    private fun stopAndDictate() {
        if (state != FloatingButtonController.State.LISTENING) return
        val startedAt = SystemClock.elapsedRealtime()
        val pcm = recorder.stop()
        val active = session
        val chunked = active != null
        recorder.onPcm = null
        session = null
        setState(FloatingButtonController.State.PROCESSING)

        // El sistema puede silenciar el micrófono a servicios en segundo plano entregando ceros exactos.
        if (pcm.size > 6_400 && Pcm16.rms(pcm) == 0.0) {
            active?.cancel()
            toast(R.string.mic_blocked)
            finishDictation()
            return
        }

        scope.launch {
            try {
                val result = active?.finish() ?: EngineProvider.engine(settings).dictate(pcm)
                val insertAt = SystemClock.elapsedRealtime()
                if (result.text.isNotEmpty() && !inserter.insert(result.text)) toast(R.string.insert_failed)
                // Algún tramo no se pudo transcribir: se insertó el resto y se avisa con el mensaje de ese error.
                result.chunkError?.let { toast(it.kind.messageRes()) }
                val now = SystemClock.elapsedRealtime()
                DictationLog.result(result, chunked, now - insertAt, now - startedAt, warmUpMs)
            } catch (e: EngineException) {
                DictationLog.failure(e.kind.name, chunked, SystemClock.elapsedRealtime() - startedAt)
                toast(e.kind.messageRes())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DictationLog.failure("UNKNOWN", chunked, SystemClock.elapsedRealtime() - startedAt)
                toast(R.string.generic_error)
            } finally {
                finishDictation()
            }
        }
    }

    private fun finishDictation() {
        setState(FloatingButtonController.State.IDLE)
        scheduleRefresh()
    }

    private fun setState(newState: FloatingButtonController.State) {
        state = newState
        button.setState(newState)
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
}
