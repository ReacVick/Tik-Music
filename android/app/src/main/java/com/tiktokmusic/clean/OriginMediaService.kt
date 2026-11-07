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
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
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
        const val CHANNEL_ID = "tiktok_music_origin_island_channel"
        const val NOTIFICATION_ID = 2026
        const val ACTION_UPDATE = "com.luna.music.ACTION_UPDATE"
        const val ACTION_PLAY = "com.luna.music.ACTION_PLAY"
        const val ACTION_PAUSE = "com.luna.music.ACTION_PAUSE"
        const val ACTION_TOGGLE = "com.luna.music.ACTION_TOGGLE"
        const val ACTION_NEXT = "com.luna.music.ACTION_NEXT"
        const val ACTION_PREV = "com.luna.music.ACTION_PREV"

        @Volatile
        private var currentTitle: String = "TikTok Music Clean"
        @Volatile
        private var currentArtist: String = "@nhacchill · TikTok Music"
        @Volatile
        private var currentCoverUrl: String = ""
        @Volatile
        private var currentIsPlaying: Boolean = true
        @Volatile
        private var currentPosMs: Long = 0L
        @Volatile
        private var currentDurMs: Long = 180000L
        @Volatile
        private var cachedCoverUrl: String = ""
        @Volatile
        private var cachedBitmap: Bitmap? = null
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
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TikTokMusic::OriginBgWakeLock").apply {
                setReferenceCounted(false)
            }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TikTokMusic::OriginWifiLock").apply {
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
        mediaSession = MediaSession(this, "TikTokMusicOriginIsland").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    currentIsPlaying = true
                    MainActivity.dispatchMediaAction("play")
                    publishMediaStateAndNotification()
                }

                override fun onPause() {
                    currentIsPlaying = false
                    MainActivity.dispatchMediaAction("pause")
                    publishMediaStateAndNotification()
                }

                override fun onSkipToNext() {
                    currentIsPlaying = true
                    MainActivity.dispatchMediaAction("next")
                }

                override fun onSkipToPrevious() {
                    currentIsPlaying = true
                    MainActivity.dispatchMediaAction("prev")
                }

                override fun onSeekTo(pos: Long) {
                    currentPosMs = pos
                    MainActivity.dispatchMediaAction("seek", (pos / 1000.0).toString())
                    publishMediaStateAndNotification()
                }
            })
            @Suppress("DEPRECATION")
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                currentIsPlaying = true
                MainActivity.dispatchMediaAction("play")
                publishMediaStateAndNotification()
            }
            ACTION_PAUSE -> {
                currentIsPlaying = false
                MainActivity.dispatchMediaAction("pause")
                publishMediaStateAndNotification()
            }
            ACTION_TOGGLE -> {
                currentIsPlaying = !currentIsPlaying
                MainActivity.dispatchMediaAction("toggle")
                publishMediaStateAndNotification()
            }
            ACTION_NEXT -> {
                currentIsPlaying = true
                MainActivity.dispatchMediaAction("next")
            }
            ACTION_PREV -> {
                currentIsPlaying = true
                MainActivity.dispatchMediaAction("prev")
            }
            ACTION_UPDATE -> {
                currentTitle = intent.getStringExtra("title") ?: currentTitle
                currentArtist = intent.getStringExtra("artist") ?: currentArtist
                val newCover = intent.getStringExtra("coverUrl") ?: ""
                currentIsPlaying = intent.getBooleanExtra("isPlaying", currentIsPlaying)
                currentPosMs = intent.getLongExtra("posMs", currentPosMs)
                val dur = intent.getLongExtra("durMs", currentDurMs)
                if (dur > 0) currentDurMs = dur

                if (newCover.isNotEmpty() && newCover != cachedCoverUrl) {
                    currentCoverUrl = newCover
                    fetchCoverBitmapAsync(newCover)
                }
                publishMediaStateAndNotification()
            }
            else -> {
                publishMediaStateAndNotification()
            }
        }
        return START_STICKY
    }

    private fun fetchCoverBitmapAsync(urlStr: String) {
        imageExecutor.execute {
            try {
                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    doInput = true
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                conn.connect()
                conn.inputStream.use { input ->
                    val raw = BitmapFactory.decodeStream(input)
                    if (raw != null) {
                        val scaled = Bitmap.createScaledBitmap(raw, 384, 384, true)
                        cachedCoverUrl = urlStr
                        cachedBitmap = scaled
                        publishMediaStateAndNotification()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun publishMediaStateAndNotification() {
        val session = mediaSession ?: return
        acquireLocks(currentIsPlaying)

        val art = cachedBitmap ?: defaultArtwork

        val metadataBuilder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "TikTok Music Clean")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, if (currentDurMs > 0) currentDurMs else 180000L)

        if (art != null) {
            metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
            metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, art)
            metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, art)
        }
        session.setMetadata(metadataBuilder.build())

        val stateCode = if (currentIsPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val speed = if (currentIsPlaying) 1.0f else 0.0f
        val actions = (
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_STOP
            )

        val pbState = PlaybackState.Builder()
            .setActions(actions)
            .setState(stateCode, currentPosMs, speed, SystemClock.elapsedRealtime())
            .build()

        session.setPlaybackState(pbState)
        session.isActive = true

        val notification = buildMediaNotification(session, art)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildMediaNotification(session: MediaSession, art: Bitmap?): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevAction = Notification.Action.Builder(
            android.R.drawable.ic_media_previous,
            "Trước",
            createServicePendingIntent(ACTION_PREV, 1)
        ).build()

        val playPauseAction = Notification.Action.Builder(
            if (currentIsPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (currentIsPlaying) "Tạm dừng" else "Phát",
            createServicePendingIntent(if (currentIsPlaying) ACTION_PAUSE else ACTION_PLAY, 2)
        ).build()

        val nextAction = Notification.Action.Builder(
            android.R.drawable.ic_media_next,
            "Tiếp theo",
            createServicePendingIntent(ACTION_NEXT, 3)
        ).build()

        val mediaStyle = Notification.MediaStyle()
            .setMediaSession(session.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setSubText("TikTok Music Clean")
            .setLargeIcon(art)
            .setContentIntent(contentPendingIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(currentIsPlaying)
            .setShowWhen(false)
            .setStyle(mediaStyle)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)

        return builder.build()
    }

    private fun createServicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, OriginMediaService::class.java).apply {
            this.action = action
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Trình phát Đảo Origin (TikTok Music)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiển thị điều khiển nhạc trên Đảo Origin (OriginOS 6) và chạy ngầm khi thoát ứng dụng"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun createDefaultAlbumArt(): Bitmap {
        val size = 320
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            intArrayOf(Color.parseColor("#181824"), Color.parseColor("#fe2c55"), Color.parseColor("#25f4ee")),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)

        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#12121a")
        }
        canvas.drawCircle(size / 2f, size / 2f, size * 0.34f, circlePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 110f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("♪", size / 2f, size / 2f + 38f, textPaint)
        return bmp
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        acquireLocks(false)
        mediaSession?.isActive = false
        mediaSession?.release()
        super.onDestroy()
    }
}
