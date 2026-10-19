package com.homesentinel.owner.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "owner_authorized_users")
data class AuthorizedUser(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val role: String,
    val photoPath: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
