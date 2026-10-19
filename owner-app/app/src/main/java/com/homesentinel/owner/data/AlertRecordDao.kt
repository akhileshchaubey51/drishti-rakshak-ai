package com.homesentinel.owner.data

import androidx.room.*

@Dao
interface AlertRecordDao {
    @Query("SELECT * FROM owner_alert_records ORDER BY timestamp DESC")
    suspend fun getAll(): List<AlertRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alert: AlertRecord): Long

    @Query("DELETE FROM owner_alert_records")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM owner_alert_records")
    suspend fun getCount(): Int
}
