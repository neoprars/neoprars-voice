package com.neoprars.voice.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.neoprars.voice.engine.ChunkedDictationSession
import com.neoprars.voice.engine.EngineException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Teclado de dictado (variante IME): mantené el botón, hablá, soltá y el texto se inserta.
 * El botón flotante estilo Wispr Flow será otra "puerta de entrada" sobre el mismo motor
 * (ver docs/ARCHITECTURE.md).
 */
class NeoprarsVoiceIme : InputMethodService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recorder = AudioRecorder()
    private val settings by lazy { Settings(this) }

    /** Dictado por tramos en curso (null si está desactivado o no se está grabando). */
    private var session: ChunkedDictationSession? = null

    private lateinit var status: TextView
    private lateinit var talk: Button

    override fun onCreate() {
        super.onCreate()
        EngineProvider.acquire()
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        status = view.findViewById(R.id.status)
        talk = view.findViewById(R.id.talk)
        bindTalkButton()

        view.findViewById<View>(R.id.space).setOnClickListener { currentInputConnection?.commitText(" ", 1) }
        view.findViewById<View>(R.id.backspace).setOnClickListener { sendKey(KeyEvent.KEYCODE_DEL) }
        view.findViewById<View>(R.id.enter).setOnClickListener { sendKey(KeyEvent.KEYCODE_ENTER) }
        view.findViewById<View>(R.id.switch_kb).setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        return view
    }

    private fun sendKey(code: Int) {
        currentInputConnection?.let {
            it.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            it.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindTalkButton() {
        talk.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startListening(); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.performClick(); stopAndDictate(); true }
                else -> false
            }
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            status.setText(R.string.no_mic)
            return
        }
        if (!EngineProvider.hasTranscriberKey(settings)) {
            status.setText(R.string.no_key)
            return
        }
        session = if (settings.chunkedDictation) {
            try {
                EngineProvider.engine(settings).startSession(CoroutineScope(scope.coroutineContext + Dispatchers.Default))
            } catch (e: EngineException) {
                null // falta configuración: se avisa al dictar
            }
        } else null
        recorder.onPcm = session?.let { s -> { pcm: ByteArray -> s.push(pcm) } }
        if (!recorder.start()) {
            discardSession()
            return
        }
        status.setText(R.string.listening)
        // Mientras la persona habla, se abren las conexiones para que el dictado no pague el saludo TLS.
        scope.launch {
            try {
                EngineProvider.engine(settings).warmUp()
            } catch (e: EngineException) {
                // falta configuración: se avisa al dictar
            }
        }
    }

    private fun discardSession() {
        recorder.onPcm = null
        session?.cancel()
        session = null
    }

    private fun stopAndDictate() {
        val pcm = recorder.stop()
        val active = session
        recorder.onPcm = null
        session = null
        if (pcm.isEmpty()) {
            active?.cancel()
            status.setText(R.string.hold_to_talk)
            return
        }
        status.setText(R.string.transcribing)
        scope.launch {
            try {
                val result = active?.finish() ?: EngineProvider.engine(settings).dictate(pcm)
                if (result.text.isNotEmpty()) currentInputConnection?.commitText(result.text, 1)
                // Algún tramo no se pudo transcribir: se insertó el resto y se avisa con el mensaje de ese error.
                val chunkError = result.chunkError
                if (chunkError != null) status.setText(chunkError.kind.messageRes())
                else status.setText(R.string.hold_to_talk)
            } catch (e: EngineException) {
                status.setText(e.kind.messageRes())
            }
        }
    }

    override fun onDestroy() {
        discardSession()
        scope.cancel()
        EngineProvider.release()
        super.onDestroy()
    }
}
