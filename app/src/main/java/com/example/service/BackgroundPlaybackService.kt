package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R

/**
 * Keeps the process alive while YouTube plays in the background and provides media controls
 * (notification, lock screen, headset) through a MediaSession.
 *
 * Playback itself happens in the WebView; this service only mirrors its state. Control actions are
 * forwarded to the page through [PlaybackCommands].
 */
class BackgroundPlaybackService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var mediaSession: MediaSessionCompat
    private var title = ""
    private var artist = ""
    private var durationSec = 0
    private var playing = false
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "StreamShield").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = handleControl(PlaybackCommand.PLAY)
                override fun onPause() = handleControl(PlaybackCommand.PAUSE)
                override fun onStop() = handleControl(PlaybackCommand.STOP)
                override fun onSkipToNext() = handleControl(PlaybackCommand.NEXT)
                override fun onSkipToPrevious() = handleControl(PlaybackCommand.PREVIOUS)
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE -> {
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty()
                durationSec = intent.getIntExtra(EXTRA_DURATION, 0)
                playing = intent.getBooleanExtra(EXTRA_PLAYING, false)
                render()
            }
            ACTION_PLAY -> handleControl(PlaybackCommand.PLAY)
            ACTION_PAUSE -> handleControl(PlaybackCommand.PAUSE)
            ACTION_STOP -> handleControl(PlaybackCommand.STOP)
            ACTION_NEXT -> handleControl(PlaybackCommand.NEXT)
            ACTION_PREVIOUS -> handleControl(PlaybackCommand.PREVIOUS)
            ACTION_SHUTDOWN -> {
                // May arrive right after startForegroundService(): honour that contract first.
                if (!isForeground) startForegroundSafely(buildNotification())
                shutdown()
            }
            else -> {
                // Restarted by the system with no state (the WebView is gone): nothing to control.
                startForegroundSafely(buildNotification())
                shutdown()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleControl(command: PlaybackCommand) {
        PlaybackCommands.send(command)
        when (command) {
            // Reflect the change immediately; the page confirms it a moment later via ACTION_UPDATE.
            // Going foreground here also happens inside the notification/media-button allowance,
            // which is what lets playback resume from the background on Android 12+.
            PlaybackCommand.PLAY -> { playing = true; render() }
            PlaybackCommand.PAUSE -> { playing = false; render() }
            PlaybackCommand.TOGGLE -> { playing = !playing; render() }
            PlaybackCommand.STOP -> shutdown()
            // The page reports the new track a moment later via ACTION_UPDATE.
            PlaybackCommand.NEXT, PlaybackCommand.PREVIOUS -> Unit
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away destroys the activity and its WebView, so playback is over; the
        // up-next cache goes with it (as agreed: it lives only while the app is open).
        shutdown()
        com.example.offline.UpNextCache.clearDir(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        mediaSession.isActive = false
        mediaSession.release()
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun shutdown() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForeground = false
        stopSelf()
    }

    private fun render() {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title.ifBlank { "YouTube" })
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .apply {
                    if (durationSec > 0) putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationSec * 1000L)
                }
                .build()
        )
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f
                )
                .build()
        )

        val notification = buildNotification()
        if (playing) {
            startForegroundSafely(notification)
            acquireWakeLock()
        } else {
            // A service started with startForegroundService() must call startForeground() once,
            // even if the video was paused in the meantime.
            if (!isForeground) startForegroundSafely(notification)
            // Paused: let the system reclaim the app if it needs to, but keep the controls around.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            isForeground = false
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
            releaseWakeLock()
        }
    }

    private fun startForegroundSafely(notification: Notification) {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                }
            )
            isForeground = true
        } catch (_: Exception) {
            // Not allowed from the background on Android 12+; the notification is still shown.
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        try {
            val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamShield::BackgroundPlayback")
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
            lock.acquire(3 * 60 * 60 * 1000L)
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_bg_playback_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_bg_playback_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 100, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, BackgroundPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(): Notification {
        val playPause = if (playing) {
            NotificationCompat.Action(android.R.drawable.ic_media_pause, "Pause", serviceIntent(ACTION_PAUSE, 102))
        } else {
            NotificationCompat.Action(android.R.drawable.ic_media_play, "Play", serviceIntent(ACTION_PLAY, 103))
        }
        val stop = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel, "Stop", serviceIntent(ACTION_STOP, 101)
        )
        val previous = NotificationCompat.Action(
            android.R.drawable.ic_media_previous, "Previous", serviceIntent(ACTION_PREVIOUS, 105)
        )
        val next = NotificationCompat.Action(
            android.R.drawable.ic_media_next, "Next", serviceIntent(ACTION_NEXT, 106)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title.ifBlank { "YouTube" })
            .setContentText(artist)
            .setContentIntent(openAppIntent())
            .setDeleteIntent(serviceIntent(ACTION_STOP, 104))
            .setOngoing(playing)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(previous)
            .addAction(playPause)
            .addAction(next)
            .addAction(stop)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    companion object {
        const val CHANNEL_ID = "streamshield_bg_playback_channel"
        const val NOTIFICATION_ID = 4088
        private const val ACTION_UPDATE = "com.example.action.BG_UPDATE"
        private const val ACTION_PLAY = "com.example.action.BG_PLAY"
        private const val ACTION_PAUSE = "com.example.action.BG_PAUSE"
        private const val ACTION_STOP = "com.example.action.BG_STOP"
        private const val ACTION_NEXT = "com.example.action.BG_NEXT"
        private const val ACTION_PREVIOUS = "com.example.action.BG_PREVIOUS"
        private const val ACTION_SHUTDOWN = "com.example.action.BG_SHUTDOWN"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_ARTIST = "extra_artist"
        private const val EXTRA_DURATION = "extra_duration"
        private const val EXTRA_PLAYING = "extra_playing"

        /**
         * Mirrors the page's playback state. [alreadyRunning] selects startService over
         * startForegroundService, which Android 12+ refuses from the background.
         */
        fun update(
            context: Context,
            title: String,
            artist: String,
            durationSec: Int,
            playing: Boolean,
            alreadyRunning: Boolean
        ) {
            val intent = Intent(context, BackgroundPlaybackService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_ARTIST, artist)
                .putExtra(EXTRA_DURATION, durationSec)
                .putExtra(EXTRA_PLAYING, playing)
            try {
                if (!alreadyRunning && playing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        /**
         * Removes the controls without touching playback. Goes through onStartCommand rather than
         * stopService() so a service just started with startForegroundService() can still call
         * startForeground() first (otherwise Android 9-11 crash the app).
         */
        fun stop(context: Context) {
            val intent = Intent(context, BackgroundPlaybackService::class.java).setAction(ACTION_SHUTDOWN)
            try {
                context.startService(intent)
            } catch (_: Exception) {
                // Not allowed from the background: the service is not foreground, so stopping is safe.
                try {
                    context.stopService(Intent(context, BackgroundPlaybackService::class.java))
                } catch (_: Exception) {
                }
            }
        }
    }
}
