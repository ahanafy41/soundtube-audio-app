package com.soundtube.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_history")
data class PlaybackHistory(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelTitle: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedAt: Long = System.currentTimeMillis()
)

// Alias for references_manifest compatibility
typealias PlaybackHistoryEntity = PlaybackHistory

@Entity(tableName = "subscriptions")
data class Subscription(
    @PrimaryKey val channelId: String,
    val channelTitle: String,
    val avatarUrl: String? = null,
    val subscribedAt: Long = System.currentTimeMillis()
)

// Alias for references_manifest compatibility
typealias ChannelEntity = Subscription

@Entity(tableName = "download_records")
data class DownloadRecord(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelTitle: String,
    val localFilePath: String,
    val totalBytes: Long,
    val downloadedAt: Long = System.currentTimeMillis()
)

typealias DownloadRecordEntity = DownloadRecord
