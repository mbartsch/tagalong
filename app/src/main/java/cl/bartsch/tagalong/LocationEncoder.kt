package cl.bartsch.tagalong

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor

/**
 * Builds the Nikon LSS Current-Time (char 0x2006) and Location (char 0x2007) payloads,
 * byte-exact to the decompiled SnapBridge SDK (BleLocationInformationUtility).
 * Sub-minutes are DECIMAL bytes 0..99 (hundredths & ten-thousandths of a minute).
 */
object LocationEncoder {

    private fun utcCal(epochMillis: Long): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = epochMillis }

    /** char 0x2006, 10 bytes. */
    fun buildTime(epochMillis: Long = System.currentTimeMillis()): ByteArray {
        val c = utcCal(epochMillis)
        return ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(c.get(Calendar.YEAR).toShort())
            put((c.get(Calendar.MONTH) + 1).toByte())
            put(c.get(Calendar.DAY_OF_MONTH).toByte())
            put(c.get(Calendar.HOUR_OF_DAY).toByte())
            put(c.get(Calendar.MINUTE).toByte())
            put(c.get(Calendar.SECOND).toByte())
            put(0); put(1); put(0)
        }.array()
    }

    private fun dms(coord: Double, pos: Int, neg: Int): ByteArray {
        val ref = if (coord >= 0) pos else neg
        val a = abs(coord)
        val deg = floor(a).toInt()
        val dm = (a - deg) * 60.0
        val minute = floor(dm).toInt()
        val x = (dm - minute) * 100.0
        val s1 = floor(x).toInt()
        val s2 = floor((x - s1) * 100.0).toInt()
        return byteArrayOf(ref.toByte(), deg.toByte(), minute.toByte(), s1.toByte(), s2.toByte())
    }

    /** char 0x2007, 41 bytes. lat/lon decimal degrees, alt metres. satellites optional. */
    fun buildLocation(
        lat: Double, lon: Double, altM: Double,
        epochMillis: Long = System.currentTimeMillis(), satellites: Int = 0
    ): ByteArray {
        val c = utcCal(epochMillis)
        val altRef = if (altM >= 0) 0x50 else 0x4d
        val subSec = ((c.get(Calendar.MILLISECOND)) / 10) and 0xFF
        val buf = ByteBuffer.allocate(41).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x7f); buf.put(0x00)
        buf.put(dms(lat, 0x4e, 0x53))           // 'N' / 'S'
        buf.put(dms(lon, 0x45, 0x57))           // 'E' / 'W'
        buf.put(satellites.toByte()); buf.put(altRef.toByte())
        buf.putShort(abs(altM).toInt().toShort())
        buf.putShort(c.get(Calendar.YEAR).toShort())
        buf.put((c.get(Calendar.MONTH) + 1).toByte()); buf.put(c.get(Calendar.DAY_OF_MONTH).toByte())
        buf.put(c.get(Calendar.HOUR_OF_DAY).toByte()); buf.put(c.get(Calendar.MINUTE).toByte())
        buf.put(c.get(Calendar.SECOND).toByte())
        buf.put(subSec.toByte()); buf.put(0x01)  // posSubSec, validGpsData
        val datum = byteArrayOf('W'.code.toByte(), 'G'.code.toByte(), 'S'.code.toByte(),
            '-'.code.toByte(), '8'.code.toByte(), '4'.code.toByte())
        buf.put(datum); buf.put(ByteArray(16 - datum.size))
        return buf.array()
    }
}
