package com.tiktokmusic.clean

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class OriginMediaService : Service() {

    companion object {
        const val CHANNEL_ID = "tiktok_clean_origin_channel"
        const val NOTIFICATION_ID = 2026
        const val ACTION_UPDATE = "com.luna.music.ACTION_UPDATE"
        const val ACTION_PLAY = "com.luna.music.ACTION_PLAY"
        const val ACTION_PAUSE = "com.luna.music.ACTION_PAUSE"
        const val ACTION_TOGGLE = "com.luna.music.ACTION_TOGGLE"
        const val ACTION_NEXT = "com.luna.music.ACTION_NEXT"
        const val ACTION_PREV = "com.luna.music.ACTION_PREV"

        @Volatile
        private var currentTitle: String = "TikTok Clean"
        @Volatile
        private var currentArtist: String = "@tiktok · Đang phát"
        @Volatile
        private var currentCoverUrl: String = ""
        @Volatile
        private var currentIsPlaying: Boolean = true
        @Volatile
        private var cachedCoverUrl: String = ""
        @Volatile
        private var cachedBitmap: Bitmap? = null

        fun updateTrack(
            context: Context,
            title: String,
            artist: String,
            coverUrl: String = "",
            isPlaying: Boolean = true
        ) {
            currentTitle = if (title.isNotBlank()) title else "TikTok Video"
            currentArtist = if (artist.isNotBlank()) artist else "@tiktok"
            currentCoverUrl = coverUrl
            currentIsPlaying = isPlaying

            val intent = Intent(context, OriginMediaService::class.java).apply {
                action = ACTION_UPDATE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }
    }

    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var defaultArtwork: Bitmap? = null
    private val imageExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        defaultArtwork = createDefaultAlbumArt()
        initLocks()
        initMediaSession()
    }

    private fun initLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TikTokClean::OriginBgWakeLock").apply {
                setReferenceCounted(false)
            }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TikTokClean::OriginWifiLock").apply {
                setReferenceCounted(false)
            }
        } catch (_: Exception) {}
    }

    private fun acquireLocks(active: Boolean) {
        try {
            if (active) {
                if (wakeLock?.isHeld == false) wakeLock?.acquire(4 * 60 * 60 * 1000L)
                if (wifiLock?.isHeld == false) wifiLock?.acquire()
            } else {
                if (wakeLock?.isHeld == true) wakeLock?.release()
                if (wifiLock?.isHeld == true) wifiLock?.release()
            }
        } catch (_: Exception) {}
    }

    private fun initMediaSession() {
        try {
            mediaSession = MediaSession(this, "TikTokCleanMediaSession").apply {
                setFlags(
                    MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
                )
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() {
                        currentIsPlaying = true
                        updatePlaybackState(PlaybackState.STATE_PLAYING)
                        refreshNotification(cachedBitmap ?: defaultArtwork)
                    }

                    override fun onPause() {
                        currentIsPlaying = false
                        updatePlaybackState(PlaybackState.STATE_PAUSED)
                        refreshNotification(cachedBitmap ?: defaultArtwork)
                    }

                    override fun onSkipToNext() {
                        updatePlaybackState(PlaybackState.STATE_PLAYING)
                        refreshNotification(cachedBitmap ?: defaultArtwork)
                    }

                    override fun onSkipToPrevious() {
                        updatePlaybackState(PlaybackState.STATE_PLAYING)
                        refreshNotification(cachedBitmap ?: defaultArtwork)
                    }
                })
                isActive = true
            }
            updatePlaybackState(PlaybackState.STATE_PLAYING)
        } catch (_: Exception) {}
    }

    private fun updatePlaybackState(state: Int) {
        val actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_STOP

        val stateBuilder = PlaybackState.Builder()
            .setActions(actions)
            .setState(
                state,
                0L,
                if (state == PlaybackState.STATE_PLAYING) 1.0f else 0.0f,
                SystemClock.elapsedRealtime()
            )

        mediaSession?.setPlaybackState(stateBuilder.build())
        acquireLocks(state == PlaybackState.STATE_PLAYING)
    }

    private fun updateMediaMetadata(artBitmap: Bitmap?) {
        val metadataBuilder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "TikTok Clean")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 180000L)

        artBitmap?.let {
            metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
            metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, it)
            metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
        }

        mediaSession?.setMetadata(metadataBuilder.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> mediaSession?.controller?.transportControls?.play()
            ACTION_PAUSE -> mediaSession?.controller?.transportControls?.pause()
            ACTION_TOGGLE -> {
                if (currentIsPlaying) {
                    mediaSession?.controller?.transportControls?.pause()
                } else {
                    mediaSession?.controller?.transportControls?.play()
                }
            }
            ACTION_NEXT -> mediaSession?.controller?.transportControls?.skipToNext()
            ACTION_PREV -> mediaSession?.controller?.transportControls?.skipToPrevious()
            else -> syncSession()
        }
        return START_STICKY
    }

    private fun syncSession() {
        val targetState = if (currentIsPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        updatePlaybackState(targetState)

        val coverUrl = currentCoverUrl
        if (coverUrl.isNotBlank() && coverUrl == cachedCoverUrl && cachedBitmap != null) {
            updateMediaMetadata(cachedBitmap)
            refreshNotification(cachedBitmap)
            return
        }

        updateMediaMetadata(defaultArtwork)
        refreshNotification(defaultArtwork)

        if (coverUrl.isNotBlank()) {
            imageExecutor.execute {
                val downloaded = downloadBitmap(coverUrl)
                if (downloaded != null) {
                    cachedCoverUrl = coverUrl
                    cachedBitmap = downloaded
                    updateMediaMetadata(downloaded)
                    refreshNotification(downloaded)
                }
            }
        }
    }

    private fun refreshNotification(artwork: Bitmap?) {
        val notif = buildMediaNotification(artwork ?: defaultArtwork)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        } catch (_: Exception) {}
    }

    private fun buildMediaNotification(artwork: Bitmap?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OriginMediaService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = PendingIntent.getService(
            this, 2,
            Intent(this, OriginMediaService::class.java).apply { action = ACTION_TOGGLE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = PendingIntent.getService(
            this, 3,
            Intent(this, OriginMediaService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (currentIsPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setSubText("TikTok Clean")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(currentIsPlaying)

        artwork?.let { builder.setLargeIcon(it) }

        builder.addAction(
            Notification.Action.Builder(
                android.R.drawable.ic_media_previous,
                "Trước",
                prevIntent
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                playPauseIcon,
                if (currentIsPlaying) "Tạm dừng" else "Phát",
                toggleIntent
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                android.R.drawable.ic_media_next,
                "Tiếp theo",
                nextIntent
            ).build()
        )

        mediaSession?.sessionToken?.let { token ->
            val style = Notification.MediaStyle()
                .setMediaSession(token)
                .setShowActionsInCompactView(0, 1, 2)
            builder.style = style
        }

        return builder.build()
    }

    private fun createDefaultAlbumArt(): Bitmap {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#121212"))

        val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#222222")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size * 0.42f, discPaint)

        val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FE2C55")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size * 0.16f, centerPaint)
        return bmp
    }

    private fun downloadBitmap(urlString: String): Bitmap? {
        return try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.instanceFollowRedirects = true
            conn.inputStream.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TikTok Clean Origin Island",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiển thị Đảo Origin và phát âm thanh khi tắt màn hình"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        acquireLocks(false)
        mediaSession?.release()
        imageExecutor.shutdown()
    }
}
