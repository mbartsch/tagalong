package cl.bartsch.tagalong

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Locates the JPEG previews embedded in a NEF (a TIFF container) using ranged reads, so a
 * full-size preview can be fetched without downloading the whole raw file.
 */
object NefPreview {
    data class Jpeg(val where: String, val offset: Long, val length: Long, val w: Int, val h: Int)

    private const val M32 = 0xFFFFFFFFL
    private const val HEAD = 256 * 1024
    private const val CHUNK = 512 * 1024

    /** Walk IFD0 -> IFD1 / SubIFDs and collect every JPEG (JPEGInterchangeFormat or JPEG strips). */
    fun find(read: (Long, Int) -> ByteArray, log: (String) -> Unit = {}): List<Jpeg> {
        val hdr = read(0, 8)
        val bo = when (hdr[0].toInt().toChar()) { 'I' -> ByteOrder.LITTLE_ENDIAN; 'M' -> ByteOrder.BIG_ENDIAN
            else -> error("not a TIFF/NEF") }
        fun bb(b: ByteArray) = ByteBuffer.wrap(b).order(bo)
        check(bb(hdr).getShort(2).toInt() == 42) { "bad TIFF magic" }

        val out = ArrayList<Jpeg>()
        val seen = HashSet<Long>()
        fun walk(off: Long, where: String) {
            if (off <= 0 || seen.size >= 16 || !seen.add(off)) return
            val cnt = bb(read(off, 2)).getShort(0).toInt() and 0xFFFF
            val b = bb(read(off + 2, cnt * 12 + 4))
            var jOff = -1L; var jLen = -1L; var sOff = -1L; var sLen = -1L
            var w = 0; var h = 0; var comp = 0
            var subs = LongArray(0)
            fun value(e: Int): Long =
                if ((b.getShort(e + 2).toInt() and 0xFFFF) == 3) (b.getShort(e + 8).toLong() and 0xFFFF)
                else b.getInt(e + 8).toLong() and M32
            for (i in 0 until cnt) {
                val e = i * 12
                when (b.getShort(e).toInt() and 0xFFFF) {
                    0x0100 -> w = value(e).toInt()
                    0x0101 -> h = value(e).toInt()
                    0x0103 -> comp = value(e).toInt()
                    0x0111 -> sOff = value(e)
                    0x0117 -> sLen = value(e)
                    0x0201 -> jOff = value(e)
                    0x0202 -> jLen = value(e)
                    0x014A -> {
                        val n = b.getInt(e + 4)
                        subs = if (n == 1) longArrayOf(b.getInt(e + 8).toLong() and M32)
                               else bb(read(b.getInt(e + 8).toLong() and M32, n * 4)).let { a -> LongArray(n) { a.getInt(it * 4).toLong() and M32 } }
                    }
                }
            }
            log("  IFD $where @$off: ${w}x$h comp=$comp interchange=$jOff/$jLen strip=$sOff/$sLen subIFDs=${subs.toList()}")
            if (jOff >= 0 && jLen > 0) out += Jpeg(where, jOff, jLen, w, h)
            else if ((comp == 6 || comp == 7) && sOff >= 0 && sLen > 0) out += Jpeg("$where(strip)", sOff, sLen, w, h)
            subs.forEachIndexed { i, s -> walk(s, "$where/Sub$i") }
            walk(b.getInt(cnt * 12).toLong() and M32, "$where+")   // next IFD in chain
        }
        walk(bb(hdr).getInt(4).toLong() and M32, "IFD0")
        return out
    }

    /** Pixel size from the first SOFn marker, or null. */
    fun jpegSize(d: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 9 < d.size) {
            if (d[i] != 0xFF.toByte()) return null
            val m = d[i + 1].toInt() and 0xFF
            if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                val h = ((d[i + 5].toInt() and 0xFF) shl 8) or (d[i + 6].toInt() and 0xFF)
                val w = ((d[i + 7].toInt() and 0xFF) shl 8) or (d[i + 8].toInt() and 0xFF)
                return w to h
            }
            i += 2 + (((d[i + 2].toInt() and 0xFF) shl 8) or (d[i + 3].toInt() and 0xFF))
        }
        return null
    }

    /** Ranged read built from GetPartialObject, chunked in case the camera caps the length. */
    fun readRange(cam: PtpIp, handle: Int, off: Long, len: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(len)
        var pos = off; var left = len
        while (left > 0) {
            val d = cam.getPartialObject(handle, pos, minOf(left, CHUNK))
            if (d.isEmpty()) break
            out.write(d); pos += d.size; left -= d.size
        }
        return out.toByteArray()
    }

    /** Ranged reader for one NEF: a single 256 KB head read serves the metadata near the start,
     *  anything beyond it is fetched on demand. */
    fun reader(cam: PtpIp, handle: Int): (Long, Int) -> ByteArray {
        val head = readRange(cam, handle, 0, HEAD)
        return { off, n ->
            if (off + n <= head.size) head.copyOfRange(off.toInt(), off.toInt() + n) else readRange(cam, handle, off, n)
        }
    }
}
