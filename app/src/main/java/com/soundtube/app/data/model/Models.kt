package com.soundtube.app.data.model

data class AudioStreamInfo(
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val channelId: String,
    val streamUrl: String,
    val format: String,
    val bitrate: Int,
    val durationSeconds: Long,
    val thumbnailUrl: String? = null
)

data class VideoFeedItem(
    val videoId: String,
    val title: String,
    val uploaderName: String,
    val uploaderUrl: String? = null,
    val durationText: String? = null,
    val durationSeconds: Long = 0L,
    val thumbnailUrl: String? = null,
    val uploadDate: String? = null
)

enum class DownloadState {
    PENDING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELED
}

data class VideoStreamOption(
    val resolution: String,
    val format: String = "MP4",
    val streamUrl: String
)

data class MediaStreamDetails(
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val channelId: String,
    val durationSeconds: Long,
    val thumbnailUrl: String? = null,
    val audioStream: AudioStreamInfo? = null,
    val videoStreams: List<VideoStreamOption> = emptyList()
)

data class DownloadItemState(
    val videoId: String,
    val title: String,
    val progressPercent: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val state: DownloadState = DownloadState.PENDING,
    val localFilePath: String? = null,
    val errorMessage: String? = null,
    val isVideo: Boolean = false,
    val qualityLabel: String? = null
)
