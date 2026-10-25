package app.mytv.player

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Lista simple de filas de texto, navegable con control remoto. */
class RowAdapter<T>(
    private val label: (T) -> String,
    private val onClick: (item: T, position: Int) -> Unit
) : RecyclerView.Adapter<RowAdapter.VH>() {

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    private var items: List<T> = emptyList()
    private var selectedIndex = -1

    fun submit(list: List<T>) {
        items = list
        selectedIndex = -1
        notifyDataSetChanged()
    }

    fun select(index: Int) {
        val old = selectedIndex
        selectedIndex = index
        if (old in items.indices) notifyItemChanged(old)
        if (index in items.indices) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false) as TextView
        return VH(tv)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tv.text = label(item)
        holder.tv.isSelected = position == selectedIndex
        holder.tv.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(items[pos], pos)
        }
    }
}
