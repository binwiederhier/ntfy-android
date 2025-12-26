package io.heckel.ntfy.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import io.heckel.ntfy.R

data class PriorityItem(
    val priority: Int,
    val label: String,
    val iconResId: Int
)

class PriorityAdapter(
    context: Context,
    private val items: List<PriorityItem>
) : ArrayAdapter<PriorityItem>(context, R.layout.item_priority_dropdown, items) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createItemView(position, convertView, parent)
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createItemView(position, convertView, parent)
    }

    private fun createItemView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_priority_dropdown, parent, false)

        val item = items[position]
        val iconView = view.findViewById<ImageView>(R.id.priority_icon)
        val textView = view.findViewById<TextView>(R.id.priority_text)

        iconView.setImageResource(item.iconResId)
        textView.text = item.label

        return view
    }

    companion object {
        fun createPriorityItems(context: Context): List<PriorityItem> {
            return listOf(
                PriorityItem(1, context.getString(R.string.publish_dialog_priority_min), R.drawable.ic_priority_1_24dp),
                PriorityItem(2, context.getString(R.string.publish_dialog_priority_low), R.drawable.ic_priority_2_24dp),
                PriorityItem(3, context.getString(R.string.publish_dialog_priority_default), R.drawable.ic_priority_3_24dp),
                PriorityItem(4, context.getString(R.string.publish_dialog_priority_high), R.drawable.ic_priority_4_24dp),
                PriorityItem(5, context.getString(R.string.publish_dialog_priority_max), R.drawable.ic_priority_5_24dp)
            )
        }
    }
}

