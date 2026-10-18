package com.soundtube.app.download

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.soundtube.app.data.local.DownloadRecord
import com.soundtube.app.data.local.SoundTubeDatabase
import com.soundtube.app.data.model.DownloadItemState
import com.soundtube.app.data.model.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class AudioDownloadManager(private val context: Context) {

    private val db = SoundTubeDatabase.getInstance(context)
    private val downloadRecordDao = db.downloadRecordDao()
    private val client = OkHttpClient()
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    private val _downloadStates = MutableStateFlow<Map<String, DownloadItemState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadItemState>> = _downloadStates.asStateFlow()

    fun getDownloadStatus(videoId: String): DownloadItemState? {
        return _downloadStates.value[videoId]
    }

    private fun getSoundTubeDownloadDir(): File {
        val publicDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val soundTubeDir = File(publicDownloadDir, "SoundTube")
        if (soundTubeDir.exists() || soundTubeDir.mkdirs()) {
            return soundTubeDir
        }
        val directSdcardDir = File("/sdcard/Download/SoundTube")
        if (directSdcardDir.exists() || directSdcardDir.mkdirs()) {
            return directSdcardDir
        }
        val fallback = context.getExternalFilesDir("audio_downloads") ?: context.filesDir
        if (!fallback.exists()) fallback.mkdirs()
        return fallback
    }

    fun downloadAudio(
        videoId: String,
        title: String,
        channelTitle: String,
        streamUrl: String
    ) {
        downloadMedia(
            videoId = videoId,
            title = title,
            channelTitle = channelTitle,
            streamUrl = streamUrl,
            isVideo = false,
            qualityLabel = "M4A"
        )
    }

    fun downloadVideo(
        videoId: String,
        title: String,
        channelTitle: String,
        streamUrl: String,
        resolution: String = "720p"
    ) {
        downloadMedia(
            videoId = videoId,
            title = title,
            channelTitle = channelTitle,
            streamUrl = streamUrl,
            isVideo = true,
            qualityLabel = resolution
        )
    }

    fun downloadMedia(
        videoId: String,
        title: String,
        channelTitle: String,
        streamUrl: String,
        isVideo: Boolean = false,
        qualityLabel: String = ""
    ) {
        val initialState = DownloadItemState(
            videoId = videoId,
            title = title,
            progressPercent = 0,
            state = DownloadState.DOWNLOADING,
            isVideo = isVideo,
            qualityLabel = qualityLabel
        )
        updateState(videoId, initialState)

        coroutineScope.launch {
            try {
                val downloadDir = getSoundTubeDownloadDir()
                val extension = if (isVideo) "mp4" else "m4a"
                val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val qualitySuffix = if (isVideo && qualityLabel.isNotBlank()) "_${qualityLabel}" else ""
                val fileName = "${videoId}_${safeTitle}${qualitySuffix}.${extension}"
                val targetFile = File(downloadDir, fileName)
                val tempFile = File(downloadDir, "${fileName}.tmp")

                val request = Request.Builder()
                    .url(streamUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                    .addHeader("Referer", "https://www.youtube.com/")
                    .addHeader("Origin", "https://www.youtube.com")
                    .build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw IOException("HTTP Error: ${response.code}")
                }

                val body = response.body ?: throw IOException("Empty response body")
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        var lastProgressPercent = 0

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            val percent = if (totalBytes > 0) {
                                ((downloadedBytes * 100) / totalBytes).toInt()
                            } else {
                                0
                            }

                            if (percent != lastProgressPercent) {
                                lastProgressPercent = percent
                                updateState(
                                    videoId,
                                    DownloadItemState(
                                        videoId = videoId,
                                        title = title,
                                        progressPercent = percent,
                                        downloadedBytes = downloadedBytes,
                                        totalBytes = totalBytes,
                                        state = DownloadState.DOWNLOADING,
                                        isVideo = isVideo,
                                        qualityLabel = qualityLabel
                                    )
                                )
                            }
                        }
                        output.flush()
                    }
                }

                // Atomic rename
                if (tempFile.exists()) {
                    if (targetFile.exists()) {
                        targetFile.delete()
                    }
                    tempFile.renameTo(targetFile)
                }

                // Notify Android MediaScanner so file appears in system file manager and media players
                try {
                    val mimeType = if (isVideo) "video/mp4" else "audio/mp4"
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(targetFile.absolutePath),
                        arrayOf(mimeType),
                        null
                    )
                } catch (_: Exception) {}

                // Record into Room database
                val displayTitle = if (isVideo && qualityLabel.isNotBlank()) "$title ($qualityLabel)" else title
                val record = DownloadRecord(
                    videoId = videoId,
                    title = displayTitle,
                    channelTitle = channelTitle,
                    localFilePath = targetFile.absolutePath,
                    totalBytes = targetFile.length()
                )
                downloadRecordDao.insertDownload(record)

                updateState(
                    videoId,
                    DownloadItemState(
                        videoId = videoId,
                        title = displayTitle,
                        progressPercent = 100,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                        state = DownloadState.COMPLETED,
                        localFilePath = targetFile.absolutePath,
                        isVideo = isVideo,
                        qualityLabel = qualityLabel
                    )
                )

            } catch (e: Exception) {
                updateState(
                    videoId,
                    DownloadItemState(
                        videoId = videoId,
                        title = title,
                        progressPercent = 0,
                        state = DownloadState.FAILED,
                        errorMessage = e.localizedMessage ?: "Download failed",
                        isVideo = isVideo,
                        qualityLabel = qualityLabel
                    )
                )
            }
        }
    }

    suspend fun removeDownload(videoId: String) = withContext(Dispatchers.IO) {
        val record = downloadRecordDao.getDownload(videoId)
        if (record != null) {
            val file = File(record.localFilePath)
            if (file.exists()) {
                file.delete()
            }
            downloadRecordDao.deleteDownload(videoId)
        }
        val current = _downloadStates.value.toMutableMap()
        current.remove(videoId)
        _downloadStates.value = current
    }

    private fun updateState(videoId: String, state: DownloadItemState) {
        val current = _downloadStates.value.toMutableMap()
        current[videoId] = state
        _downloadStates.value = current
    }

    companion object {
        @Volatile
        private var INSTANCE: AudioDownloadManager? = null

        fun getInstance(context: Context): AudioDownloadManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AudioDownloadManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
