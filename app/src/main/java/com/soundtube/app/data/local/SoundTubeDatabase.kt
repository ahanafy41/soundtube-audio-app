package com.soundtube.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PlaybackHistory::class, Subscription::class, DownloadRecord::class, FeedCacheEntity::class],
    version = 2,
    exportSchema = false
)
abstract class SoundTubeDatabase : RoomDatabase() {
    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun downloadRecordDao(): DownloadRecordDao
    abstract fun feedCacheDao(): FeedCacheDao

    companion object {
        @Volatile
        private var INSTANCE: SoundTubeDatabase? = null

        fun getInstance(context: Context): SoundTubeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SoundTubeDatabase::class.java,
                    "soundtube.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
