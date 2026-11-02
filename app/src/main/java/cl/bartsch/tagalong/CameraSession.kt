package cl.bartsch.tagalong

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * One live camera connection for browsing/downloading photos: BLE auth -> start the camera AP ->
 * join it -> PTP/IP session. PTP is strictly one-operation-at-a-time, so every call goes through
 * [mutex]; callers that scroll away simply get cancelled while waiting for the lock.
 */
class CameraSession(private val ctx: Context, private val log: (String) -> Unit = {}) {
    val items = mutableStateListOf<PtpIp.ObjectInfo>()
    val thumbs = mutableStateMapOf<Int, Bitmap>()
    var loading by mutableStateOf(true)
        private set

    /** Gallery folder name = active camera profile name (filesystem-safe), e.g. "D780" or "Z6 II". */
    val folder: String = Profiles.active(ctx).name.replace(Regex("[^A-Za-z0-9 ._-]"), "_").trim().ifEmpty { "Nikon" }

    private val mutex = Mutex()
    private val wifi = WifiCamera(ctx) { log(it) }
    private var ptp: PtpIp? = null
    private val requested = HashSet<Int>()                       // main thread only
    private class Meta(val jpegs: List<NefPreview.Jpeg>, val exif: ByteArray?)
    private val metaCache = HashMap<Int, Meta>()                  // guarded by mutex

    private suspend fun <T> io(block: (PtpIp) -> T): T =
        mutex.withLock { withContext(Dispatchers.IO) { block(ptp ?: error("camera session closed")) } }

    /** Bring everything up; throws on failure (caller should then [close]). */
    suspend fun open() {
        val ble = TagalongBle(ctx, Identity.clientId(ctx)) { log(it) }
        val creds = try {
            withContext(Dispatchers.IO) {
                ble.connect(TagalongScanner(ctx) { log(it) }.scan()); ble.authenticate()
                val c = ble.readWifiConfig()   // decrypt SSID/PSK over BLE
                ble.startWifiAp()              // tell the camera to bring up its AP
                c
            }
        } finally { ble.close() }
        log("AP booting, waiting 6s ...")
        delay(6000)
        log("joining ${creds.ssid} ...")
        val net = wifi.join(creds.ssid, creds.psk)
        val host = wifi.cameraHost(net)
        ptp = withContext(Dispatchers.IO) {
            // The PTP daemon can take a while to accept after the AP is up; retry with backoff.
            var cam: PtpIp? = null
            for (attempt in 1..6) {
                try { cam = PtpIp(net, host, log = { log(it) }).also { it.connect() }; break }
                catch (e: Exception) { log("ptp connect try $attempt: ${e.javaClass.simpleName}: ${e.message}"); delay(3000) }
            }
            (cam ?: error("PTP connect failed after retries")).also { it.openSession(); log("ptp session open") }
        }
    }

    /** List photos newest-first, appending to [items] as each object's info arrives. */
    suspend fun loadItems() {
        loading = true
        try {
            val handles = io { it.getObjectHandles() }
            log("${handles.size} objects on card")
            for (h in handles.reversed()) {
                val i = io { it.getObjectInfo(h) }
                val n = i.name.uppercase()
                if (i.format == 0x3001 || !(n.endsWith(".NEF") || n.endsWith(".JPG") || n.endsWith(".JPEG"))) continue
                items.add(i)
            }
        } finally { loading = false }
    }

    /** 160x120 grid thumbnail into [thumbs]; no-op if already loaded or in flight. */
    suspend fun ensureThumb(handle: Int) {
        if (thumbs.containsKey(handle) || !requested.add(handle)) return
        var ok = false
        try {
            decode(io { it.getThumb(handle) })?.let { thumbs[handle] = it; ok = true }
        } finally { if (!ok) requested.remove(handle) }   // cancelled/failed: allow a retry
    }

    /** 640x424 preview via Nikon GetLargeThumb: quick first paint in the viewer. */
    suspend fun largeThumb(handle: Int): Bitmap? = decode(io { it.getLargeThumb(handle) })

    private fun isNef(i: PtpIp.ObjectInfo) = i.name.endsWith(".NEF", ignoreCase = true)

