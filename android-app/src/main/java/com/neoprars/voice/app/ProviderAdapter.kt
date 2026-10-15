package com.neoprars.voice.app

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.TextView

/**
 * Opciones de un selector de proveedores. Las que no se pueden elegir (por ejemplo, "Próximamente") se ven
 * atenuadas y no responden al toque. No filtra por lo escrito: el selector siempre muestra la lista completa.
 */
class ProviderAdapter(
    context: Context,
    private val labels: List<String>,
    private val enabled: List<Boolean>,
) : ArrayAdapter<String>(context, R.layout.item_dropdown, labels) {

    override fun areAllItemsEnabled(): Boolean = enabled.all { it }

    override fun isEnabled(position: Int): Boolean = enabled.getOrElse(position) { false }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent) as TextView
        view.isEnabled = isEnabled(position)
        view.alpha = if (isEnabled(position)) 1f else 0.45f
        return view
    }

    private val all = object : Filter() {
        override fun performFiltering(constraint: CharSequence?) =
            FilterResults().apply { values = labels; count = labels.size }

        override fun publishResults(constraint: CharSequence?, results: FilterResults?) = notifyDataSetChanged()
    }

    override fun getFilter(): Filter = all
}
