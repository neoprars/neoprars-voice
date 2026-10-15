package com.neoprars.voice.app

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/** "Dónde se escribe el texto". Hoy: el campo enfocado vía Accesibilidad. Mañana: también el teclado propio (IME). */
interface TextInserter {
    /** @return true si el texto quedó insertado. */
    fun insert(text: String): Boolean
}

/**
 * Inserta en el campo de texto enfocado de cualquier app, en la posición del cursor
 * (o reemplazando la selección). Nunca inserta en campos de contraseña.
 */
class AccessibilityInserter(private val service: AccessibilityService) : TextInserter {

    @Suppress("DEPRECATION") // recycle() es necesario antes de Android 13
    override fun insert(text: String): Boolean {
        val node = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        try {
            if (!node.isEditable || node.isPassword) return false

            val start = node.textSelectionStart
            val end = node.textSelectionEnd
            val rawText = node.text?.toString()
            val hint = node.hintText?.toString()
            // Diagnóstico del campo, sin contenido: solo banderas, largo y posiciones.
            Log.i(
                DictationLog.TAG,
                "field showing_hint=${node.isShowingHintText} has_hint=${hint != null} " +
                    "text_equals_hint=${rawText != null && rawText == hint} text_len=${rawText?.length ?: -1} " +
                    "sel=$start..$end",
            )

            val plan = TextSplice.plan(rawText, hint, node.isShowingHintText, start, end, text)
            if (plan is TextSplice.Plan.SetText) {
                val setTextArgs = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, plan.text)
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)) {
                    val selection = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, plan.caret)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, plan.caret)
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
                    Log.i(DictationLog.TAG, "insert method=set_text")
                    return true
                }
            }
            // Sin selección informada, o la app rechazó ACTION_SET_TEXT: pegar en el cursor.
            val pasted = paste(node, text)
            Log.i(DictationLog.TAG, "insert method=paste ok=$pasted")
            return pasted
        } finally {
            node.recycle()
        }
    }

    /** Pega en el cursor por portapapeles y restaura el contenido anterior. */
    private fun paste(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val previous = clipboard.primaryClip
        val clip = ClipData.newPlainText("dictado", text).apply {
            description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true) // oculta la vista previa en Android 13+
            }
        }
        clipboard.setPrimaryClip(clip)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Handler(Looper.getMainLooper()).postDelayed({
            // El dictado no queda en el portapapeles; ver ClipboardRestore para la rama por versión.
            when (ClipboardRestore.action(previous != null, Build.VERSION.SDK_INT)) {
                ClipboardRestore.Action.RESTORE_PREVIOUS -> previous?.let { clipboard.setPrimaryClip(it) }
                ClipboardRestore.Action.CLEAR ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip()
                ClipboardRestore.Action.REPLACE_WITH_EMPTY -> clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }, 600)
        return ok
    }
}
