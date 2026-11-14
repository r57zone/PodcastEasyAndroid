package com.r57zone.podcasteasy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Фоновое воспроизведение: MediaPlayer + MediaSession + уведомление (кнопки на шторке и экране блокировки).
 * Доступ из Activity — через PlaybackService.instance (тот же процесс).
 */
class PlaybackService : Service() {

    companion object {
        const val ACTION_PLAY_FILE = "pe.PLAY_FILE"
        const val ACTION_TOGGLE = "pe.TOGGLE"
        const val ACTION_REW = "pe.REW"
        const val ACTION_FWD = "pe.FWD"
        const val EXTRA_PATH = "path"
        const val EXTRA_AUTOPLAY = "autoplay"
        const val SKIP_MS = 10000
        const val CA_REW = "pe_rew10"
        const val CA_FWD = "pe_fwd10"
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 1
        private const val IDLE_STOP_MS = 10 * 60 * 1000L

        @Volatile var instance: PlaybackService? = null

        fun playFile(ctx: Context, path: String, autoplay: Boolean = true) {
            val i = Intent(ctx, PlaybackService::class.java).setAction(ACTION_PLAY_FILE)
                .putExtra(EXTRA_PATH, path).putExtra(EXTRA_AUTOPLAY, autoplay)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var session: MediaSession? = null
    private var focusReq: AudioFocusRequest? = null
    private var tickCount = 0
    private var ducked = false
    private var pendingPlay = false        // ждём поверхность для видео, прежде чем стартовать
    private var surfaceWaitDone = false
    private var pausedSince = 0L

    var currentPath: String? = null; private set
    var title: String = ""; private set
    var cover: Bitmap? = null; private set
    /** Обложка уже прочитана (или читать её не нужно было, т.к. отключена в настройках — тогда false до первого запроса) */
    var coverLoaded = false; private set
    private var coverLoading = false
    var hasVideo = false; private set
    var display: SurfaceHolder? = null; private set
    var formatLabel = ""; private set     // "MP3"
    var infoLabel = ""; private set       // "320 kbit/s" или "1920×1080"
    val speed: Float get() = Store.speed
    var sleepEndAt = 0L; private set

    val loaded get() = player != null
    val videoWidth get() = try { player?.videoWidth ?: 0 } catch (e: Exception) { 0 }
    val videoHeight get() = try { player?.videoHeight ?: 0 } catch (e: Exception) { 0 }
    val isPlaying get() = try { player?.isPlaying == true } catch (e: Exception) { false }
    val position get() = try { player?.currentPosition ?: 0 } catch (e: Exception) { 0 }
    val duration get() = try { player?.duration ?: 0 } catch (e: Exception) { 0 }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { pause() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Строки (уведомление, тосты) — на выбранном в настройках языке */
    private fun str(id: Int, vararg a: Any): String = Lang.wrap(applicationContext).getString(id, *a)

    override fun onCreate() {
        super.onCreate()
        instance = this
        Store.init(this)
        val ch = NotificationChannel(CHANNEL, str(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)

        session = MediaSession(this, "PodcastEasy").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { play() }
                override fun onPause() { pause() }
                override fun onStop() { stopAll() }
                override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
                override fun onSkipToNext() { skip(SKIP_MS) }
                override fun onSkipToPrevious() { skip(-SKIP_MS) }
                override fun onFastForward() { skip(SKIP_MS) }
                override fun onRewind() { skip(-SKIP_MS) }
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    when (action) {
                        CA_REW -> skip(-SKIP_MS)
                        CA_FWD -> skip(SKIP_MS)
                    }
                }
            })
            isActive = true
        }
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Нужно вызвать startForeground сразу, иначе Android убьёт сервис
        goForeground()
        when (intent?.action) {
            ACTION_PLAY_FILE -> intent.getStringExtra(EXTRA_PATH)?.let { open(it, intent.getBooleanExtra(EXTRA_AUTOPLAY, true)) }
            ACTION_TOGGLE -> if (isPlaying) pause() else play()
            ACTION_REW -> skip(-SKIP_MS)
            ACTION_FWD -> skip(SKIP_MS)
        }
        if (player == null) stopAll()
        return START_NOT_STICKY
    }

