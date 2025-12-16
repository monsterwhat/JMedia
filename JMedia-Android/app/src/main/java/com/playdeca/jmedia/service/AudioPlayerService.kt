package com.playdeca.jmedia.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.playdeca.jmedia.MainActivity
import com.playdeca.jmedia.R

class AudioPlayerService : android.app.Service() {

    private lateinit var exoPlayer: ExoPlayer
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): AudioPlayerService = this@AudioPlayerService
    }

    override fun onCreate() {
        super.onCreate()
        exoPlayer = ExoPlayer.Builder(this).build()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "JMusic Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Music playback controls"
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    fun playMedia(url: String) {
        val mediaItem = MediaItem.fromUri(url)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
        startForegroundService()
    }

    fun pauseMedia() {
        exoPlayer.pause()
        stopForeground(false)
    }

    fun stopMedia() {
        exoPlayer.stop()
        stopForeground(true)
        stopSelf()
    }

    fun seekTo(position: Long) {
        exoPlayer.seekTo(position)
    }

    fun setVolume(volume: Float) {
        exoPlayer.volume = volume
    }

    fun getPlayer(): ExoPlayer = exoPlayer

    private fun startForegroundService() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JMusic")
            .setContentText("Playing music")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        exoPlayer.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "jmusic_playback_channel"
        private const val NOTIFICATION_ID = 1
    }
}