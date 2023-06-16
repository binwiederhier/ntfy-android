package io.heckel.ntfy.ui.detail

import android.view.View
import io.heckel.ntfy.R


class UnreadDividerItemViewHolder(itemView: View) : DetailItemViewHolder(itemView) {

    override fun bind(item: DetailItem) {
        // nothing to do
    }

    companion object {
        const val LAYOUT = R.layout.item_unread_divider
    }
}
