package io.heckel.ntfy.ui.detail

import android.view.View
import androidx.recyclerview.widget.RecyclerView


abstract class DetailItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    abstract fun bind(item: DetailItem)
}
