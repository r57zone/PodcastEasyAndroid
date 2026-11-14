package com.r57zone.podcasteasy

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.File

class PlayerActivity : BaseActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        private val SPEEDS = floatArrayOf(0.75f, 1f, 1.05f, 1.1f, 1.15f, 1.2f, 1.25f, 1.5f, 1.75f, 2f)
        private val SPEED_LABELS = arrayOf("0.75x", "1x", "1.05x", "1.1x", "1.15x", "1.2x", "1.25x", "1.5x", "1.75x", "2x")
        private const val VIDEO_ZOOM = 2f   // во сколько раз увеличивать видео по тапу
        private val SLEEP_MIN = intArrayOf(0, 5, 10, 15, 30, 45, 60)
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var tvBarTitle: TextView
    private lateinit var tvFmt: TextView
    private lateinit var tvInfo: TextView
    private lateinit var coverBox: View
    private lateinit var tvName: TextView
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var tvSleep: TextView
    private lateinit var seek: SeekBar
    private lateinit var seekVol: SeekBar
    private lateinit var btnPlay: ImageButton
    private lateinit var btnVideo: ImageButton
    private lateinit var btnSpeed: TextView
    private lateinit var tvVol: TextView
    private lateinit var ivCover: ImageView
    private lateinit var surface: SurfaceView

    private lateinit var bmPanel: View
    private lateinit var bmEmpty: View
    private lateinit var bmAdapter: BookmarkAdapter
    private var bmItems = ArrayList<String>()

    private var holder: SurfaceHolder? = null
    private var showVideo = false          // выставляется из настроек (по умолчанию выключено: сначала обложка)
    private var shownCover: android.graphics.Bitmap? = null
    private var infoShown = false             // формат/битрейт: только подсмотреть, не запоминается
    private var coverToggled = false          // тап по обложке: переключает «обложка файла ⇄ стандартная» (сбрасывается при смене файла)
    private var videoFill = false              // двойной тап по видео: вписать ⇄ увеличить в точке тапа
    private var panX = 0f                      // положение увеличенного видео в квадрате (левый верхний угол), px
    private var panY = 0f
    private var dragging = false
    private var shownPath: String? = null
    private var seenLoaded = false
    private var requestedPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        showVideo = Store.autoVideo
        setContentView(R.layout.activity_player)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        tvBarTitle = findViewById(R.id.tvBarTitle)
        tvFmt = findViewById(R.id.tvFmt)
        tvInfo = findViewById(R.id.tvInfo)
        coverBox = findViewById(R.id.coverBox)
        tvName = findViewById(R.id.tvName)
        tvPos = findViewById(R.id.tvPos)
        tvDur = findViewById(R.id.tvDur)
        tvSleep = findViewById(R.id.tvSleep)
        seek = findViewById(R.id.seek)
        seekVol = findViewById(R.id.seekVol)
        btnPlay = findViewById(R.id.btnPlay)
        btnVideo = findViewById(R.id.btnVideo)
        btnSpeed = findViewById(R.id.btnSpeed)
        tvVol = findViewById(R.id.tvVol)
        ivCover = findViewById(R.id.ivCover)
        surface = findViewById(R.id.surface)

        bmPanel = findViewById(R.id.bmPanel)
        bmEmpty = findViewById(R.id.bmEmpty)
        bmAdapter = BookmarkAdapter(
            onOpen = { line -> openBookmarkLine(line) },
            onEdit = { i, line -> editBookmark(i, line) },
            onRemove = { i -> removeBookmark(i) }
        )
        val bmRecycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.bmRecycler)
        bmRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        bmRecycler.adapter = bmAdapter
        bmAdapter.attach(bmRecycler)
        findViewById<View>(R.id.btnBmAdd).setOnClickListener { addBookmark() }
        findViewById<View>(R.id.btnBmList).setOnClickListener {
            if (bmPanel.visibility == View.VISIBLE) closeBookmarks() else openBookmarks()
        }

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { holder = h }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {
                holder = null
                PlaybackService.instance?.setDisplay(null)
            }
        })

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnDelete).setOnClickListener { confirmDelete() }
        btnPlay.setOnClickListener { PlaybackService.instance?.togglePlay() }
        // -30  -10  play/pause  +10  +30
        findViewById<ImageButton>(R.id.btnRew30).setOnClickListener { PlaybackService.instance?.skip(-30_000) }
        findViewById<ImageButton>(R.id.btnRew10).setOnClickListener { PlaybackService.instance?.skip(-10_000) }
        findViewById<ImageButton>(R.id.btnFwd10).setOnClickListener { PlaybackService.instance?.skip(10_000) }
        findViewById<ImageButton>(R.id.btnFwd30).setOnClickListener { PlaybackService.instance?.skip(30_000) }
        // тап по названию: «Кни…ена 4» → «Книга 12…» → «…Сцена 4» → снова; режим запоминается
        applyTitleMode()
        updateVideoIcon()
        // тап по нижней строке: показать / скрыть формат и битрейт (запоминается)
        applyInfoVisibility()
        findViewById<View>(R.id.infoStrip).setOnClickListener {
            infoShown = !infoShown
            applyInfoVisibility()
        }
        tvName.setOnClickListener {
            Store.titleMode = (Store.titleMode + 1) % 3
            applyTitleMode()
        }
        // двойной тап по видео: увеличить (точка под пальцем остаётся на месте), ещё двойной тап — вернуть; палец двигает картинку
        val gestures = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent) = true
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                toggleZoom(e.x, e.y)
                return true
            }
            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                // у видео, которое сейчас показано, одиночный тап ничего не делает; у обложки — переключает обложку
                val svc = PlaybackService.instance
                if (svc != null && svc.hasVideo && showVideo) return false
                coverToggled = !coverToggled
                if (useFileCover()) svc?.loadCoverAsync()     // с диска читается один раз, дальше — из памяти
                applyCover()
                return true
            }
            override fun onScroll(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, dx: Float, dy: Float): Boolean {
                if (!videoFill) return false
                panX -= dx; panY -= dy          // видео едет за пальцем (границы проверяет fitVideo)
                return true
            }
        })
        coverBox.setOnTouchListener { _, e -> gestures.onTouchEvent(e); true }

        btnVideo.setOnClickListener {
            if (bmPanel.visibility == View.VISIBLE) {
                // список открыт: кнопка видео закрывает его и показывает видео
                closeBookmarks()
                showVideo = true
            } else showVideo = !showVideo
            updateVideoIcon()
        }

        // «осталось» ⇄ «всего»
        tvDur.setOnClickListener {
            Store.showRemaining = !Store.showRemaining
            refresh()
        }

        btnSpeed.setOnClickListener { v ->
            showList(v, SPEED_LABELS.toList()) { PlaybackService.instance?.setSpeed(SPEEDS[it]) }
        }
        findViewById<View>(R.id.btnSleep).setOnClickListener { v ->
            val labels = SLEEP_MIN.map { min ->
                when (min) {
                    0 -> getString(R.string.sleep_off)
                    60 -> getString(R.string.sleep_hour)
                    else -> getString(R.string.sleep_min, min)
                }
            }
            showList(v, labels) { PlaybackService.instance?.setSleepTimer(SLEEP_MIN[it]) }
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) tvPos.text = Fmt.time(p)
            }
            override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                dragging = false
                PlaybackService.instance?.seekTo(s.progress)
            }
        })

        // громкость плеера: ползунок обычный 1…100 %, кнопки шагают по уровням (Store.VOLUME_LEVELS)
        seekVol.max = 100
        seekVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) setVolume(p)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        findViewById<ImageButton>(R.id.btnVolDown).setOnClickListener { setVolume(Store.volumeDown(Store.volumePercent)) }
        findViewById<ImageButton>(R.id.btnVolUp).setOnClickListener { setVolume(Store.volumeUp(Store.volumePercent)) }
        showVolume()

        handleIntent(intent, savedInstanceState == null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, true)
    }

    private fun handleIntent(i: Intent?, fresh: Boolean) {
        val path = i?.getStringExtra(EXTRA_PATH)
        if (path != null) {
            requestedPath = path
            val svc = PlaybackService.instance
            if (svc?.currentPath != path) PlaybackService.playFile(this, path, Store.autoplay)
            else if (fresh && svc?.isPlaying == false) svc?.play()      // файл из списка уже открыт, но на паузе — играем
        } else if (PlaybackService.instance?.loaded != true) {
            finish()
        }
    }

    /** Сейчас нужна обложка файла? Настройка задаёт умолчание, тап по обложке его переворачивает */
    private fun useFileCover() = Store.coverFile != coverToggled

    /** Обложка файла или стандартная — по тапу переключаются */
    private fun applyCover() {
        val c = PlaybackService.instance?.cover
        shownCover = c
        if (c != null && useFileCover()) ivCover.setImageBitmap(c) else ivCover.setImageResource(R.drawable.cover_placeholder)
    }

    private fun applyInfoVisibility() {
        val v = if (infoShown) View.VISIBLE else View.INVISIBLE
        tvFmt.visibility = v
        tvInfo.visibility = v
    }

    private fun updateVideoIcon() {
        btnVideo.setImageResource(if (showVideo) R.drawable.ic_video_on else R.drawable.ic_video_off)
    }

    private fun applyTitleMode() {
        tvName.ellipsize = when (Store.titleMode) {
            1 -> android.text.TextUtils.TruncateAt.END
            2 -> android.text.TextUtils.TruncateAt.START
            else -> android.text.TextUtils.TruncateAt.MIDDLE
        }
    }

    private fun setVolume(pct: Int) {
        Store.volumePercent = pct
        PlaybackService.instance?.applyVolume()
        showVolume()
    }

    private fun showVolume() {
        val v = Store.volumePercent
        seekVol.progress = v
        tvVol.text = "$v%"
    }

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 300)
        }
    }

    override fun onResume() { super.onResume(); ui.post(poll) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(poll) }

    private fun refresh() {
        val svc = PlaybackService.instance
        if (svc == null || !svc.loaded) {
            if (seenLoaded) finish()
            return
        }
        seenLoaded = true

        // новый файл — обновляем обложку, папку и название
        if (shownPath != svc.currentPath) {
            shownPath = svc.currentPath
            tvName.text = svc.title
            tvBarTitle.text = File(svc.currentPath ?: "").parentFile?.name ?: ""
            tvFmt.text = svc.formatLabel
            tvInfo.text = svc.infoLabel
            showVideo = Store.autoVideo
            videoFill = false
            closeBookmarks()
            updateVideoIcon()
            coverToggled = false
            applyCover()
        }

        // видео: показывать/скрывать
        val video = svc.hasVideo && showVideo
        btnVideo.visibility = if (svc.hasVideo) View.VISIBLE else View.GONE
        findViewById<View>(R.id.spVideo).visibility = btnVideo.visibility
        // Поверхность нужна декодеру видео всегда — иначе на части устройств пропадает и звук.
        // Когда видео «выкл», она остаётся живой, но сжата до 1×1 px под обложкой.
        surface.visibility = if (svc.hasVideo) View.VISIBLE else View.GONE
        if (svc.hasVideo) fitVideo(svc.videoWidth, svc.videoHeight, video)
        // обложка: по настройке сразу, иначе — только после тапа по пустому квадрату (читается с диска по требованию)
        if (useFileCover() && !svc.coverLoaded) svc.loadCoverAsync()
        if (svc.cover !== shownCover) applyCover()
        ivCover.visibility = if (video) View.INVISIBLE else View.VISIBLE
        // Видео скрыто и играет, а поверхность пропала (экран гасили): заново её не подключаем —
        // подключение на ходу перезапускает декодер видео и звук откатывается назад.
        // Подключим, когда видео включат или поставят на паузу.
        if (svc.display !== holder && (holder == null || video || !svc.isPlaying)) svc.setDisplay(holder)

        // прогресс
        val dur = svc.duration
        val pos = svc.position
        if (!dragging) {
            seek.max = maxOf(dur, 1)
            seek.progress = pos
            tvPos.text = Fmt.time(pos)
        }
        tvDur.text = if (Store.showRemaining) "-" + Fmt.time(dur - pos) else Fmt.time(dur)

        btnPlay.setImageResource(if (svc.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        btnSpeed.text = SPEED_LABELS[SPEEDS.indexOfFirst { Math.abs(it - svc.speed) < 0.001f }.coerceAtLeast(0)]

        // таймер сна: иконка всегда, обратный отсчёт рядом — только когда включён
        val left = svc.sleepEndAt - SystemClock.elapsedRealtime()
        if (svc.sleepEndAt > 0 && left > 0) {
            tvSleep.visibility = View.VISIBLE
            tvSleep.text = Fmt.time(left.toInt())
        } else tvSleep.visibility = View.GONE

    }

    /** Размеры видео в области bw×bh: [0] ширина, [1] высота, [2] масштаб «вписать» */
    private fun videoGeometry(vw: Int, vh: Int, bw: Int, bh: Int, zoomed: Boolean): FloatArray {
        val fit = minOf(bw.toFloat() / vw, bh.toFloat() / vh)
        val fill = maxOf(bw.toFloat() / vw, bh.toFloat() / vh)
        val scale = if (zoomed) maxOf(fit * VIDEO_ZOOM, fill) else fit
        return floatArrayOf(vw * scale, vh * scale, fit)
    }

    /** Двойной тап: увеличить так, чтобы точка под пальцем осталась на месте, или вернуть как было */
    private fun toggleZoom(x: Float, y: Float) {
        val svc = PlaybackService.instance ?: return
        val vw = svc.videoWidth
        val vh = svc.videoHeight
        val bw = coverBox.width
        val bh = coverBox.height
        if (!svc.hasVideo || !showVideo || vw <= 0 || vh <= 0 || bw <= 0 || bh <= 0) return
        if (videoFill) { videoFill = false; return }
        val g = videoGeometry(vw, vh, bw, bh, true)
        val fitW = vw * g[2]
        val fitH = vh * g[2]
        val fx = ((x - (bw - fitW) / 2) / fitW).coerceIn(0f, 1f)   // какая доля видео была под пальцем
        val fy = ((y - (bh - fitH) / 2) / fitH).coerceIn(0f, 1f)
        panX = x - fx * g[0]
        panY = y - fy * g[1]
        videoFill = true
    }

    /** Видео в области: вписано по центру; после двойного тапа увеличено в VIDEO_ZOOM раза и двигается пальцем. */
    private fun fitVideo(vw: Int, vh: Int, visible: Boolean) {
        val box = surface.parent as View
        val bw = box.width
        val bh = box.height
        if (vw <= 0 || vh <= 0 || bw <= 0 || bh <= 0) return
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        if (!visible) {
            // скрытое видео: поверхность живёт, но 1×1 px под обложкой
            if (lp.width != 1 || lp.height != 1) {
                lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                lp.width = 1; lp.height = 1; lp.leftMargin = 0; lp.topMargin = 0
                surface.layoutParams = lp
            }
            return
        }
        val g = videoGeometry(vw, vh, bw, bh, videoFill)
        val w = g[0]
        val h = g[1]
        var left = (bw - w) / 2
        var top = (bh - h) / 2
        if (videoFill) {
            // не даём увести картинку за границы области
            if (w > bw) { panX = panX.coerceIn(bw - w, 0f); left = panX }
            if (h > bh) { panY = panY.coerceIn(bh - h, 0f); top = panY }
        }
        if (lp.width != w.toInt() || lp.height != h.toInt() || lp.leftMargin != left.toInt() || lp.topMargin != top.toInt()) {
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            lp.width = w.toInt(); lp.height = h.toInt()
            lp.leftMargin = left.toInt(); lp.topMargin = top.toInt()
            surface.layoutParams = lp
        }
    }

    /** Выпадающий список с прокруткой: показывает не больше ~5 строк, остальное — скроллом */
    private fun showList(anchor: View, labels: List<String>, onPick: (Int) -> Unit) {
        val d = resources.displayMetrics.density
        val lp = androidx.appcompat.widget.ListPopupWindow(this)
        lp.anchorView = anchor
        lp.setAdapter(android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        lp.width = (150 * d).toInt()
        lp.height = minOf((labels.size * 48 * d).toInt(), (5.5f * 48 * d).toInt())
        lp.isModal = true
        lp.setOnItemClickListener { _, _, pos, _ -> lp.dismiss(); onPick(pos) }
        lp.show()
    }

    // ---------- закладки ----------

    private fun currentMediaPath(): String? = PlaybackService.instance?.currentPath ?: requestedPath

    private fun openBookmarks() {
        val path = currentMediaPath() ?: return
        bmItems = ArrayList(Bookmarks.load(path))       // каждый раз читаем файл заново — вдруг его поправили руками
        showBookmarkItems()
        bmPanel.visibility = View.VISIBLE
    }

    private fun closeBookmarks() { bmPanel.visibility = View.GONE }

    private fun showBookmarkItems() {
        bmAdapter.submit(bmItems)
        bmEmpty.visibility = if (bmItems.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun saveBookmarks() {
        val path = currentMediaPath() ?: return
        if (!Bookmarks.save(path, bmItems)) Toast.makeText(this, R.string.bookmark_save_failed, Toast.LENGTH_LONG).show()
        if (bmPanel.visibility == View.VISIBLE) showBookmarkItems()
    }

    private fun inputDialog(title: Int, initial: String, hint: String?, onOk: (String) -> Unit) {
        val d = resources.displayMetrics.density
        val et = EditText(this)
        et.setSingleLine()
        et.setText(initial)
        et.setSelection(et.text.length)
        et.hint = hint
        val box = FrameLayout(this)
        box.setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        box.addView(et, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        val dlg = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ -> onOk(et.text.toString().trim()) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        et.requestFocus()
    }

    /** Тап по строке: если она начинается со времени и оно не больше длины трека — перейти и играть; иначе ничего */
    private fun openBookmarkLine(line: String) {
        val ms = Bookmarks.timeOf(line) ?: return
        val svc = PlaybackService.instance ?: return
        if (ms > svc.duration) return
        svc.seekTo(ms)
        if (!svc.isPlaying) svc.play()
        if (Store.hideBookmarksOnTap) closeBookmarks()
    }

    /** «Закладка»: время берём с текущего места, название спрашиваем (можно оставить пустым) */
    private fun addBookmark() {
        val svc = PlaybackService.instance ?: return
        val path = svc.currentPath ?: return
        val ms = svc.position
        inputDialog(R.string.bookmark_name, "", null) { name ->
            bmItems = ArrayList(Bookmarks.load(path))
            bmItems.add(Bookmarks.insertIndex(bmItems, ms), Bookmarks.newLine(ms, name))
            saveBookmarks()
            Toast.makeText(this, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
        }
    }

    /** Двойной тап: правим всю строку как текст, без проверок */
    private fun editBookmark(index: Int, line: String) {
        inputDialog(R.string.bookmark_edit, line.trim(), null) { text ->
            if (index in bmItems.indices) {
                bmItems[index] = text
                saveBookmarks()
            }
        }
    }

    private fun removeBookmark(index: Int) {
        if (index !in bmItems.indices) return
        bmItems.removeAt(index)
        saveBookmarks()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (bmPanel.visibility == View.VISIBLE) closeBookmarks() else super.onBackPressed()
    }

    private fun confirmDelete() {
        val path = currentMediaPath() ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage(File(path).name)
            .setPositiveButton(R.string.yes) { _, _ ->
                if (Bookmarks.exists(path) && Store.alwaysDeleteBookmarks) {
                    doDelete(path, true)
                } else if (Bookmarks.exists(path)) {
                    AlertDialog.Builder(this)
                        .setMessage(R.string.delete_bookmarks)
                        .setPositiveButton(R.string.yes) { _, _ -> doDelete(path, true) }
                        .setNegativeButton(R.string.no) { _, _ -> doDelete(path, false) }
                        .show()
                } else doDelete(path, false)
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun doDelete(path: String, withBookmarks: Boolean) {
        val svc = PlaybackService.instance
        if (svc?.currentPath == path) svc.stopAll()
        if (File(path).delete()) {
            Store.forget(path)
            if (withBookmarks) Bookmarks.delete(path)
            finish()
        } else {
            Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_LONG).show()
        }
    }
}
