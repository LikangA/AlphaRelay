package com.alpharelay.camera

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private lateinit var adapter: FileAdapter

    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var progressCard: View
    private lateinit var progressText: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var summary: TextView
    private lateinit var exportButton: Button
    private lateinit var selectAllButton: Button

    private var allFiles: List<CameraFile> = emptyList()
    private var visibleFiles: List<CameraFile> = emptyList()
    private var filter: FileKind? = null
    private val selected = LinkedHashSet<Int>()

    private var autoMode = false
    private var autoJob: Job? = null
    private var connecting = false
    private var syncing = false
    private var fromCache = false
    private var pendingExport: List<CameraFile>? = null
    private var wasRunning = false
    private var receiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        recycler = findViewById(R.id.recycler)
        emptyView = findViewById(R.id.emptyView)
        progressCard = findViewById(R.id.progressCard)
        progressText = findViewById(R.id.progressText)
        progressBar = findViewById(R.id.progressBar)
        summary = findViewById(R.id.summary)
        exportButton = findViewById(R.id.btnExport)
        selectAllButton = findViewById(R.id.btnSelectAll)

        adapter = FileAdapter(
            loader = { CameraHub.thumbnailBytes(it) },
            onOpen = { preview(it) },
            onSelect = { toggleSelect(it) }
        )
        recycler.layoutManager = GridLayoutManager(this, 2)
        recycler.adapter = adapter

        findViewById<ChipGroup>(R.id.filterGroup).setOnCheckedStateChangeListener { _, checkedIds ->
            filter = when (checkedIds.firstOrNull()) {
                R.id.chipRaw -> FileKind.RAW
                R.id.chipJpeg -> FileKind.JPEG
                R.id.chipHeif -> FileKind.HEIF
                R.id.chipVideo -> FileKind.VIDEO
                else -> null
            }
            render()
        }

        exportButton.setOnClickListener { doExport() }
        selectAllButton.setOnClickListener { toggleSelectAll() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { ExportBus.cancel() }

        registerUsbReceiver()
        collectProgress()
        requestNotificationPermission()

        render()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        if (!CameraHub.isReady() && !connecting) tryConnect()
    }

    override fun onDestroy() {
        autoJob?.cancel()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(usbReceiver) }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    // ---------------- USB ----------------

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        connectDevice(device)
                    } else {
                        CameraHub.claimNoPermission()
                        updateStatus()
                        toast(getString(R.string.perm_denied))
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> tryConnect()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    CameraHub.onDeviceDetached()
                    allFiles = emptyList()
                    selected.clear()
                    render()
                    updateStatus()
                    toast(getString(R.string.camera_detached))
                }
            }
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun requestPermission(device: UsbDevice) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val pending = PendingIntent.getBroadcast(this, 0, intent, flags)
        usbManager.requestPermission(device, pending)
    }

    private fun tryConnect() {
        val device = CameraHub.findDevice()
        if (device == null) {
            showDisconnected()
            return
        }
        if (!usbManager.hasPermission(device)) {
            statusTitle.text = getString(R.string.status_perm)
            statusDetail.text = getString(R.string.status_perm_hint)
            requestPermission(device)
            return
        }
        connectDevice(device)
    }

    private fun connectDevice(device: UsbDevice) {
        if (connecting) return
        connecting = true
        statusTitle.text = getString(R.string.status_connecting)
        statusDetail.text = getString(R.string.status_connecting_hint)
        lifecycleScope.launch {
            val ok = try {
                CameraHub.connect(device)
            } catch (e: Exception) {
                Diag.error("connect", "连接过程抛异常", e)
                false
            }
            connecting = false
            updateStatus()
            if (ok) {
                showCachedListing()
                loadFiles()
            }
        }
    }

    /** 上次的清单先铺上去，别让用户盯着空列表等扫卡。 */
    private suspend fun showCachedListing() {
        if (allFiles.isNotEmpty()) return
        val cached = withContext(Dispatchers.IO) { ListingCache.load(this@MainActivity) }
        if (cached.isEmpty()) return
        allFiles = cached
        fromCache = true
        render()
        statusDetail.text = getString(R.string.cache_hint, cached.size)
    }

    private fun loadFiles() {
        if (syncing) return
        syncing = true
        if (!fromCache) statusDetail.text = getString(R.string.reading)
        lifecycleScope.launch {
            var failed = false
            val files = try {
                CameraHub.listFiles()
            } catch (e: Exception) {
                Diag.error("sync", "扫描存储卡失败", e)
                failed = true
                emptyList()
            }
            syncing = false
            if (!failed) {
                allFiles = files
                fromCache = false
                selected.clear()
                withContext(Dispatchers.IO) { ListingCache.save(this@MainActivity, files) }
            }
            render()
            updateStatus()
            if (!failed && files.isEmpty()) toast(getString(R.string.no_files))
        }
    }

    // ---------------- UI ----------------

    private fun render() {
        val current = filter
        visibleFiles = if (current == null) allFiles else allFiles.filter { it.kind == current }
        selected.retainAll(visibleFiles.mapTo(HashSet<Int>()) { it.handle })
        adapter.submit(visibleFiles, selected)

        val empty = visibleFiles.isEmpty()
        recycler.visibility = if (empty) View.GONE else View.VISIBLE
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        emptyView.text = if (CameraHub.isReady()) {
            getString(R.string.empty_no_files)
        } else {
            getString(R.string.empty_no_camera)
        }
        updateSummary()
    }

    private fun updateSummary() {
        val count = visibleFiles.size
        val picked = selected.size
        summary.text = getString(R.string.summary, count, picked)
        exportButton.text = if (picked == 0) {
            getString(R.string.export_all, count)
        } else {
            getString(R.string.export_selected, picked)
        }
        exportButton.isEnabled = count > 0
        selectAllButton.text = if (picked == 0) getString(R.string.select_all) else getString(R.string.clear_selection)
        invalidateOptionsMenu()
    }

    private fun updateStatus() {
        val link = CameraHub.link.value
        when (link.phase) {
            CameraHub.Phase.READY -> {
                statusTitle.text = if (link.model.isBlank()) getString(R.string.status_ready) else link.model
                val storage = link.storages.firstOrNull()
                statusDetail.text = if (storage != null) {
                    getString(
                        R.string.status_storage,
                        storage.label,
                        Formats.size(storage.free),
                        Formats.size(storage.total)
                    )
                } else {
                    getString(R.string.status_ready_hint)
                }
            }
            CameraHub.Phase.CONNECTING -> {
                statusTitle.text = getString(R.string.status_connecting)
                statusDetail.text = link.message
            }
            CameraHub.Phase.NEED_PERMISSION -> {
                statusTitle.text = getString(R.string.status_perm)
                statusDetail.text = link.message
            }
            CameraHub.Phase.ERROR -> {
                statusTitle.text = getString(R.string.status_error)
                statusDetail.text = link.message
            }
            CameraHub.Phase.IDLE -> showDisconnected()
        }
    }

    private fun showDisconnected() {
        statusTitle.text = getString(R.string.status_disconnected)
        statusDetail.text = getString(R.string.status_hint)
        if (allFiles.isNotEmpty()) {
            allFiles = emptyList()
            selected.clear()
            render()
        }
    }

    private fun toggleSelect(item: CameraFile) {
        if (!selected.add(item.handle)) selected.remove(item.handle)
        adapter.submit(visibleFiles, selected)
        updateSummary()
    }

    private fun toggleSelectAll() {
        if (selected.isEmpty()) {
            visibleFiles.forEach { selected.add(it.handle) }
        } else {
            selected.clear()
        }
        adapter.submit(visibleFiles, selected)
        updateSummary()
    }

    // ---------------- 导出 ----------------

    private fun doExport() {
        if (fromCache) {
            toast(getString(R.string.cache_wait))
            return
        }
        val list = if (selected.isEmpty()) {
            visibleFiles
        } else {
            visibleFiles.filter { selected.contains(it.handle) }
        }
        if (list.isEmpty()) {
            toast(getString(R.string.nothing_to_export))
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingExport = list
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE
            )
            return
        }
        startExport(list)
    }

    private fun startExport(list: List<CameraFile>) {
        val intent = Intent(this, ImportService::class.java).apply {
            action = ImportService.ACTION_START
            putExtra(ImportService.EXTRA_FILES, ArrayList(list))
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun collectProgress() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ExportBus.state.collect { state -> renderProgress(state) }
            }
        }
    }

    private fun renderProgress(state: ExportBus.State) {
        if (state.total == 0 && !state.running) {
            progressCard.visibility = View.GONE
            wasRunning = false
            return
        }
        progressCard.visibility = View.VISIBLE
        val total = state.total.coerceAtLeast(1)
        progressBar.max = total
        progressBar.setProgressCompat(state.done.coerceIn(0, total), true)
        progressText.text = if (state.running) {
            getString(R.string.progress_line, state.done, state.total, state.current)
        } else {
            state.summary.ifEmpty { getString(R.string.progress_done) }
        }
        if (wasRunning && !state.running) {
            toast(state.summary)
            hideProgressLater()
        }
        wasRunning = state.running
    }

    private fun hideProgressLater() {
        lifecycleScope.launch {
            delay(4000)
            if (!ExportBus.state.value.running) {
                progressCard.visibility = View.GONE
                ExportBus.clear()
            }
        }
    }

    // ---------------- 预览 ----------------

    private fun preview(f: CameraFile) {
        if (f.kind == FileKind.VIDEO) {
            toast(getString(R.string.video_no_preview))
            return
        }
        if (fromCache) {
            toast(getString(R.string.cache_wait))
            return
        }
        startActivity(PreviewActivity.intent(this, f))
    }

    // ---------------- 菜单 ----------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_auto)?.isChecked = autoMode
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                loadFiles()
                true
            }
            R.id.action_reconnect -> {
                reconnect()
                true
            }
            R.id.action_auto -> {
                toggleAuto()
                true
            }
            R.id.action_about -> {
                showAbout()
                true
            }
            R.id.action_diag -> {
                showDiagnostics()
                true
            }
            R.id.action_clear_index -> {
                clearExportIndex()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun reconnect() {
        val device = CameraHub.findDevice()
        if (device == null) {
            toast(getString(R.string.status_disconnected))
            return
        }
        if (!usbManager.hasPermission(device)) {
            requestPermission(device)
            return
        }
        connectDevice(device)
    }

    private fun toggleAuto() {
        if (!CameraHub.isReady()) {
            toast(getString(R.string.auto_need_camera))
            return
        }
        autoMode = !autoMode
        autoJob?.cancel()
        autoJob = null
        if (!autoMode) {
            toast(getString(R.string.auto_off))
            invalidateOptionsMenu()
            return
        }
        toast(getString(R.string.auto_on))
        invalidateOptionsMenu()

        autoJob = lifecycleScope.launch {
            val known = HashSet<Int>()
            known.addAll(allFiles.map { it.handle })
            while (isActive) {
                delay(5000)
                if (!CameraHub.isReady()) continue
                val handles = try {
                    CameraHub.listHandles()
                } catch (e: Exception) {
                    emptySet()
                }
                if (handles.isEmpty()) continue
                val fresh = handles.filter { it !in known }
                if (fresh.isEmpty()) continue
                val files = try {
                    CameraHub.infoOf(fresh)
                } catch (e: Exception) {
                    emptyList()
                }
                if (files.isEmpty()) continue
                files.forEach { known.add(it.handle) }
                allFiles = (files + allFiles).sortedWith(
                    compareByDescending<CameraFile> { it.modifiedSec }.thenByDescending { it.name }
                )
                render()
                toast(getString(R.string.auto_new, files.size))
                startExport(files)
            }
        }
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_about)
            .setMessage(R.string.about_text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showDiagnostics() {
        val text = Diag.dump()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_diag)
            .setMessage(text)
            .setPositiveButton(R.string.diag_copy) { _, _ -> copyToClipboard(text) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val manager = getSystemService(ClipboardManager::class.java) ?: return
        manager.setPrimaryClip(ClipData.newPlainText("AlphaRelay", text))
        toast(getString(R.string.copied))
    }

    private fun clearExportIndex() {
        ExportIndex.clear(this)
        Diag.info("index", "导出记录已清空")
        toast(getString(R.string.index_cleared))
    }

    // ---------------- 权限 ----------------

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_STORAGE) return
        val list = pendingExport
        pendingExport = null
        if (list == null) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startExport(list)
        } else {
            toast(getString(R.string.need_storage_perm))
        }
    }

    private fun toast(text: String) {
        if (text.isBlank()) return
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.alpharelay.camera.USB_PERMISSION"
        private const val REQ_STORAGE = 1001
        private const val REQ_NOTIFICATION = 1002
    }
}
