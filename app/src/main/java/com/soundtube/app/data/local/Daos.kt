package com.soundtube.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackHistoryDao {
    @Query("SELECT * FROM playback_history ORDER BY lastPlayedAt DESC")
    fun getAllHistory(): Flow<List<PlaybackHistory>>

    @Query("SELECT * FROM playback_history WHERE videoId = :videoId LIMIT 1")
    suspend fun getHistoryItem(videoId: String): PlaybackHistory?

    @Query("SELECT positionMs FROM playback_history WHERE videoId = :videoId LIMIT 1")
    suspend fun getPlaybackPosition(videoId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(history: PlaybackHistory)

    @Query("UPDATE playback_history SET positionMs = :positionMs, durationMs = :durationMs, lastPlayedAt = :lastPlayedAt WHERE videoId = :videoId")
    suspend fun updatePosition(videoId: String, positionMs: Long, durationMs: Long, lastPlayedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM playback_history WHERE videoId = :videoId")
    suspend fun deleteHistory(videoId: String)

    @Query("DELETE FROM playback_history")
    suspend fun clearHistory()
}

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY channelTitle COLLATE NOCASE ASC")
    fun getAllSubscriptions(): Flow<List<Subscription>>

    @Query("SELECT * FROM subscriptions ORDER BY subscribedAt DESC")
    fun getAllSubscriptionsByDate(): Flow<List<Subscription>>

    @Query("SELECT EXISTS(SELECT 1 FROM subscriptions WHERE channelId = :channelId)")
    fun isSubscribedFlow(channelId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM subscriptions WHERE channelId = :channelId)")
    suspend fun isSubscribed(channelId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun subscribe(subscription: Subscription)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(subscriptions: List<Subscription>)

    @Query("SELECT * FROM subscriptions ORDER BY channelTitle COLLATE NOCASE ASC")
    suspend fun getAllSubscriptionsList(): List<Subscription>

    @Query("DELETE FROM subscriptions WHERE channelId = :channelId")
    suspend fun unsubscribe(channelId: String)
}

@Dao
interface DownloadRecordDao {
    @Query("SELECT * FROM download_records ORDER BY downloadedAt DESC")
    fun getAllDownloads(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM download_records WHERE videoId = :videoId LIMIT 1")
    suspend fun getDownload(videoId: String): DownloadRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(record: DownloadRecord)

    @Query("DELETE FROM download_records WHERE videoId = :videoId")
    suspend fun deleteDownload(videoId: String)
}