    /** Embedded JPEGs + rebuilt EXIF of a NEF, from ranged reads (cached). Must run under [io]. */
    private fun meta(cam: PtpIp, handle: Int): Meta = metaCache.getOrPut(handle) {
        val rd = NefPreview.reader(cam, handle)
        Meta(NefPreview.find(rd), runCatching { ExifBuilder.build(rd) }
            .onFailure { log("EXIF rebuild failed: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull())
    }
    private fun embedded(cam: PtpIp, handle: Int) = meta(cam, handle).jpegs

    /** Viewer image: the mid-size (~1620x1080) embedded JPEG of a NEF, or the JPEG itself. */
    suspend fun preview(i: PtpIp.ObjectInfo): Bitmap? = decode(io { cam ->
        if (isNef(i)) embedded(cam, i.handle).minByOrNull { it.length }
            ?.let { NefPreview.readRange(cam, i.handle, it.offset, it.length.toInt()) }
        else cam.getObject(i.handle)
    }, maxDim = 2048)

    /**
     * Stream one photo straight into the shared gallery without buffering it in memory.
     * [original] true saves a NEF as-is (Download/<profile name>); otherwise the full-size embedded JPEG
     * of a NEF (~1 MB) or the camera JPEG itself is saved to Pictures/<profile name>.
     * [onProgress] gets (bytesSoFar, totalBytes) on the IO thread. Returns the saved file name.
     */
    suspend fun saveToGallery(i: PtpIp.ObjectInfo, original: Boolean, onProgress: (Long, Long) -> Unit = { _, _ -> }): String {
        val nef = isNef(i)
        val asNef = original && nef
        val name = if (nef && !original) i.name.substringBeforeLast('.') + ".jpg" else i.name
        val image = !asNef
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (image) "image/jpeg" else "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (image) "Pictures" else "Download") + "/$folder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val res = ctx.contentResolver
        val uri = res.insert(if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                             else MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: error("MediaStore insert failed")
        try {
            io { cam ->
                val jpeg = if (nef && !original) embedded(cam, i.handle).maxByOrNull { it.length }
                               ?: error("no embedded JPEG in ${i.name}") else null
                val exif = if (jpeg != null) meta(cam, i.handle).exif else null
                if (jpeg != null) log("${i.name}: ${if (exif != null) "EXIF ${exif.size}B added" else "no EXIF available"}")
                val total = (jpeg?.length ?: i.size) + (exif?.size ?: 0)
                var done = 0L
                val out = object : java.io.FilterOutputStream(res.openOutputStream(uri)!!.buffered(256 * 1024)) {
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        out.write(b, off, len); done += len; onProgress(done, total)
                    }
                }
                out.use {
                    when {
                        jpeg != null -> {   // ranged reads in 512 KB pieces, each streamed to disk
                            // The embedded JPEG has no EXIF: write SOI + rebuilt APP1, then the rest of the data.
                            var pos = jpeg.offset
                            if (exif != null) { it.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte())); it.write(exif); pos += 2 }
                            val end = jpeg.offset + jpeg.length
                            while (pos < end) {
                                val n = minOf(end - pos, 512L * 1024).toInt()
                                val before = done
                                cam.getPartialObjectTo(i.handle, pos, n, it)
                                check(done > before) { "camera returned no data at offset $pos" }
                                pos += done - before
                            }
                        }
                        else -> cam.getObjectTo(i.handle, it)
                    }
                }
            }
            res.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return name
        } catch (e: Throwable) {
            runCatching { res.delete(uri, null, null) }   // don't leave a half-written file in the gallery
            throw e
        }
    }

    suspend fun close() {
        val cam = ptp; ptp = null
        runCatching {
            if (cam != null) withContext(Dispatchers.IO) { cam.closeSession(); cam.close() }
        }
        wifi.release()
    }

    companion object {
        /** Decode a JPEG, subsampled so the longest side stays near [maxDim], honouring EXIF rotation. */
        fun decode(b: ByteArray?, maxDim: Int = 0): Bitmap? {
            if (b == null || b.isEmpty()) return null
            val opts = BitmapFactory.Options()
            if (maxDim > 0) {
                opts.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(b, 0, b.size, opts)
                var s = 1
                while (maxOf(opts.outWidth, opts.outHeight) / (s * 2) >= maxDim) s *= 2
                opts.inJustDecodeBounds = false; opts.inSampleSize = s
            }
            val bmp = BitmapFactory.decodeByteArray(b, 0, b.size, opts) ?: return null
            val deg = when (runCatching {
                ExifInterface(ByteArrayInputStream(b)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
            }.getOrDefault(1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
            if (deg == 0f) return bmp
            return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        }
    }
}
