package com.homesentinel.owner.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.homesentinel.owner.R
import com.homesentinel.owner.data.AlertRecord
import com.homesentinel.owner.databinding.ItemOwnerAlertBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OwnerAlertAdapter(
    private val onItemClick: (AlertRecord) -> Unit
) : RecyclerView.Adapter<OwnerAlertAdapter.ViewHolder>() {

    private var items = listOf<AlertRecord>()
    private val timeFormat = SimpleDateFormat("dd MMM, hh:mm:ss a", Locale.getDefault())

    fun submitList(list: List<AlertRecord>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOwnerAlertBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemOwnerAlertBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AlertRecord) {
            binding.tvAlertBadge.text = item.alertType.uppercase()
            binding.tvConfidence.text = "${(item.confidence * 100).toInt()}% Conf"
            binding.tvAlertLabel.text = item.label
            binding.tvAlertTimestamp.text = timeFormat.format(Date(item.timestamp))

            if (item.isAuthorized) {
                binding.tvAlertBadge.setTextColor(Color.parseColor("#00FF88"))
                binding.tvAlertBadge.setBackgroundResource(R.drawable.badge_status_active)
                binding.ivSirenIcon.visibility = View.GONE
            } else {
                binding.tvAlertBadge.setTextColor(Color.parseColor("#EF4444"))
                binding.tvAlertBadge.setBackgroundResource(R.drawable.bg_danger_alert)
                binding.ivSirenIcon.visibility = if (item.sirenTriggered) View.VISIBLE else View.GONE
            }

            val path = item.snapshotPath
            if (!path.isNullOrEmpty()) {
                if (path.length > 200 && !path.startsWith("http") && !path.startsWith("/")) {
                    // Base64 image from Cloud Firestore
                    try {
                        val decodedBytes = Base64.decode(path, Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                        binding.ivAlertSnapshot.setImageBitmap(bitmap)
                    } catch (e: Exception) {
                        binding.ivAlertSnapshot.setImageResource(R.drawable.ic_camera)
                    }
                } else {
                    Glide.with(binding.root.context)
                        .load(path)
                        .placeholder(R.drawable.ic_camera)
                        .into(binding.ivAlertSnapshot)
                }
            } else {
                binding.ivAlertSnapshot.setImageResource(R.drawable.ic_camera)
            }

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}
