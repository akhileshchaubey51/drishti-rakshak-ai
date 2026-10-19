package com.homesentinel.owner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [AlertRecord::class, AuthorizedUser::class], version = 1, exportSchema = false)
abstract class OwnerDatabase : RoomDatabase() {
    abstract fun alertRecordDao(): AlertRecordDao
    abstract fun authorizedUserDao(): AuthorizedUserDao

    companion object {
        @Volatile
        private var INSTANCE: OwnerDatabase? = null

        fun getInstance(context: Context): OwnerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    OwnerDatabase::class.java,
                    "home_sentinel_owner.db"
                ).fallbackToDestructiveMigration()
                 .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
