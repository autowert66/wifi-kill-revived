package dev.a99.wifikill.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.a99.wifikill.databinding.ItemHostBinding
import dev.a99.wifikill.model.Host

class HostListAdapter(
    private val onKillToggled: (Host, Boolean) -> Unit,
) : ListAdapter<Host, HostListAdapter.HostViewHolder>(HostDiffCallback()) {

    class HostViewHolder(val binding: ItemHostBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HostViewHolder =
        HostViewHolder(
            ItemHostBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: HostViewHolder, position: Int) {
        val host = getItem(position)
        val b = holder.binding
        b.titleText.text = host.hostname ?: host.ip
        b.manufacturerText.visibility =
            if (host.manufacturer != null) View.VISIBLE else View.GONE
        b.manufacturerText.text = host.manufacturer
        b.ipText.visibility = if (host.hostname != null) View.VISIBLE else View.GONE
        b.ipText.text = host.ip
        b.macText.text = host.mac

        b.killSwitch.setOnCheckedChangeListener(null)
        b.killSwitch.isChecked = host.isKilled
        b.killSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != host.isKilled) {
                onKillToggled(host, checked)
            }
        }
    }

    class HostDiffCallback : DiffUtil.ItemCallback<Host>() {
        override fun areItemsTheSame(oldItem: Host, newItem: Host): Boolean =
            oldItem.ip == newItem.ip

        override fun areContentsTheSame(oldItem: Host, newItem: Host): Boolean =
            oldItem == newItem
    }
}
