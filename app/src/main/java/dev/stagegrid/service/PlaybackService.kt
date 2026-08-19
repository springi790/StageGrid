package dev.stagegrid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import dev.stagegrid.R
import dev.stagegrid.StageGridApplication
import dev.stagegrid.audio.EngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSession: MediaSession
    private lateinit var app: StageGridApplication
    private var observer: Job? = null

    override fun onCreate() {
        super.onCreate()
        app = application as StageGridApplication
        createChannel()
        mediaSession = MediaSession(this, "StageGridPlayback").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = app.audio.play()
                override fun onPause() = app.audio.pause()
                override fun onStop() = app.audio.stop()
                override fun onSeekTo(pos: Long) = app.audio.seekTo(pos)
                override fun onSkipToNext() {
                    app.audio.state.value.nextSection?.let(app.audio::queueOrJumpSection)
                }
                override fun onSkipToPrevious() {
                    val state = app.audio.state.value
                    val sections = state.sections
                    val currentIndex = sections.indexOfFirst { it.id == state.currentSection?.id }
                    sections.getOrNull((currentIndex - 1).coerceAtLeast(0))?.let(app.audio::queueOrJumpSection)
                }
            })
            isActive = true
        }
        observer = scope.launch {
            app.audio.state.collectLatest { state ->
                val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                val playback = when (state.engineState) {
                    EngineState.PLAYING -> PlaybackState.STATE_PLAYING
                    EngineState.PAUSED -> PlaybackState.STATE_PAUSED
                    EngineState.READY -> PlaybackState.STATE_STOPPED
                    EngineState.ERROR -> PlaybackState.STATE_ERROR
                    else -> PlaybackState.STATE_BUFFERING
                }
                mediaSession.setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(actions)
                        .setState(playback, state.positionMs, if (state.isPlaying) 1f else 0f)
                        .build(),
                )
                mediaSession.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, state.song?.title ?: getString(R.string.app_name))
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, state.song?.artist.orEmpty())
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs)
                        .build(),
                )
                if (state.song != null) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> app.audio.play()
            ACTION_PAUSE -> app.audio.pause()
            ACTION_STOP -> app.audio.stop()
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val state = app.audio.state.value
        val contentIntent = app.audio.mediaContentIntent()
        val playPauseAction = if (state.isPlaying) ACTION_PAUSE else ACTION_PLAY
        val playPauseLabel = if (state.isPlaying) getString(R.string.pause) else getString(R.string.play)
        val playPauseIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, PlaybackService::class.java).setAction(playPauseAction),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.song?.title ?: getString(R.string.app_name))
            .setContentText(state.song?.artist?.takeIf { it.isNotBlank() } ?: getString(R.string.local_multitrack_player))
            .setContentIntent(contentIntent)
            .setOngoing(state.isPlaying)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(R.drawable.ic_notification, playPauseLabel, playPauseIntent).build())
            .addAction(Notification.Action.Builder(R.drawable.ic_notification, getString(R.string.stop), stopIntent).build())
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1))
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.playback_channel_description)
                setSound(null, null)
            },
        )
    }

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_ENSURE_FOREGROUND = "dev.stagegrid.action.ENSURE_FOREGROUND"
        const val ACTION_PLAY = "dev.stagegrid.action.PLAY"
        const val ACTION_PAUSE = "dev.stagegrid.action.PAUSE"
        const val ACTION_STOP = "dev.stagegrid.action.STOP"
        private const val CHANNEL_ID = "stagegrid_playback"
        private const val NOTIFICATION_ID = 1001
    }
}
