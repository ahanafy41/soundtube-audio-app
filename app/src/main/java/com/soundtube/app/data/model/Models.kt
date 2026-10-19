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

fun List<VideoFeedItem>.sortedByNewest(): List<VideoFeedItem> {
    return this.sortedWith(
        compareByDescending<VideoFeedItem> { item ->
            parseUploadDateScore(item.uploadDate)
        }
    )
}

fun parseUploadDateScore(dateStr: String?): Long {
    if (dateStr.isNullOrBlank()) return 0L
    val s = dateStr.trim().lowercase()

    val isoMatch = Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(s)
    if (isoMatch != null) {
        val (y, m, d) = isoMatch.destructured
        val year = y.toLongOrNull() ?: 2026L
        val month = m.toLongOrNull() ?: 1L
        val day = d.toLongOrNull() ?: 1L
        val daysSince2000 = (year - 2000) * 365 + month * 30 + day
        val nowDays = (2026 - 2000) * 365 + 10 * 30 + 19
        val diffDays = (nowDays - daysSince2000).coerceAtLeast(0L)
        val minutesAgo = diffDays * 1440
        return (100_000_000L - minutesAgo).coerceAtLeast(1L)
    }

    val numberInText = Regex("""\d+""").find(s)?.value?.toLongOrNull() ?: 1L
    val minutesAgo: Long = when {
        s.contains("ثاني") || s.contains("second") -> (numberInText / 60).coerceAtLeast(0L)
        s.contains("دقيق") || s.contains("minute") -> numberInText
        s.contains("ساعتين") -> 120L
        s.contains("ساع") || s.contains("hour") -> numberInText * 60
        s.contains("يومين") -> 2 * 1440L
        s.contains("يوم") || s.contains("day") -> numberInText * 1440
        s.contains("أسبوعين") || s.contains("اسبوعين") -> 2 * 7 * 1440L
        s.contains("أسبوع") || s.contains("اسبوع") || s.contains("week") -> numberInText * 7 * 1440
        s.contains("شهرين") -> 2 * 30 * 1440L
        s.contains("شهر") || s.contains("month") -> numberInText * 30 * 1440
        s.contains("سنتين") || s.contains("عامين") -> 2 * 365 * 1440L
        s.contains("سن") || s.contains("عام") || s.contains("year") -> numberInText * 365 * 1440
        else -> 999_999L
    }
    return (100_000_000L - minutesAgo).coerceAtLeast(1L)
}

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

data class PlaylistFeedItem(
    val playlistId: String,
    val title: String,
    val uploaderName: String,
    val videoCount: Long = 0L,
    val thumbnailUrl: String? = null
)
