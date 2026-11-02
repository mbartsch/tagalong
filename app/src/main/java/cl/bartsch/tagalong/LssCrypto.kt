package cl.bartsch.tagalong

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Nikon LSS BLE authentication (replaces libLsSec-jni.so Stage1..4).
 * Custom Blowfish "entangle" chain under a hardcoded key; verified byte-exact against
 * three captured D780 sessions. All nonces/proofs are little-endian u64.
 */
object LssCrypto {
    private val H = LssConstants.HEADER1
    private val D = LssConstants.DATA1
    private val SEED = LssConstants.SEED

    private fun f(prev: Int): Int {
        val f0 = D[0][(prev ushr 24) and 0xff]
        val f1 = D[1][(prev ushr 16) and 0xff]
        val f2 = D[2][(prev ushr 8) and 0xff]
        val f3 = D[3][prev and 0xff]
        return f3 + (f2 xor (f1 + f0))
    }

    /** One Blowfish block encrypt. Returns (a, b). */
    private fun entangle2(aIn: Int, bIn: Int): Pair<Int, Int> {
        var a = aIn; var b = bIn
        for (i in 0 until 16) {
            val prev = H[i] xor a
            a = b xor f(prev)
            b = prev
        }
        return Pair(H[17] xor b, H[16] xor a)
    }

    /** CBC-MAC-style chain over 64-bit elements -> 8-byte result as u64. */
    private fun entangle(elements: LongArray): Long {
        var x = 0x01020304; var y = 0x05060708
        for (e in elements) {
            x = Integer.reverseBytes((e and 0xFFFFFFFFL).toInt()) xor x
            y = Integer.reverseBytes((e ushr 32).toInt()) xor y
            val p = entangle2(x, y); x = p.first; y = p.second
        }
        return (Integer.reverseBytes(x).toLong() and 0xFFFFFFFFL) or
               ((Integer.reverseBytes(y).toLong() and 0xFFFFFFFFL) shl 32)
    }

    private fun hash(idx: Int, a: Long, b: Long): Long = entangle(longArrayOf(SEED[idx], a, b))

    private fun u64le(b: ByteArray): Long =
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long

    private fun u64ToLe(v: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()

    /**
     * Given the camera's stage-2 nonce + proof and our stage-1 nonce, return the
     * stage-3 proof (8 bytes) and the matched seed index.
     * @throws IllegalStateException if the camera proof does not verify.
     */
    fun respond(phoneNonce: ByteArray, cameraNonce: ByteArray, cameraProof: ByteArray): Pair<ByteArray, Int> {
        val nc = u64le(phoneNonce); val ns = u64le(cameraNonce); val cp = u64le(cameraProof)
        for (idx in 0 until 8) {
            if (hash(idx, ns, nc) == cp) {
                return Pair(u64ToLe(hash(idx, nc, ns)), idx)
            }
        }
        throw IllegalStateException("camera proof did not verify")
    }

    // --- Session cipher (64-bit), reimplemented from libLsSec-jni.so (populate_key/decode).
    // Distinct from the auth path above (32-bit). Decrypts char 0x4b (Wi-Fi SSID/PSK).
    // Validated against a live D780 read; see app/scratch/CIPHER_RE_NOTES.md.
    private val H2 = LssConstants.HEADER2
    private val D2 = LssConstants.DATA2

    /** Per-session cipher key: 64-bit header[18] + data[4][256]. */
    class Key(val h: LongArray, val d: Array<LongArray>)

    private fun f64(d: Array<LongArray>, v: Long): Long {
        val b0 = (v and 0xff).toInt(); val b1 = ((v ushr 8) and 0xff).toInt()
        val b2 = ((v ushr 16) and 0xff).toInt(); val b3 = ((v ushr 24) and 0xff).toInt()
        var t = d[0][b3] + d[1][b2]
        t = t xor d[2][b1]
        return t + d[3][b0]
    }

    private fun ent2(h: LongArray, d: Array<LongArray>, aIn: Long, bIn: Long): Pair<Long, Long> {
        var a = aIn; var b = bIn
        for (i in 0 until 16) { a = a xor h[i]; val t = f64(d, a); b = b xor t; val x = a; a = b; b = x }
        val x = a; a = b; b = x
        b = b xor h[16]; a = a xor h[17]
        return Pair(a, b)
    }

    private fun unt2(h: LongArray, d: Array<LongArray>, aIn: Long, bIn: Long): Pair<Long, Long> {
        var a = aIn; var b = bIn
        for (i in 17 downTo 2) { a = a xor h[i]; val t = f64(d, a); b = b xor t; val x = a; a = b; b = x }
        val x = a; a = b; b = x
        b = b xor h[1]; a = a xor h[0]
        return Pair(a, b)
    }

    private fun populateKey(seed: Long): Key {
        val sb = u64ToLe(seed)
        val h = LongArray(18)
        var k = 0
        for (i in 0 until 18) {
            var abcd = 0
            repeat(4) { abcd = (abcd shl 8) or (sb[k].toInt() and 0xff); k++; if (k >= 8) k = 0 }
            h[i] = (H2[i].toLong() and 0xFFFFFFFFL) xor (abcd.toLong() and 0xFFFFFFFFL)
        }
        val d = Array(4) { i -> LongArray(256) { j -> D2[i][j].toLong() and 0xFFFFFFFFL } }
        var x = 0L; var y = 0L
        var i = 0
        while (i < 18) { val p = ent2(h, d, x, y); x = p.first; y = p.second; h[i] = x; h[i + 1] = y; i += 2 }
        for (r in 0 until 4) { var j = 0; while (j < 256) { val p = ent2(h, d, x, y); x = p.first; y = p.second; d[r][j] = x; d[r][j + 1] = y; j += 2 } }
        return Key(h, d)
    }

    /** Derive the per-session cipher key after auth. stage4/deviceId are the 8-byte LSS fields. */
    fun generateKey(stage4: ByteArray, deviceId: ByteArray, phoneNonce: ByteArray,
                    cameraNonce: ByteArray, index: Int): Key {
        val nc = u64le(phoneNonce); val ns = u64le(cameraNonce)
        val secret = (((nc shl 32) or (ns and 0xFFFFFFFFL)) and 0xFFFFFFFFFFFFFF00UL.toLong()) or
                     (index.toLong() and 0xff)
        val seed = entangle(longArrayOf(u64le(stage4), u64le(deviceId), secret))
        return populateKey(seed)
    }

    private fun r32be(b: ByteArray, o: Int): Long {
        val v = ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
                ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)
        return v.toLong()   // sign-extends the 32-bit value to 64-bit (matches sub_87ac)
    }

    private fun w32be(out: ByteArray, o: Int, v: Long) {
        out[o] = ((v ushr 24) and 0xff).toByte(); out[o + 1] = ((v ushr 16) and 0xff).toByte()
        out[o + 2] = ((v ushr 8) and 0xff).toByte(); out[o + 3] = (v and 0xff).toByte()
    }

    /** CBC decrypt of a 0x4b cipher field (length must be a multiple of 8). */
    fun decodeConfig(key: Key, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var x = 0L; var y = 0L
        var i = 0
        while (i < data.size) {
            val ea = r32be(data, i); val eb = r32be(data, i + 4)
            val (a, b) = unt2(key.h, key.d, ea, eb)
            w32be(out, i, a xor x); w32be(out, i + 4, b xor y)
            x = ea; y = eb; i += 8
        }
        return out
    }
}
