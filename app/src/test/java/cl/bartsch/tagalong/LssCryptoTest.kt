package cl.bartsch.tagalong

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LssCryptoTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // Nc, Ns, camera_proof, expected phone_proof  (captured D780 sessions)
    private val vectors = listOf(
        listOf("4643c23f177fa3a8", "38d2a0620445904e", "a41f81fd66e88831", "dfc233e6b94161b1"),
        listOf("3106c85c6c8914f8", "5d4397242d935336", "310d3017fd68c98a", "e112d3663418610a"),
        listOf("1ecadd8501eb4b28", "0ea1018412592306", "718b0cfa624eef6f", "e23a35884bf18e4c"),
    )

    @Test fun authVectors() {
        for (v in vectors) {
            val (proof, _) = LssCrypto.respond(hex(v[0]), hex(v[1]), hex(v[2]))
            assertEquals(v[3], hex(proof))
        }
    }

    @Test fun sessionCipherDecodesLiveConfig() {
        // Dummy-value known answer: encrypted with the Python reference cipher (validated against live
        // 0x4b reads), so this checks the Kotlin session cipher against it without real credentials.
        val key = LssCrypto.generateKey(
            hex("2d39373738363000"), hex("0011223344556677"),
            hex("a1a2a3a4a5a6a7a8"), hex("b1b2b3b4b5b6b7b8"), 5)
        val blob = hex("034b5f8fbf34dcf70b478bac8b262e9ab125abb8b459903472d05a07836e861b" +
            "58172b286764b03ff0ea84094281f8540d19718cac9635a1e714cc9ee03c3048" +
            "a2030d432fc7ab9f34da8547b16d8d31b1c82f26f1d53a1f8d4af4065368c525" +
            "0f00000000")
        fun str(b: ByteArray): String {
            val p = LssCrypto.decodeConfig(key, b)
            val end = p.indexOf(0).let { if (it < 0) p.size else it }
            return String(p, 0, end, Charsets.UTF_8)
        }
        assertEquals("EXAMPLE_0000000_Camera", str(blob.copyOfRange(1, 33)))
        assertEquals("EXAMPLEPSK", str(blob.copyOfRange(33, 97)))
    }

    @Test fun locationSubMinutesInRange() {
        // worldwide sweep: every decimal sub-minute byte must be 0..99 (camera rejects >99)
        var lat = -89.9
        while (lat < 90) {
            var lon = -179.9
            while (lon < 180) {
                val p = LocationEncoder.buildLocation(lat, lon, 100.0)
                for (idx in intArrayOf(5, 6, 10, 11)) assertTrue((p[idx].toInt() and 0xff) <= 99)
                lon += 7.3
            }
            lat += 3.7
        }
    }
}
