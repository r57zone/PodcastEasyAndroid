package com.r57zone.podcasteasy

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.util.concurrent.Executors

class MainActivity : BaseActivity() {

    private lateinit var swipe: PullRefreshLayout
    private lateinit var tvTitle: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var btnLeft: ImageButton
    private var reloadGen = 0
    private lateinit var btnNow: ImageButton
    private lateinit var btnMore: ImageButton
    private lateinit var btnSelDel: ImageButton
    private lateinit var adapter: FileAdapter

    private var currentDir: File? = null
    private var askedFiles = false
    private var askedNotif = false
    private var hasAccess = true
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            val svc = PlaybackService.instance
            if (adapter.selectedCount() == 0) btnNow.visibility = if (svc?.loaded == true) View.VISIBLE else View.INVISIBLE
            adapter.setPlaying(svc?.takeIf { it.isPlaying }?.currentPath)
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setContentView(R.layout.activity_main)

        swipe = findViewById(R.id.swipe)
        tvTitle = findViewById(R.id.tvTitle)
        tvCount = findViewById(R.id.tvCount)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnLeft = findViewById(R.id.btnLeft)
        btnNow = findViewById(R.id.btnNow)
        btnMore = findViewById(R.id.btnMore)
        btnSelDel = findViewById(R.id.btnSelDel)

