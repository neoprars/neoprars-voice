package com.neoprars.voice.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.progressindicator.IndeterminateDrawable
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.neoprars.voice.engine.Capability
import com.neoprars.voice.engine.CatalogEntry
import com.neoprars.voice.engine.EngineException
import com.neoprars.voice.engine.PolishStyle
import com.neoprars.voice.engine.ProviderCatalog
import com.neoprars.voice.engine.ProviderStatus
import kotlinx.coroutines.launch

/**
 * Configuración: servicios, claves, permisos y activación del botón flotante (y, opcional, del teclado).
 * Interruptores y selectores se guardan al instante; los campos que se escriben, con "Guardar cambios"
 * (ver [SettingsForm]). El motor se rearma solo en [EngineProvider] al cambiar su configuración.
 */
class MainActivity : AppCompatActivity() {

    private val settings by lazy { Settings(this) }

    private lateinit var root: View
    private lateinit var statusList: LinearLayout
    private lateinit var chunked: SwitchMaterial
    private lateinit var polish: SwitchMaterial
    private lateinit var polishOptions: View
    private lateinit var language: MaterialAutoCompleteTextView
    private lateinit var transcriber: MaterialAutoCompleteTextView
    private lateinit var polisher: MaterialAutoCompleteTextView
    private lateinit var style: MaterialAutoCompleteTextView
    private lateinit var keyFields: LinearLayout
    private lateinit var transcriberModelLayout: TextInputLayout
    private lateinit var transcriberModel: TextInputEditText
    private lateinit var polisherModelLayout: TextInputLayout
    private lateinit var polisherModel: TextInputEditText
    private lateinit var hint: TextInputEditText
    private lateinit var save: MaterialButton

    /** Campos de clave visibles, por empresa (`CatalogEntry.keyId`). */
    private val keyInputs = linkedMapOf<String, TextInputEditText>()

