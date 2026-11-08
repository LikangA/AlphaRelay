package com.alpharelay.camera

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * 相机连接层。
 *
 * 所有 MTP 调用都被串行化到一个专用线程：android.mtp.MtpDevice 不是线程安全的，
 * 而且一条 USB 会话上并发发 PTP 命令会直接错乱。
 */
object CameraHub {

    enum class Phase { IDLE, NEED_PERMISSION, CONNECTING, READY, ERROR }

    data class Storage(val id: Int, val label: String, val total: Long, val free: Long)

    data class Link(
        val phase: Phase = Phase.IDLE,
        val model: String = "",
        val message: String = "",
        val storages: List<Storage> = emptyList()
    )

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "mtp-io") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _link = MutableStateFlow(Link())
    val link: StateFlow<Link> = _link.asStateFlow()

    private var app: Context? = null
    private var device: MtpDevice? = null
    private var storageId: Int = 0

    fun init(context: Context) {
        if (app == null) app = context.applicationContext
    }

    private fun manager(): UsbManager? = app?.getSystemService(Context.USB_SERVICE) as? UsbManager

    fun isMtpDevice(d: UsbDevice): Boolean {
        for (i in 0 until d.interfaceCount) {
            val itf = d.getInterface(i)
            if (itf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE &&
                itf.interfaceSubclass == 1 &&
                itf.interfaceProtocol == 1
            ) {
                return true
            }
        }
        return false
    }

    fun findDevice(): UsbDevice? = manager()?.deviceList?.values?.firstOrNull { isMtpDevice(it) }

    fun hasPermission(d: UsbDevice): Boolean = manager()?.hasPermission(d) == true

    fun isReady(): Boolean = _link.value.phase == Phase.READY

    suspend fun connect(target: UsbDevice): Boolean = withContext(dispatcher) {
        closeQuietly()
        _link.value = Link(phase = Phase.CONNECTING, message = "正在建立 MTP 会话…")

        val mgr = manager()
        if (mgr == null) {
            _link.value = Link(phase = Phase.ERROR, message = "USB 服务不可用")
            return@withContext false
        }
        if (!mgr.hasPermission(target)) {
            _link.value = Link(phase = Phase.NEED_PERMISSION, message = "请允许 App 访问相机")
            return@withContext false
        }

        val connection = try {
            mgr.openDevice(target)
        } catch (e: Exception) {
            Diag.error("usb", "openDevice 抛异常", e)
            null
        }
        if (connection == null) {
            Diag.error("usb", "openDevice 返回 null，设备可能被占用")
            _link.value = Link(phase = Phase.ERROR, message = "打不开 USB 设备，请拔插数据线重试")
            return@withContext false
        }

        val dev = MtpDevice(target)
        val opened = try {
            dev.open(connection)
        } catch (e: Exception) {
            false
        }
        if (!opened) {
            runCatching { connection.close() }
            Diag.error("mtp", "MTP open() 返回 false")
            _link.value = Link(
                phase = Phase.ERROR,
                message = "MTP 会话建立失败。请在相机上把 USB 连接设为 MTP，并关掉「智能手机控制」。"
            )
            return@withContext false
        }
        device = dev

        // 刚建立会话时部分相机还读不到存储卡，重试几次
        var storages = readStorages(dev)
        var attempt = 0
        while (storages.isEmpty() && attempt < 6) {
            delay(300)
            storages = readStorages(dev)
            attempt++
        }
        if (storages.isEmpty()) {
            closeQuietly()
            Diag.error("mtp", "6 次重试后仍读不到存储卡")
            _link.value = Link(phase = Phase.ERROR, message = "读不到存储卡，请确认卡已插入，且相机处于 MTP 模式")
            return@withContext false
        }

        storageId = storages.first().id
        val model = runCatching { dev.deviceInfo?.model ?: "" }.getOrDefault("")
        Diag.info("mtp", "连接成功 model=$model storages=${storages.size} storageId=$storageId")
        _link.value = Link(phase = Phase.READY, model = model, storages = storages)
        true
    }

    private fun readStorages(dev: MtpDevice): List<Storage> {
        val ids = try {
            dev.storageIds
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        val result = ArrayList<Storage>(ids.size)
        for (id in ids) {
            val info = try {
                dev.getStorageInfo(id)
            } catch (e: Exception) {
                null
            } ?: continue
            val label = (info.description ?: "").ifBlank { "存储卡" }
            result.add(Storage(id, label, info.maxCapacity, info.freeSpace))
        }
        return result
    }

    /** 完整枚举：每个文件都取一次 info，比较慢，只在进入/刷新时用。 */
    suspend fun listFiles(): List<CameraFile> = withContext(dispatcher) {
        val dev = device ?: return@withContext emptyList()
        if (storageId == 0) return@withContext emptyList()

        val roots = queryRoots(dev) ?: return@withContext emptyList()
        val out = ArrayList<CameraFile>(1024)
        val seen = HashSet<Int>(1024)
        for (handle in roots) walkFiles(dev, handle, 0, seen, out)
        out.sortWith(compareByDescending<CameraFile> { it.modifiedSec }.thenByDescending { it.name })
        Diag.info("mtp", "枚举完成，共 ${out.size} 个文件")
        out
    }

    /** 规范写法是父节点传 -1 取根；少数机型不支持，退化成取「全部对象」。 */
    private fun queryRoots(dev: MtpDevice): IntArray? {
        val roots = try {
            dev.getObjectHandles(storageId, 0, -1)
        } catch (e: Exception) {
            null
        }
        if (roots != null && roots.isNotEmpty()) return roots
        return try {
            dev.getObjectHandles(storageId, 0, 0)
        } catch (e: Exception) {
            null
        }
    }

    private fun walkFiles(
        dev: MtpDevice,
        handle: Int,
        depth: Int,
        seen: MutableSet<Int>,
        out: MutableList<CameraFile>
    ) {
        if (out.size >= 8000) return
        if (!seen.add(handle)) return

        val info = try {
            dev.getObjectInfo(handle)
        } catch (e: Exception) {
            null
        } ?: return

        if (info.format == MtpConstants.FORMAT_ASSOCIATION) {
            if (depth >= 4) return
            val children = try {
                dev.getObjectHandles(storageId, 0, handle)
            } catch (e: Exception) {
                null
            } ?: return
            for (child in children) walkFiles(dev, child, depth + 1, seen, out)
        } else {
            val name = info.name ?: return
            out.add(
                CameraFile(
                    handle = handle,
                    name = name,
                    size = info.compressedSize,
                    modifiedSec = info.dateModified.toLong(),
                    width = info.imagePixWidth,
                    height = info.imagePixHeight,
                    kind = Formats.kindOf(name)
                )
            )
        }
    }

    /** 轻量枚举：只比对 handle，不取每个文件的 info，用于「即拍即传」轮询。 */
    suspend fun listHandles(): Set<Int> = withContext(dispatcher) {
        val dev = device ?: return@withContext emptySet()
        if (storageId == 0) return@withContext emptySet()

        val roots = queryRoots(dev) ?: return@withContext emptySet()
        val out = HashSet<Int>(2048)
        for (handle in roots) collectHandles(dev, handle, 0, out)
        out
    }

    private fun collectHandles(dev: MtpDevice, handle: Int, depth: Int, out: MutableSet<Int>) {
        if (depth > 4) return
        val children = try {
            dev.getObjectHandles(storageId, 0, handle)
        } catch (e: Exception) {
            null
        }
        if (children != null && children.isNotEmpty()) {
            for (child in children) collectHandles(dev, child, depth + 1, out)
            return
        }
        val info = try {
            dev.getObjectInfo(handle)
        } catch (e: Exception) {
            null
        } ?: return
        if (info.format == MtpConstants.FORMAT_ASSOCIATION) return
        out.add(handle)
    }

    suspend fun infoOf(handles: List<Int>): List<CameraFile> = withContext(dispatcher) {
        val dev = device ?: return@withContext emptyList()
        val result = ArrayList<CameraFile>(handles.size)
        for (handle in handles) {
            val info = try {
                dev.getObjectInfo(handle)
            } catch (e: Exception) {
                null
            } ?: continue
            val name = info.name ?: continue
            result.add(
                CameraFile(
                    handle = handle,
                    name = name,
                    size = info.compressedSize,
                    modifiedSec = info.dateModified.toLong(),
                    width = info.imagePixWidth,
                    height = info.imagePixHeight,
                    kind = Formats.kindOf(name)
                )
            )
        }
        result
    }

    /**
     * 取缩略图。JPEG 走 MTP 缩略图；RAW 的 MTP 缩略图常常是空的，
     * 这时只读文件前 6MB，从 TIFF 结构里抠内嵌预览图，不用下载整张。
     */
    suspend fun thumbnailBytes(f: CameraFile): ByteArray? = withContext(dispatcher) {
        val dev = device ?: return@withContext null

        val thumb = try {
            dev.getThumbnail(f.handle)
        } catch (e: Exception) {
            null
        }
        if (thumb != null && thumb.size > 512) return@withContext thumb

        if (f.kind == FileKind.RAW) {
            val cap = minOf(f.size, 6L * 1024L * 1024L).toInt()
            if (cap > 64 * 1024) {
                val buffer = ByteArray(cap)
                val read = try {
                    dev.getPartialObject(f.handle, 0L, cap.toLong(), buffer)
                } catch (e: Exception) {
                    -1L
                }
                if (read > 0) {
                    val jpeg = PreviewExtractor.extract(buffer, read.toInt())
                    if (jpeg != null) return@withContext jpeg
                }
            }
        }
        null
    }

    suspend fun download(f: CameraFile, dest: File): Boolean = withContext(dispatcher) {
        val dev = device ?: return@withContext false
        dest.parentFile?.mkdirs()
        try {
            val mode = ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_TRUNCATE
            ParcelFileDescriptor.open(dest, mode).use { pfd ->
                dev.importFile(f.handle, pfd)
            }
        } catch (e: Exception) {
            Diag.error("download", "读取 ${f.name} 失败", e)
            false
        }
    }

    fun onDeviceDetached() {
        scope.launch {
            closeQuietly()
            _link.value = Link(phase = Phase.IDLE, message = "相机已断开")
        }
    }

    fun claimNoPermission() {
        _link.value = Link(phase = Phase.NEED_PERMISSION, message = "没有 USB 访问权限")
    }

    private fun closeQuietly() {
        val old = device
        device = null
        storageId = 0
        if (old != null) runCatching { old.close() }
    }
}
