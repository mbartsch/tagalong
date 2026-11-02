package cl.bartsch.tagalong

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The JPEG previews embedded in a NEF carry no EXIF at all (only DQT/DHT/SOF), so a saved copy would
 * have no date, camera, exposure, GPS or orientation. This rebuilds an EXIF APP1 segment from the
 * NEF's own TIFF metadata (IFD0 basics + Exif IFD + GPS IFD), read with ranged reads.
 *
 * MakerNote is dropped: its internal offsets are only valid inside the original file and it would
 * push the segment past the 64 KB APP1 limit.
 */
object ExifBuilder {
    private const val M32 = 0xFFFFFFFFL
    private val TYPE_SIZE = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8)

    private const val TAG_EXIF_PTR = 0x8769
    private const val TAG_GPS_PTR = 0x8825

    // IFD0 tags worth keeping (everything else there describes the raw/thumbnail layout).
    private val IFD0_KEEP = setOf(0x010F, 0x0110, 0x0112, 0x011A, 0x011B, 0x0128, 0x0131, 0x0132, 0x013B, 0x8298)
    // Exif IFD: drop MakerNote and the Interop pointer.
    private val EXIF_DROP = setOf(0x927C, 0xA005)

    private class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray)

    /** Complete APP1 segment (FF E1 len "Exif\0\0" TIFF), or null if the NEF has nothing usable. */
    fun build(read: (Long, Int) -> ByteArray): ByteArray? {
        val hdr = read(0, 8)
        val bo = when (hdr[0].toInt().toChar()) { 'I' -> ByteOrder.LITTLE_ENDIAN; 'M' -> ByteOrder.BIG_ENDIAN; else -> return null }
        fun bb(b: ByteArray) = ByteBuffer.wrap(b).order(bo)

        fun readIfd(off: Long): List<Entry> {
            if (off <= 0) return emptyList()
            val cnt = bb(read(off, 2)).getShort(0).toInt() and 0xFFFF
            val raw = read(off + 2, cnt * 12)
            val b = bb(raw)
            return (0 until cnt).mapNotNull { i ->
                val e = i * 12
                val tag = b.getShort(e).toInt() and 0xFFFF
                val type = b.getShort(e + 2).toInt() and 0xFFFF
                val count = b.getInt(e + 4)
                val ts = TYPE_SIZE.getOrElse(type) { 0 }
                val total = ts.toLong() * count
                if (ts == 0 || total <= 0 || total > 20_000) null
                else Entry(tag, type, count,
                    if (total <= 4) raw.copyOfRange(e + 8, e + 8 + total.toInt())
                    else read(b.getInt(e + 8).toLong() and M32, total.toInt()))
            }
        }

        val ifd0 = readIfd(bb(hdr).getInt(4).toLong() and M32)
        fun pointer(tag: Int) = ifd0.firstOrNull { it.tag == tag }?.let { bb(it.data).getInt(0).toLong() and M32 } ?: 0L
        val base = ifd0.filter { it.tag in IFD0_KEEP }
        val exif = readIfd(pointer(TAG_EXIF_PTR)).filter { it.tag !in EXIF_DROP }
        val gps = readIfd(pointer(TAG_GPS_PTR))
        if (base.isEmpty() && exif.isEmpty()) return null

        // Layout: header | IFD0 | Exif IFD | GPS IFD | out-of-line value data.
        val n0 = base.size + (if (exif.isNotEmpty()) 1 else 0) + (if (gps.isNotEmpty()) 1 else 0)
        fun sizeOf(n: Int) = if (n == 0) 0 else 2 + 12 * n + 4
        val ifd0Off = 8
        val exifOff = ifd0Off + sizeOf(n0)
        val gpsOff = exifOff + sizeOf(exif.size)
        val dataStart = gpsOff + sizeOf(gps.size)

        val data = ByteArrayOutputStream()
        fun encode(entries: List<Entry>): ByteArray {
            val out = ByteBuffer.allocate(sizeOf(entries.size)).order(bo)
            out.putShort(entries.size.toShort())
            for (e in entries.sortedBy { it.tag }) {
                out.putShort(e.tag.toShort()); out.putShort(e.type.toShort()); out.putInt(e.count)
                if (e.data.size <= 4) { out.put(e.data); repeat(4 - e.data.size) { out.put(0) } }
                else {
                    if (data.size() % 2 == 1) data.write(0)          // keep values word-aligned
                    out.putInt(dataStart + data.size()); data.write(e.data)
                }
            }
            out.putInt(0)   // no next IFD
            return out.array()
        }
        fun ptr(tag: Int, off: Int) = Entry(tag, 4, 1, ByteBuffer.allocate(4).order(bo).putInt(off).array())

        val b0 = encode(base + listOfNotNull(
            if (exif.isNotEmpty()) ptr(TAG_EXIF_PTR, exifOff) else null,
            if (gps.isNotEmpty()) ptr(TAG_GPS_PTR, gpsOff) else null))
        val bE = if (exif.isNotEmpty()) encode(exif) else ByteArray(0)
        val bG = if (gps.isNotEmpty()) encode(gps) else ByteArray(0)

        val tiff = ByteBuffer.allocate(8).order(bo)
            .put(if (bo == ByteOrder.BIG_ENDIAN) 'M'.code.toByte() else 'I'.code.toByte())
            .put(if (bo == ByteOrder.BIG_ENDIAN) 'M'.code.toByte() else 'I'.code.toByte())
            .putShort(42).putInt(ifd0Off).array() + b0 + bE + bG + data.toByteArray()

        val len = 2 + 6 + tiff.size
        if (len > 0xFFFF) return null
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte(),
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0) + tiff
    }
}