    /** Proveedores cuyos modelos muestran los campos (para no pisar lo escrito si no cambiaron). */
    private var shownTranscriber: String? = null
    private var shownPolisher: String? = null

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    /** Al volver atrás con cambios sin guardar, se pregunta antes de salir. */
    private val unsavedGuard = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showUnsavedDialog()
    }

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = updateSaveButton()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        statusList = findViewById(R.id.status_list)
        chunked = findViewById(R.id.chunked_switch)
        polish = findViewById(R.id.polish_switch)
        polishOptions = findViewById(R.id.polish_options)
        language = findViewById(R.id.language)
        transcriber = findViewById(R.id.transcriber)
        polisher = findViewById(R.id.polisher)
        style = findViewById(R.id.style)
        keyFields = findViewById(R.id.key_fields)
        transcriberModelLayout = findViewById(R.id.transcriber_model_layout)
        transcriberModel = findViewById(R.id.transcriber_model)
        polisherModelLayout = findViewById(R.id.polisher_model_layout)
        polisherModel = findViewById(R.id.polisher_model)
        hint = findViewById(R.id.hint)
        save = findViewById(R.id.btn_save)
        onBackPressedDispatcher.addCallback(this, unsavedGuard)

        bindInstantSettings()

        // ---- Con "Guardar cambios" ----
        hint.setText(settings.vocabularyHint)
        hint.addTextChangedListener(watcher)
        transcriberModel.addTextChangedListener(watcher)
        polisherModel.addTextChangedListener(watcher)
        save.setOnClickListener { saveTypedFields() }
        rebuildTypedFields()

        findViewById<ImageButton>(R.id.transcriber_help).setOnClickListener {
            showKeyHelp(ProviderCatalog.find(Capability.TRANSCRIBE, currentTranscriber()))
        }
        findViewById<ImageButton>(R.id.polisher_help).setOnClickListener {
            showKeyHelp(ProviderCatalog.find(Capability.POLISH, currentPolisher()))
        }

        findViewById<MaterialButton>(R.id.btn_about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btn_mic).setOnClickListener {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        findViewById<MaterialButton>(R.id.btn_a11y).setOnClickListener { showAccessibilityDisclosure() }
        findViewById<MaterialButton>(R.id.btn_enable).setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.btn_pick).setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---- Al instante ----

    private fun bindInstantSettings() {
        chunked.isChecked = settings.chunkedDictation
        chunked.setOnCheckedChangeListener { _, on ->
            settings.chunkedDictation = on
            notice(if (on) R.string.saved_chunked_on else R.string.saved_chunked_off)
        }

        polish.isChecked = settings.polishEnabled
        polishOptions.visibility = if (settings.polishEnabled) View.VISIBLE else View.GONE
        polish.setOnCheckedChangeListener { _, on ->
            settings.polishEnabled = on
            polishOptions.visibility = if (on) View.VISIBLE else View.GONE
            notice(if (on) R.string.saved_polish_on else R.string.saved_polish_off)
            afterProviderChange()
        }

        val languages = SettingsForm.languageOptions(settings.language)
        bindDropdown(language, languages.map { languageLabel(it) }, languages.indexOf(settings.language.trim())) { i ->
            if (languages[i] == settings.language.trim()) return@bindDropdown
            settings.language = languages[i]
            notice(getString(R.string.saved_language, languageLabel(languages[i])))
        }

        bindProviderDropdown(transcriber, Capability.TRANSCRIBE, currentTranscriber()) { entry ->
            if (entry.id == settings.transcriberProvider) return@bindProviderDropdown
            settings.transcriberProvider = entry.id
            notice(getString(R.string.saved_transcriber, entry.displayName))
            afterProviderChange()
        }

        bindProviderDropdown(polisher, Capability.POLISH, currentPolisher()) { entry ->
            if (entry.id == settings.polisherProvider) return@bindProviderDropdown
            settings.polisherProvider = entry.id
            notice(getString(R.string.saved_polisher, entry.displayName))
            afterProviderChange()
        }

        val styles = PolishStyle.entries
        bindDropdown(style, styles.map { styleLabel(it) }, styles.indexOf(settings.polishStyle)) { i ->
            if (styles[i] == settings.polishStyle) return@bindDropdown
            settings.polishStyle = styles[i]
            notice(getString(R.string.saved_style, styleLabel(styles[i])))
        }
    }

    /** Selector de opciones fijas; [selected] fuera de rango = la primera. */
    private fun bindDropdown(view: MaterialAutoCompleteTextView, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        view.setSimpleItems(labels.toTypedArray())
        view.setText(labels[selected.coerceIn(labels.indices)], false)
        view.setOnItemClickListener { _, _, position, _ -> onPick(position) }
    }

    /**
     * Selector de proveedores: todos los del catálogo, con su estado como etiqueta (probado = sin etiqueta).
     * Los "Próximamente" se ven pero no se pueden elegir.
     */
    private fun bindProviderDropdown(
        view: MaterialAutoCompleteTextView,
        capability: Capability,
        selectedId: String,
        onPick: (CatalogEntry) -> Unit,
    ) {
        val options = SettingsForm.providerOptions(capability)
        val labels = options.map { providerLabel(it) }
        view.setAdapter(ProviderAdapter(this, labels, options.map { it.implemented }))
        view.setText(labels[options.indexOfFirst { it.id == selectedId }.coerceIn(labels.indices)], false)
        view.setOnItemClickListener { _, _, position, _ -> onPick(options[position]) }
    }

    private fun providerLabel(entry: CatalogEntry): String = when (entry.status) {
        ProviderStatus.TESTED -> entry.displayName
        ProviderStatus.EXPERIMENTAL -> getString(R.string.provider_with_status, entry.displayName, getString(R.string.provider_experimental))
        ProviderStatus.PLANNED -> getString(R.string.provider_with_status, entry.displayName, getString(R.string.provider_planned))
    }

    /** Cambió qué servicios se usan: cambian las claves y modelos que se piden. */
    private fun afterProviderChange() {
        rebuildTypedFields()
        refresh()
    }

    private fun languageLabel(code: String): String = when (code) {
        "es" -> getString(R.string.language_es)
        "en" -> getString(R.string.language_en)
        "" -> getString(R.string.language_auto)
        else -> code
    }

    private fun styleLabel(style: PolishStyle): String = getString(
        when (style) {
            PolishStyle.NEUTRAL -> R.string.style_neutral
            PolishStyle.FORMAL -> R.string.style_formal
            PolishStyle.INFORMAL -> R.string.style_informal
            PolishStyle.CONCISE -> R.string.style_concise
        },
    )

    // ---- Con "Guardar cambios" ----

    private fun vendorName(keyId: String): String =
        ProviderCatalog.entries.firstOrNull { it.keyId == keyId }?.displayName ?: keyId

    private fun currentTranscriber() = settings.transcriberProvider
        .takeIf { id -> ProviderCatalog.available(Capability.TRANSCRIBE).any { it.id == id } }
        ?: ProviderCatalog.DEFAULT_TRANSCRIBER

    private fun currentPolisher() = settings.polisherProvider
        .takeIf { id -> ProviderCatalog.available(Capability.POLISH).any { it.id == id } }
        ?: ProviderCatalog.DEFAULT_POLISHER

    /** Una clave por empresa elegida y los modelos de los servicios elegidos. Conserva lo ya escrito. */
    private fun rebuildTypedFields() {
        val typed = keyInputs.mapValues { it.value.text?.toString().orEmpty() }
        keyFields.removeAllViews()
        keyInputs.clear()
        for (keyId in EngineProvider.requiredKeys(settings)) {
            val item = layoutInflater.inflate(R.layout.item_key_field, keyFields, false)
            val layout = item.findViewById<TextInputLayout>(R.id.key_layout)
            val input = layout.findViewById<TextInputEditText>(R.id.key_input)
            val check = item.findViewById<MaterialButton>(R.id.key_check)
            layout.hint = getString(R.string.key_hint, vendorName(keyId))
            layout.helperText = keyHelper(keyId)
            input.setText(typed[keyId].orEmpty())
            input.addTextChangedListener(watcher)
            // Al escribir otra clave, el resultado de la prueba anterior ya no vale.
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    layout.error = null
                    layout.helperText = keyHelper(keyId)
                }
            })
            check.setOnClickListener { checkKey(keyId, layout, input, check) }
            keyFields.addView(item)
            keyInputs[keyId] = input
        }

        val t = currentTranscriber()
        if (t != shownTranscriber) {
            transcriberModel.setText(settings.model(Capability.TRANSCRIBE, t))
            shownTranscriber = t
        }
        transcriberModelLayout.helperText =
            getString(R.string.model_helper, ProviderCatalog.find(Capability.TRANSCRIBE, t)?.defaultModel.orEmpty())

        val p = currentPolisher()
        if (p != shownPolisher) {
            polisherModel.setText(settings.model(Capability.POLISH, p))
            shownPolisher = p
        }
        polisherModelLayout.helperText =
            getString(R.string.model_helper, ProviderCatalog.find(Capability.POLISH, p)?.defaultModel.orEmpty())
        polisherModelLayout.visibility = if (settings.polishEnabled) View.VISIBLE else View.GONE

        updateSaveButton()
    }

    private fun keyHelper(keyId: String): String =
        if (EngineProvider.hasKey(settings, keyId)) getString(R.string.key_saved_helper)
        else getString(R.string.key_missing_helper, vendorName(keyId))

    /**
     * "Probar clave": con lo escrito en el campo o, si está vacío, con la guardada. La pantalla solo muestra
     * el resultado; la prueba vive en el motor ([com.neoprars.voice.engine.KeyCheck]) y dura como mucho unos 10 s.
     */
    private fun checkKey(keyId: String, layout: TextInputLayout, input: TextInputEditText, button: MaterialButton) {
        val typed = input.text?.toString().orEmpty()
        if (typed.isBlank() && !EngineProvider.hasKey(settings, keyId)) {
            layout.error = getString(R.string.key_check_empty)
            return
        }
        layout.error = null
        button.isEnabled = false
        button.setText(R.string.key_check_running)
        button.icon = progressIcon()
        lifecycleScope.launch {
            val kind = EngineProvider.checkKey(settings, keyId, typed)
            button.isEnabled = true
            button.setText(R.string.key_check)
            button.icon = null
            if (kind == null) {
                layout.helperText = getString(if (typed.isBlank()) R.string.key_check_valid else R.string.key_check_valid_unsaved)
            } else {
                layout.error = getString(
                    when (kind) {
                        EngineException.Kind.AUTH -> R.string.key_check_auth
                        EngineException.Kind.NETWORK -> R.string.key_check_network
                        EngineException.Kind.RATE_LIMIT -> R.string.key_check_limit
                        else -> R.string.key_check_unknown
                    },
                )
            }
        }
    }

    private fun progressIcon(): IndeterminateDrawable<CircularProgressIndicatorSpec> {
        val spec = CircularProgressIndicatorSpec(
            this, null, 0, com.google.android.material.R.style.Widget_MaterialComponents_CircularProgressIndicator_ExtraSmall,
        )
        spec.indicatorColors = intArrayOf(ContextCompat.getColor(this, R.color.app_secondary_text))
        return IndeterminateDrawable.createCircularDrawable(this, spec)
    }

    private fun hasPendingChanges(): Boolean {
        val edits = buildList {
            add(transcriberModel.text.toString() to settings.model(Capability.TRANSCRIBE, currentTranscriber()))
            if (settings.polishEnabled) add(polisherModel.text.toString() to settings.model(Capability.POLISH, currentPolisher()))
            add(hint.text.toString() to settings.vocabularyHint)
        }
        return SettingsForm.hasPendingChanges(keyInputs.values.map { it.text.toString() }, edits)
    }

    /** Deshabilitado sin cambios; color de acento (el del tema) con cambios pendientes. */
    private fun updateSaveButton() {
        if (!::save.isInitialized) return
        val pending = hasPendingChanges()
        save.isEnabled = pending
        unsavedGuard.isEnabled = pending
    }

    private fun saveTypedFields() {
        for ((keyId, input) in keyInputs) {
            val key = input.text.toString()
            if (key.isNotBlank()) settings.setApiKey(keyId, key)
            input.text?.clear()
        }
        settings.setModel(Capability.TRANSCRIBE, currentTranscriber(), transcriberModel.text.toString())
        if (settings.polishEnabled) settings.setModel(Capability.POLISH, currentPolisher(), polisherModel.text.toString())
        settings.vocabularyHint = hint.text.toString()
        hint.setText(settings.vocabularyHint)
        rebuildTypedFields()
        notice(R.string.saved_changes)
        refresh()
    }

    private fun showUnsavedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.unsaved_title)
            .setMessage(R.string.unsaved_body)
            .setPositiveButton(R.string.unsaved_save) { _, _ ->
                saveTypedFields()
                finish()
            }
            .setNegativeButton(R.string.unsaved_discard) { _, _ ->
                unsavedGuard.isEnabled = false
                finish()
            }
            .setNeutralButton(R.string.unsaved_keep_editing, null)
            .show()
    }

    /** Hoja inferior con los pasos para conseguir la clave del proveedor elegido y el enlace a su página. */
    private fun showKeyHelp(entry: CatalogEntry?) {
        val help = entry?.keyHelp ?: return
        val view = layoutInflater.inflate(R.layout.sheet_key_help, null)
        view.findViewById<TextView>(R.id.help_title).text = entry.displayName
        view.findViewById<TextView>(R.id.help_billing).text = getString(R.string.key_help_billing, entry.displayName)

        val steps = KeyHelpTexts.steps(help.id)?.let { resources.getStringArray(it).toList() }
            ?: listOf(getString(R.string.key_help_generic, entry.displayName))
        val list = view.findViewById<LinearLayout>(R.id.help_steps)
        steps.forEachIndexed { i, step ->
            list.addView(
                TextView(this).apply {
                    text = getString(R.string.key_help_step, i + 1, step)
                    setTextColor(ContextCompat.getColor(context, R.color.app_on_surface))
                    textSize = 16f
                    setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
                },
            )
        }

        val sheet = BottomSheetDialog(this)
        view.findViewById<MaterialButton>(R.id.help_open).setOnClickListener {
            // Navegador del sistema; la URL sale del catálogo y no lleva datos de la persona.
            val open = Intent(Intent.ACTION_VIEW, Uri.parse(help.keyPageUrl)).addCategory(Intent.CATEGORY_BROWSABLE)
            try {
                startActivity(open)
            } catch (e: ActivityNotFoundException) {
                sheet.dismiss()
                notice(R.string.key_help_no_browser)
            }
        }
        sheet.setContentView(view)
        sheet.show()
    }

    private fun notice(resId: Int) = notice(getString(resId))

    private fun notice(text: String) = Snackbar.make(root, text, Snackbar.LENGTH_SHORT).show()

    // ---- Estado y activación ----

    /** Aviso claro y previo: qué hace el servicio, qué no hace y adónde van los datos. */
    private fun showAccessibilityDisclosure() {
        AlertDialog.Builder(this)
            .setTitle(R.string.a11y_disclosure_title)
            .setMessage(R.string.a11y_disclosure_body)
            .setPositiveButton(R.string.a11y_disclosure_continue) { _, _ ->
                startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.a11y_disclosure_cancel, null)
            .show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = AndroidSettings.Secure.getString(
            contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val me = "$packageName/${NeoprarsVoiceAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun refresh() {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val keyboardEnabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val a11y = isAccessibilityServiceEnabled()
        val missing = EngineProvider.missingKeys(settings)

        statusList.removeAllViews()
        statusRow(
            R.string.status_keys, missing.isEmpty(),
            if (missing.isEmpty()) null else getString(R.string.status_missing_keys, missing.joinToString(", ") { vendorName(it) }),
        )
        statusRow(R.string.status_mic, mic)
        statusRow(R.string.status_a11y, a11y)
        statusRow(R.string.status_keyboard, keyboardEnabled)

        // Activación: solo lo que falta (y elegir el teclado, si ya está activado).
        findViewById<View>(R.id.btn_mic).visibility = if (mic) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btn_a11y).visibility = if (a11y) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btn_enable).visibility = if (keyboardEnabled) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btn_pick).visibility = if (keyboardEnabled) View.VISIBLE else View.GONE
    }

    private fun statusRow(labelRes: Int, ok: Boolean, pendingText: String? = null) {
        val row = layoutInflater.inflate(R.layout.item_status, statusList, false)
        row.findViewById<ImageView>(R.id.status_icon)
            .setImageResource(if (ok) R.drawable.ic_status_ok else R.drawable.ic_status_pending)
        row.findViewById<TextView>(R.id.status_label).setText(labelRes)
        row.findViewById<TextView>(R.id.status_state).text =
            if (ok) getString(R.string.status_ready) else pendingText ?: getString(R.string.status_pending)
        statusList.addView(row)
    }
}