    // ---------- управление ----------

    fun open(path: String, autoplay: Boolean) {
        if (path == currentPath && player != null) {
            if (autoplay && !isPlaying) play()
            return
        }
        saveProgress()
        releasePlayer()
        val f = File(path)
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build()
            )
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(path)
            mp.prepare()
        } catch (e: Exception) {
            try { mp.release() } catch (_: Exception) {}
            Toast.makeText(this, str(R.string.open_failed, f.name), Toast.LENGTH_LONG).show()
            return
        }
        mp.setOnCompletionListener {
            Store.markDone(path)
            val next = if (Store.playNext) Store.nextInFolder(path) else null
            if (next != null) {
                handler.post { open(next, true) }   // не освобождать плеер внутри его же callback'а
            } else {
                pausedSince = SystemClock.elapsedRealtime()
                updateAll()
            }
        }
        mp.setOnErrorListener { _, what, extra ->
            Toast.makeText(this, "Player error: $what / $extra", Toast.LENGTH_LONG).show()
            true
        }
        player = mp
        currentPath = path
        pendingPlay = false
        surfaceWaitDone = false
        applyVolume()
        title = f.nameWithoutExtension
        hasVideo = Store.isVideoExt(f) && mp.videoWidth > 0
        coverLoading = false
        coverLoaded = Store.coverFile            // выключено в настройках — обложку с диска не читаем, пока не попросят
        cover = loadMedia(path, f, coverLoaded)
        display?.let { try { mp.setDisplay(it) } catch (_: Exception) {} }

        var start = Store.position(path)
        if (mp.duration > 0 && start >= mp.duration - 3000) start = 0
        if (start > 0) mp.seekTo(start.toLong(), MediaPlayer.SEEK_CLOSEST)

        updateMetadata()
        if (autoplay) play() else updateAll()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000)
    }

    fun play() {
        val mp = player ?: return
        // Видео без поверхности на части устройств не играет вовсе (даже звук): ждём её до 1.2 с
        if (hasVideo && display == null && !surfaceWaitDone) {
            if (!pendingPlay) {
                pendingPlay = true
                handler.postDelayed({
                    if (pendingPlay) { pendingPlay = false; surfaceWaitDone = true; play() }
                }, 1200)
            }
            return
        }
        if (!requestFocus()) return
        try {
            if (mp.duration > 0 && mp.currentPosition >= mp.duration - 500) mp.seekTo(0)
            mp.start()
            applySpeed()
        } catch (_: Exception) {}
        updateAll()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000)
    }

    fun pause() {
        val mp = player ?: return
        try { if (mp.isPlaying) mp.pause() } catch (_: Exception) {}
        pausedSince = SystemClock.elapsedRealtime()
        saveProgress()
        updateAll()
    }

    fun togglePlay() { if (isPlaying) pause() else play() }

    fun seekTo(ms: Int) {
        val mp = player ?: return
        try {
            mp.seekTo(ms.coerceIn(0, maxOf(0, mp.duration)).toLong(), MediaPlayer.SEEK_CLOSEST)
        } catch (_: Exception) {}
        saveProgress()
        updateState()
    }

    fun skip(deltaMs: Int) { seekTo(position + deltaMs) }

    /** Громкость плеера = выбранный уровень (%) поверх системной громкости медиа */
    fun applyVolume() {
        val g = Store.volumePercent / 100f
        val v = if (ducked) g * 0.3f else g
        try { player?.setVolume(v, v) } catch (_: Exception) {}
    }

    fun setSpeed(s: Float) {
        Store.speed = s
        if (isPlaying) applySpeed()
        updateState()
    }

    private fun applySpeed() {
        val mp = player ?: return
        try { mp.playbackParams = mp.playbackParams.setSpeed(speed) } catch (_: Exception) {}
    }

    /** minutes = 0 — выключить таймер */
    fun setSleepTimer(minutes: Int) {
        sleepEndAt = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else 0L
    }

    /**
     * Файл (или папку, в которой он лежит) переименовали на диске. Воспроизведение продолжается,
     * но путь надо обновить — иначе прогресс сохранялся бы под старым именем.
     */
    fun pathChanged(old: String, new: String) {
        val cur = currentPath ?: return
        val o = old.trimEnd('/')
        val n = new.trimEnd('/')
        currentPath = when {
            cur == o -> n
            cur.startsWith("$o/") -> n + cur.substring(o.length)
            else -> return
        }
        title = File(currentPath!!).nameWithoutExtension
        updateMetadata()
        updateAll()
    }

    fun setDisplay(h: SurfaceHolder?) {
        display = h
        try { player?.setDisplay(h) } catch (_: Exception) {}
        if (h != null) {
            surfaceWaitDone = true
            if (pendingPlay) { pendingPlay = false; handler.post { play() } }
        }
        // Новая поверхность остаётся чёрной, пока не придёт новый кадр (на паузе — никогда):
        // чуть сдвигаем позицию влево, чтобы декодер отрисовал кадр заново.
        // Во время воспроизведения кадры придут сами, а seek дал бы слышимый откат звука назад.
        if (h != null && hasVideo && !isPlaying) {
            try { player?.seekTo(maxOf(0, position - 50).toLong(), MediaPlayer.SEEK_CLOSEST) } catch (_: Exception) {}
        }
    }

    /** Остановить, закрыть уведомление (например, после удаления файла) */
    fun stopAll() {
        saveProgress()
        releasePlayer()
        abandonFocus()
        handler.removeCallbacks(tick)
        stopForeground(true)
        stopSelf()
    }

    // ---------- внутреннее ----------

    private val tick = object : Runnable {
        override fun run() {
            if (player == null) return
            tickCount++
            if (sleepEndAt > 0 && SystemClock.elapsedRealtime() >= sleepEndAt) {
                sleepEndAt = 0
                pause()
            }
            if (isPlaying) {
                if (tickCount % 5 == 0) saveProgress()
            } else if (pausedSince > 0 && SystemClock.elapsedRealtime() - pausedSince > IDLE_STOP_MS) {
                stopAll()
                return
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun saveProgress() {
        val p = currentPath ?: return
        val mp = player ?: return
        try {
            val pos = mp.currentPosition
            val dur = mp.duration
            if (dur > 0 && pos >= dur - 3000) Store.markDone(p) else Store.savePosition(p, pos)
        } catch (_: Exception) {}
    }

    private fun releasePlayer() {
        try { player?.release() } catch (_: Exception) {}
        player = null
        currentPath = null
        cover = null
        coverLoaded = false
        coverLoading = false
        hasVideo = false
        sleepEndAt = 0
    }

    /** Обложка (встроенная или кадр видео) + строки «формат» и «битрейт / разрешение» */
    private fun loadMedia(path: String, f: File, withCover: Boolean): Bitmap? {
        formatLabel = f.extension.uppercase()
        infoLabel = ""
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            if (hasVideo) {
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                if (w != null && h != null) infoLabel = "${w}×${h}"
            } else {
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.let {
                    if (it > 0) infoLabel = str(R.string.bitrate_fmt, (it + 500) / 1000)
                }
            }
            if (withCover) readCover(r, f) else null
        } catch (_: Exception) { null } finally { try { r.release() } catch (_: Exception) {} }
    }

    private fun readCover(r: MediaMetadataRetriever, f: File): Bitmap? {
        val pic = r.embeddedPicture
        return if (pic != null) decodeScaled(pic)
        else if (Store.isVideoExt(f)) r.getFrameAtTime(1_000_000) else null
    }

    /** Большие обложки (3000×3000 и т.п.) уменьшаем до ~1024 px: меньше памяти и быстрее рисуется */
    private fun decodeScaled(pic: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(pic, 0, pic.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= 1024 && o.outHeight / (sample * 2) >= 1024) sample *= 2
        return BitmapFactory.decodeByteArray(pic, 0, pic.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * Прочитать обложку по требованию (тап по пустому квадрату или включённая в настройках галочка).
     * Читается в фоне один раз и хранится в памяти, пока не сменится файл.
     */
    fun loadCoverAsync() {
        val path = currentPath ?: return
        if (coverLoaded || coverLoading) return
        coverLoading = true
        Thread {
            var bmp: Bitmap? = null
            val r = MediaMetadataRetriever()
            try { r.setDataSource(path); bmp = readCover(r, File(path)) } catch (_: Exception) {}
            try { r.release() } catch (_: Exception) {}
            handler.post {
                if (currentPath == path) {
                    cover = bmp
                    coverLoaded = true
                    coverLoading = false
                    updateMetadata()
                    updateAll()
                }
            }
        }.start()
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { ducked = true; applyVolume() }
            AudioManager.AUDIOFOCUS_GAIN -> { ducked = false; applyVolume() }
        }
    }

    private fun requestFocus(): Boolean {
        val am = getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setOnAudioFocusChangeListener(focusListener).build()
        focusReq = req
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusReq?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focusReq = null
    }

    private fun updateAll() {
        updateState()
        val n = buildNotification()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    private var placeholder: Bitmap? = null
    private var placeholderNight = false

    /** Картинка для экрана блокировки и уведомления: обложка файла, а если её нет — стандартная заглушка (по теме) */
    private fun artwork(): Bitmap? {
        cover?.let { return it }
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (placeholder == null || placeholderNight != night) {
            placeholder = try { BitmapFactory.decodeResource(resources, R.drawable.cover_placeholder) } catch (_: Exception) { null }
            placeholderNight = night
        }
        return placeholder
    }

    private fun updateMetadata() {
        val b = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, File(currentPath ?: "").parentFile?.name ?: "")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration.toLong())
        artwork()?.let { b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        session?.setMetadata(b.build())
    }

    private fun updateState() {
        val st = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
        session?.setPlaybackState(
            PlaybackState.Builder().setActions(actions)
                .addCustomAction(PlaybackState.CustomAction.Builder(CA_REW, str(R.string.act_rew), R.drawable.ic_rew10).build())
                .addCustomAction(PlaybackState.CustomAction.Builder(CA_FWD, str(R.string.act_fwd), R.drawable.ic_fwd10).build())
                .setState(st, position.toLong(), if (isPlaying) speed else 0f, SystemClock.elapsedRealtime()).build()
        )
    }

    private fun pi(code: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun act(icon: Int, text: String, intent: PendingIntent) =
        Notification.Action.Builder(Icon.createWithResource(this, icon), text, intent).build()

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(if (title.isEmpty()) str(R.string.app_title) else title)
            .setContentText(File(currentPath ?: "").parentFile?.name ?: "")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
        artwork()?.let { b.setLargeIcon(it) }
        b.addAction(act(R.drawable.ic_rew10, str(R.string.act_rew), pi(1, ACTION_REW)))
        b.addAction(
            if (isPlaying) act(R.drawable.ic_pause, str(R.string.act_pause), pi(2, ACTION_TOGGLE))
            else act(R.drawable.ic_play, str(R.string.act_play), pi(2, ACTION_TOGGLE))
        )
        b.addAction(act(R.drawable.ic_fwd10, str(R.string.act_fwd), pi(3, ACTION_FWD)))
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        session?.let { style.setMediaSession(it.sessionToken) }
        b.setStyle(style)
        return b.build()
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, n)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(noisy) } catch (_: Exception) {}
        saveProgress()
        releasePlayer()
        abandonFocus()
        session?.release()
        session = null
        instance = null
        super.onDestroy()
    }
}
