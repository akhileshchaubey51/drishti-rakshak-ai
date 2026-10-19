package com.homesentinel.owner.data

import androidx.room.*

@Dao
interface AuthorizedUserDao {
    @Query("SELECT * FROM owner_authorized_users ORDER BY id DESC")
    suspend fun getAll(): List<AuthorizedUser>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(user: AuthorizedUser): Long

    @Delete
    suspend fun delete(user: AuthorizedUser)

    @Query("SELECT COUNT(*) FROM owner_authorized_users")
    suspend fun getCount(): Int
}