        adapter = FileAdapter(
            onClick = { f ->
                if (f.isDirectory) openDir(f)
                else startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_PATH, f.absolutePath))
            },
            onDelete = { f -> deleteFile(f) },
            onSelection = { applyBar() }
        )
        val rv = findViewById<RecyclerView>(R.id.list)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        // прокрутка вверх/вниз закрывает открытую для удаления строку
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(r: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) adapter.closeOpen()
            }
        })

        // нажатие в пустую область списка (ниже строк) сбрасывает выбор
        rv.setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP && adapter.selectedCount() > 0 &&
                rv.findChildViewUnder(e.x, e.y) == null) adapter.clearSelection()
            false
        }

        // Pull-to-refresh: заново читаем текущую папку (и статусы файлов)
        swipe.setColorSchemeResources(R.color.accent)
        swipe.setOnRefreshListener { reload() }

        // слева сверху: на корневой папке — настройки, внутри папок — «назад»
        btnLeft.setOnClickListener {
            if (adapter.selectedCount() > 0) adapter.clearSelection()
            else if (!goUp()) startActivity(Intent(this, SettingsActivity::class.java))
        }
        // выбран один элемент: меню «⋮» (переименовать, удалить); выбрано несколько: просто «удалить»
        btnMore.setOnClickListener {
            val f = adapter.selectedFiles().firstOrNull() ?: return@setOnClickListener
            val m = PopupMenu(this, btnMore)
            m.menu.add(0, 1, 0, R.string.rename)
            m.menu.add(0, 2, 1, R.string.delete)
            m.setOnMenuItemClickListener {
                if (it.itemId == 1) renameDialog(f) else confirmDelete(listOf(f))
                true
            }
            m.show()
        }
        btnSelDel.setOnClickListener { confirmDelete(adapter.selectedFiles()) }
        btnNow.setOnClickListener { startActivity(Intent(this, PlayerActivity::class.java)) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (adapter.selectedCount() > 0) { adapter.clearSelection(); return }
                if (!goUp()) { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        savedInstanceState?.getString("dir")?.let { currentDir = File(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentDir?.let { outState.putString("dir", it.absolutePath) }
    }

    override fun onResume() {
        super.onResume()
        askPermissions()
        // если корневая папка в настройках сменилась — перейти в неё
        val root = File(Store.rootFolder)
        val cd = currentDir
        if (cd == null || !cd.absolutePath.startsWith(root.absolutePath)) currentDir = root
        reload()
        ui.removeCallbacks(poll)
        ui.post(poll)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(poll)
    }

    /** Разрешения запрашиваем по одному и по разу за запуск (иначе диалоги и экран настроек сменяют друг друга). */
    private fun askPermissions() {
        if (!Access.hasFiles(this)) {
            if (!askedFiles) { askedFiles = true; Access.requestFiles(this) }
        } else if (!askedNotif) {
            askedNotif = true
            Access.requestNotifications(this)
        }
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        reload()
    }

    private fun openDir(d: File) { currentDir = d; reload() }

    private fun goUp(): Boolean {
        val cd = currentDir ?: return false
        val root = File(Store.rootFolder)
        if (cd.absolutePath == root.absolutePath) return false
        currentDir = cd.parentFile ?: root
        reload()
        return true
    }

    /** Удаление файла свайпом: без подтверждения; если рядом есть закладки — спрашиваем, удалять ли их. */
    private fun deleteFile(f: File) {
        if (f.isDirectory) { confirmDeleteFolder(f); return }
        if (f.isFile && Bookmarks.exists(f.absolutePath) && Store.alwaysDeleteBookmarks) {
            doDeleteFile(f, true)
        } else if (f.isFile && Bookmarks.exists(f.absolutePath)) {
            AlertDialog.Builder(this)
                .setMessage(R.string.delete_bookmarks)
                .setPositiveButton(R.string.yes) { _, _ -> doDeleteFile(f, true) }
                .setNegativeButton(R.string.no) { _, _ -> doDeleteFile(f, false) }
                .setOnCancelListener { adapter.closeOpen() }
                .show()
        } else doDeleteFile(f, false)
    }

    /** Папка удаляется со всем содержимым — это серьёзнее, поэтому спрашиваем */
    private fun confirmDeleteFolder(dir: File) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_folder_title) + "\n\n" + dir.name)
            .setPositiveButton(R.string.yes) { _, _ ->
                val cur = PlaybackService.instance?.currentPath
                if (cur != null && cur.startsWith(dir.absolutePath + "/")) PlaybackService.instance?.stopAll()
                val ok = dir.deleteRecursively()
                Store.forgetUnder(dir.absolutePath)
                if (ok) {
                    adapter.remove(dir)
                    updateCount()
                } else {
                    Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_LONG).show()
                    reload()                      // часть файлов могла удалиться
                }
            }
            .setNegativeButton(R.string.no, null)
            .setOnCancelListener { adapter.closeOpen() }
            .setOnDismissListener { adapter.closeOpen() }
            .show()
    }

    private fun doDeleteFile(f: File, withBookmarks: Boolean) {
        PlaybackService.instance?.takeIf { it.currentPath == f.absolutePath }?.stopAll()
        if (f.delete()) {
            Store.forget(f.absolutePath)
            if (withBookmarks) Bookmarks.delete(f.absolutePath)
            adapter.remove(f)
            updateCount()
        } else {
            Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Верхняя панель: обычная или в режиме выбора («← | Выбрано: N | ⋮ или удалить») */
    private fun applyBar() {
        val root = File(Store.rootFolder)
        val dir = currentDir ?: root
        val n = adapter.selectedCount()
        if (n > 0) {
            btnLeft.setImageResource(R.drawable.ic_back)           // стрелка сбрасывает выбор
            btnLeft.contentDescription = getString(R.string.back)
            tvTitle.text = getString(R.string.selected_count, n)
            btnNow.visibility = View.GONE
            btnMore.visibility = if (n == 1) View.VISIBLE else View.GONE
            btnSelDel.visibility = if (n > 1) View.VISIBLE else View.GONE
        } else {
            val atRoot = dir.absolutePath == root.absolutePath
            btnLeft.setImageResource(if (atRoot) R.drawable.ic_settings else R.drawable.ic_back)
            btnLeft.contentDescription = getString(if (atRoot) R.string.settings else R.string.back)
            tvTitle.text = dir.name                // в корне — название выбранной папки (Podcasts)
            btnMore.visibility = View.GONE
            btnSelDel.visibility = View.GONE
            btnNow.visibility = if (PlaybackService.instance?.loaded == true) View.VISIBLE else View.INVISIBLE
        }
        swipe.isEnabled = n == 0                // при выборе pull-to-refresh не нужен
    }

    // ---------- удаление выбранного ----------

    /** Сначала спрашиваем про удаление; потом (если у файлов есть закладки и «всегда удалять» выключено) — про закладки. Пока не ответили, ничего не удаляется. */
    private fun confirmDelete(files: List<File>) {
        if (files.isEmpty()) return
        val folders = files.count { it.isDirectory }
        // только обычный текст (без крупного жирного заголовка диалога)
        val text = if (files.size == 1) {
            getString(if (folders == 1) R.string.delete_folder_title else R.string.delete_title) + "\n\n" + files[0].name
        } else {
            getString(R.string.delete_selected_title, files.size) + if (folders > 0) "\n\n" + getString(R.string.delete_folders_note) else ""
        }
        AlertDialog.Builder(this)
            .setMessage(text)
            .setPositiveButton(R.string.yes) { _, _ -> askBookmarks(files) }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun askBookmarks(files: List<File>) {
        val withBm = files.filter { it.isFile && Bookmarks.exists(it.absolutePath) }
        when {
            withBm.isEmpty() -> deleteMany(files, false)
            Store.alwaysDeleteBookmarks -> deleteMany(files, true)
            else -> AlertDialog.Builder(this)
                .setMessage(if (withBm.size > 1) R.string.delete_bookmarks_many else R.string.delete_bookmarks)
                .setPositiveButton(R.string.yes) { _, _ -> deleteMany(files, true) }
                .setNegativeButton(R.string.no) { _, _ -> deleteMany(files, false) }
                .show()
        }
    }

    /** Файлы и папки (со всем содержимым) удаляются вместе с записями прогресса; закладки — по ответу */
    private fun deleteMany(files: List<File>, withBookmarks: Boolean) {
        val svc = PlaybackService.instance
        var failed = false
        for (f in files) {
            val p = f.absolutePath
            val isDir = f.isDirectory
            val cur = svc?.currentPath
            if (cur != null && (cur == p || cur.startsWith("$p/"))) svc?.stopAll()
            val ok = if (isDir) f.deleteRecursively() else f.delete()
            if (isDir) Store.forgetUnder(p)                  // часть файлов могла удалиться, даже если папка не удалилась целиком
            else if (ok) { Store.forget(p); if (withBookmarks) Bookmarks.delete(p) }
            if (ok) adapter.remove(f) else failed = true
        }
        adapter.clearSelection()
        updateCount()
        if (failed) {
            Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_LONG).show()
            reload()
        }
    }

    // ---------- переименование ----------

    private fun renameDialog(f: File) {
        val d = resources.displayMetrics.density
        val et = EditText(this)
        et.setSingleLine()
        et.setText(if (f.isDirectory) f.name else f.nameWithoutExtension)   // у файла расширение не трогаем
        et.selectAll()
        val box = FrameLayout(this)
        box.setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        box.addView(et, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        val dlg = AlertDialog.Builder(this)
            .setMessage(R.string.rename)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ -> rename(f, et.text.toString().trim()) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        et.requestFocus()
    }

    /** Переименовать на диске, потом перенести прогресс (и закладки, и путь в плеере) на новое имя */
    private fun rename(f: File, name: String) {
        if (name.isEmpty() || name.contains('/') || name == "." || name == "..") {
            Toast.makeText(this, R.string.rename_failed, Toast.LENGTH_LONG).show()
            return
        }
        val isDir = f.isDirectory
        val ext = if (isDir) "" else f.extension
        val newName = if (ext.isEmpty()) name else "$name.$ext"
        if (newName == f.name) { adapter.clearSelection(); return }
        val target = File(f.parentFile, newName)
        if (target.exists() && !target.absolutePath.equals(f.absolutePath, ignoreCase = true)) {
            Toast.makeText(this, R.string.rename_exists, Toast.LENGTH_LONG).show()
            return
        }
        val old = f.absolutePath
        val oldBm = if (isDir) null else Bookmarks.file(old)
        if (!f.renameTo(target)) {
            Toast.makeText(this, R.string.rename_failed, Toast.LENGTH_LONG).show()
            return
        }
        val np = target.absolutePath
        if (isDir) Store.renameUnder(old, np)
        else {
            Store.rename(old, np)
            if (oldBm != null && oldBm.exists()) { val nb = Bookmarks.file(np); if (!nb.exists()) oldBm.renameTo(nb) }
        }
        PlaybackService.instance?.pathChanged(old, np)
        adapter.clearSelection()
        reload()
    }

    private fun updateCount() {
        val files = adapter.fileCount()
        // индикатор «Записей: N» виден только если в папке есть хотя бы один файл
        tvCount.visibility = if (files > 0) View.VISIBLE else View.GONE
        val pct = adapter.listenedPercent()
        tvCount.text = if (pct == null) getString(R.string.files_count, files)
                       else getString(R.string.files_count_pct, files, pct)
        tvEmpty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    /** Перечитать содержимое текущей папки (при открытии папки и при pull-to-refresh). */
    private fun reload() {
        val root = File(Store.rootFolder)
        var dir = currentDir ?: root
        if (!dir.exists()) dir = root          // текущую папку удалили — вернёмся в корень
        currentDir = dir
        applyBar()
        val d = dir
        val gen = ++reloadGen
        io.execute {
            val access = Access.hasFiles(this)
            if (access && !d.exists()) d.mkdirs()
            val list = if (access) Store.list(d) else emptyList()
            val st = Store.allStatuses()
            val pos = Store.allPositions()
            val meta = Store.allMeta()
            if (access) Store.pruneMeta(d, meta)       // файлов, которых больше нет, в базе быть не должно
            val durs = HashMap<String, Int>()
            val missing = ArrayList<File>()
            for (f in list) if (f.isFile) {
                val c = Store.cachedDuration(meta, f)
                if (c != null) durs[f.absolutePath] = c else missing.add(f)
            }
            ui.post {
                if (isFinishing || isDestroyed || gen != reloadGen) return@post
                adapter.submit(list, st, pos, durs, PlaybackService.instance?.takeIf { it.isPlaying }?.currentPath)
                tvEmpty.setText(if (access) R.string.empty_folder else R.string.no_access)
                updateCount()
                swipe.isRefreshing = false
            }
            // длительности новых/изменённых файлов считаем в фоне и дописываем в базу
            for (f in missing) {
                if (gen != reloadGen) break
                val ms = Store.readDuration(f)
                if (ms > 0) {
                    Store.putDuration(f, ms)
                    ui.post { if (gen == reloadGen && !isDestroyed) { adapter.setDuration(f.absolutePath, ms); updateCount() } }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }
}
