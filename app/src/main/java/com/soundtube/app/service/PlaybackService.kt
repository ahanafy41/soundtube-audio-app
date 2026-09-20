package com.soundtube.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.soundtube.app.MainActivity
import com.soundtube.app.data.local.PlaybackHistory
import com.soundtube.app.data.local.SoundTubeDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var progressTrackerJob: Job? = null
    private lateinit var database: SoundTubeDatabase

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "soundtube_playback_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PLAY = "com.soundtube.app.ACTION_PLAY"
        const val ACTION_PAUSE = "com.soundtube.app.ACTION_PAUSE"
        const val ACTION_SEEK_BACK = "com.soundtube.app.ACTION_SEEK_BACK"
        const val ACTION_SEEK_FORWARD = "com.soundtube.app.ACTION_SEEK_FORWARD"
    }

    override fun onCreate() {
        super.onCreate()
        database = SoundTubeDatabase.getInstance(applicationContext)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        createNotificationChannel()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startProgressTracker()
                } else {
                    stopProgressTracker()
                    persistCurrentPosition()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    // Reset position when completed
                    serviceScope.launch(Dispatchers.IO) {
                        val currentMediaId = player.currentMediaItem?.mediaId
                        if (!currentMediaId.isNullOrEmpty()) {
                            database.playbackHistoryDao().updatePosition(currentMediaId, 0L, player.duration)
                        }
                    }
                }
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    private fun startProgressTracker() {
        progressTrackerJob?.cancel()
        progressTrackerJob = serviceScope.launch {
            while (isActive) {
                delay(5000)
                persistCurrentPosition()
            }
        }
    }

    private fun stopProgressTracker() {
        progressTrackerJob?.cancel()
        progressTrackerJob = null
    }

    private fun persistCurrentPosition() {
        val currentMediaItem = player.currentMediaItem ?: return
        val videoId = currentMediaItem.mediaId
        if (videoId.isEmpty()) return

        val pos = player.currentPosition
        val duration = player.duration
        val title = currentMediaItem.mediaMetadata.title?.toString() ?: "Unknown"
        val channel = currentMediaItem.mediaMetadata.artist?.toString() ?: "Unknown"

        serviceScope.launch(Dispatchers.IO) {
            val history = PlaybackHistory(
                videoId = videoId,
                title = title,
                channelTitle = channel,
                positionMs = if (pos >= 0) pos else 0L,
                durationMs = if (duration >= 0) duration else 0L,
                lastPlayedAt = System.currentTimeMillis()
            )
            database.playbackHistoryDao().insertOrUpdate(history)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "SoundTube Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls background audio playback and resumption for SoundTube"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopProgressTracker()
        persistCurrentPosition()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
