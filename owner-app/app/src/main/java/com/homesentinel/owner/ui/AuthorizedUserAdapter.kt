package com.homesentinel.owner.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.homesentinel.owner.R
import com.homesentinel.owner.data.AuthorizedUser
import com.homesentinel.owner.databinding.ItemAuthorizedUserBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AuthorizedUserAdapter(
    private val onDeleteClick: (AuthorizedUser) -> Unit
) : RecyclerView.Adapter<AuthorizedUserAdapter.ViewHolder>() {

    private var items = listOf<AuthorizedUser>()
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    fun submitList(list: List<AuthorizedUser>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAuthorizedUserBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemAuthorizedUserBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AuthorizedUser) {
            binding.tvUserName.text = item.name
            binding.tvUserRole.text = item.role
            binding.tvUserDate.text = "Registered: ${dateFormat.format(Date(item.timestamp))}"

            if (!item.photoPath.isNullOrEmpty()) {
                Glide.with(binding.root.context)
                    .load(item.photoPath)
                    .placeholder(R.drawable.ic_person)
                    .into(binding.ivUserPhoto)
            } else {
                binding.ivUserPhoto.setImageResource(R.drawable.ic_person)
            }

            binding.btnDeleteUser.setOnClickListener {
                onDeleteClick(item)
            }
        }
    }
}
