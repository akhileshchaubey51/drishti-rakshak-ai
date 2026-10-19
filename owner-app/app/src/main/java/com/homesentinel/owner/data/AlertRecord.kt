package com.homesentinel.owner.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "owner_alert_records")
data class AlertRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val alertType: String,
    val label: String,
    val confidence: Float,
    val isAuthorized: Boolean,
    val sirenTriggered: Boolean,
    val snapshotPath: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
