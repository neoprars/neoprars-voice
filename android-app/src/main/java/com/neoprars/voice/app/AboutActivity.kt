package com.neoprars.voice.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat

/**
 * Acerca de: nombre, versión, autoría, licencia (con el aviso de marca), código fuente, privacidad y
 * licencias de terceros. Los textos legales salen de res/raw (copias de LICENSE, NOTICE y las licencias
 * de las bibliotecas); los enlaces se abren en el navegador del sistema.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val info = packageManager.getPackageInfo(packageName, 0)
        findViewById<TextView>(R.id.about_version).text =
            getString(R.string.about_version, info.versionName, PackageInfoCompat.getLongVersionCode(info))

        val rows = findViewById<LinearLayout>(R.id.about_rows)
        addRow(rows, R.string.about_license, getString(R.string.about_license_summary)) {
            showText(R.string.about_license, readRaw(R.raw.license_app) + "\n\n" + readRaw(R.raw.notice_app))
        }
        val sourceUrl = getString(R.string.about_source_url)
        if (sourceUrl.isNotBlank()) {
            addRow(rows, R.string.about_source, sourceUrl) { openUrl(sourceUrl) }
        }
        addRow(rows, R.string.about_privacy, getString(R.string.about_privacy_summary)) {
            showText(R.string.about_privacy, getString(R.string.about_privacy_body))
        }
        addRow(rows, R.string.about_third_party, getString(R.string.about_third_party_summary)) {
            showText(R.string.about_third_party, readRaw(R.raw.third_party_licenses))
        }
    }

    private fun addRow(parent: LinearLayout, title: Int, subtitle: String, onClick: () -> Unit) {
        val row = layoutInflater.inflate(R.layout.item_about_row, parent, false)
        row.findViewById<TextView>(R.id.row_title).setText(title)
        row.findViewById<TextView>(R.id.row_subtitle).text = subtitle
        row.setOnClickListener { onClick() }
        parent.addView(row)
    }

    private fun readRaw(id: Int): String = resources.openRawResource(id).bufferedReader().use { it.readText() }

    /** Texto largo en un diálogo con desplazamiento; se puede seleccionar y copiar. */
    private fun showText(title: Int, text: String) {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val body = TextView(this).apply {
            this.text = text
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.app_on_surface))
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(R.string.about_close, null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.key_help_no_browser, Toast.LENGTH_SHORT).show()
        }
    }
}
