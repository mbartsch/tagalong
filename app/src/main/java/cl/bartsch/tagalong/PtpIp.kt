package cl.bartsch.tagalong

import android.net.Network
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Standard PTP/IP (ISO 15740) client for image download, matching SnapBridge's
 * ConnectWifiAction (port 15740, GUID, Init-Command/Event handshake). Sockets are created
 * through the camera [Network] so traffic routes over the camera AP.
 */
class PtpIp(private val network: Network, private val host: String = "192.168.1.1", private val port: Int = 15740,
           private val log: (String) -> Unit = {}) {

    data class ObjectInfo(val handle: Int, val format: Int, val size: Long, val w: Int, val h: Int, val name: String)

    private lateinit var cmd: Socket
    private lateinit var out: OutputStream
    private lateinit var inp: DataInputStream
    private var evt: Socket? = null
    private var txid = 0

    // packet types
    private val INIT_CMD_REQ=1; private val INIT_CMD_ACK=2; private val INIT_EVT_REQ=3; private val INIT_EVT_ACK=4
    private val OP_REQ=6; private val OP_RES=7; private val EVENT=8; private val START_DATA=9; private val DATA=10; private val END_DATA=12
    private val RC_OK=0x2001
    val FMT_ASSOCIATION=0x3001

    private fun le(vararg ints: Int) = ByteBuffer.allocate(ints.size*4).order(ByteOrder.LITTLE_ENDIAN)
        .apply { ints.forEach { putInt(it) } }.array()

    private fun sendPacket(type: Int, payload: ByteArray) {
        val b = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(payload.size + 8); b.putInt(type); b.put(payload)
        out.write(b.array()); out.flush()
    }
    private fun recvN(n: Int): ByteArray { val a = ByteArray(n); inp.readFully(a); return a }
    private fun recvPacket(): Pair<Int, ByteArray> {
        val hdr = ByteBuffer.wrap(recvN(8)).order(ByteOrder.LITTLE_ENDIAN)
        val len = hdr.int; val type = hdr.int
        return Pair(type, if (len > 8) recvN(len - 8) else ByteArray(0))
    }

    fun connect(friendly: String = "Android Device") {
        cmd = network.socketFactory.createSocket().apply { soTimeout = 15000 }
        cmd.connect(InetSocketAddress(host, port), 15000)
        log("ptp tcp connected $host:$port")
        out = cmd.getOutputStream(); inp = DataInputStream(cmd.getInputStream())
        // Fixed GUID used by SnapBridge's ConnectWifiAction (camera may reject unknown GUIDs).
        val guid = byteArrayOf(0x00,0x11,0x22,0x33,0x44,0x55,0x66,0x77,
            0x88.toByte(),0x99.toByte(),0xAA.toByte(),0xBB.toByte(),0xCC.toByte(),0xDD.toByte(),0xEE.toByte(),0xFF.toByte())
        val name = (friendly.toByteArray(Charsets.UTF_16LE)) + byteArrayOf(0,0)
        sendPacket(INIT_CMD_REQ, guid + name + le(0x00010000))
        log("ptp init sent, awaiting ACK ...")
        val (t, pl) = recvPacket()
        check(t == INIT_CMD_ACK) { "InitCommand failed: type $t" }
        val connNo = ByteBuffer.wrap(pl).order(ByteOrder.LITTLE_ENDIAN).int
        log("ptp init ACK connNo=$connNo")
        // Event connection: InitEventRequest = [len=12][type=3][connNo]; then read InitEventAck.
        // (The camera drops the command connection if this isn't a valid event handshake.)
        try {
            val e = network.socketFactory.createSocket().apply { soTimeout = 15000 }
            e.connect(InetSocketAddress(host, port), 15000)
            evt = e
            val eo = e.getOutputStream()
            val eb = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(12).putInt(INIT_EVT_REQ).putInt(connNo)
            eo.write(eb.array()); eo.flush()
            val ei = DataInputStream(e.getInputStream())
            val eh = ByteArray(8); ei.readFully(eh)
            val etype = ByteBuffer.wrap(eh).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
            log("ptp event ACK type=$etype")
        } catch (ex: Exception) { log("ptp event conn failed: ${ex.javaClass.simpleName}") }
    }

    /** Run one PTP transaction. Data-phase bytes go to [sink] if given (streaming; the returned array is
     *  then empty), otherwise they are buffered and returned. */
    private fun transaction(opcode: Int, params: IntArray, sink: OutputStream? = null): Pair<Int, ByteArray> {
        txid++
        val req = ByteBuffer.allocate(10 + params.size*4).order(ByteOrder.LITTLE_ENDIAN)
        req.putInt(1); req.putShort(opcode.toShort()); req.putInt(txid); params.forEach { req.putInt(it) }
        sendPacket(OP_REQ, req.array())
        val data = if (sink == null) java.io.ByteArrayOutputStream() else null
        while (true) {
            val (t, pl) = recvPacket()
            when (t) {
                START_DATA -> {}
                DATA, END_DATA -> (sink ?: data!!).write(pl, 4, pl.size - 4)
                OP_RES -> return Pair(ByteBuffer.wrap(pl).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF, data?.toByteArray() ?: ByteArray(0))
                EVENT -> {}
                else -> throw IllegalStateException("unexpected packet type $t")
            }
        }
    }
    private fun op(opcode: Int, vararg params: Int): ByteArray {
        val (rc, data) = transaction(opcode, params)
        check(rc == RC_OK) { "PTP op 0x%04X failed: rc=0x%04X".format(opcode, rc) }
        return data
    }

    private fun opTo(opcode: Int, sink: OutputStream, vararg params: Int) {
        val (rc, _) = transaction(opcode, params, sink)
        check(rc == RC_OK) { "PTP op 0x%04X failed: rc=0x%04X".format(opcode, rc) }
    }

    fun openSession(sid: Int = 1) { op(0x1002, sid) }
    fun closeSession() { runCatching { op(0x1003) } }

    fun getObjectHandles(storage: Int = -1, fmt: Int = 0, assoc: Int = 0): IntArray {
        val d = ByteBuffer.wrap(op(0x1007, storage, fmt, assoc)).order(ByteOrder.LITTLE_ENDIAN)
        val n = d.int; return IntArray(n) { d.int }
    }
    fun getObjectInfo(handle: Int): ObjectInfo {
        val d = op(0x1008, handle); val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val storage = b.int; val format = b.short.toInt() and 0xFFFF; b.short
        val size = b.int.toLong() and 0xFFFFFFFFL
        b.position(26); val w = b.int; val h = b.int
        val nChars = d[52].toInt() and 0xFF
        val name = if (nChars == 0) "" else String(d, 53, (nChars - 1) * 2, Charsets.UTF_16LE)
        return ObjectInfo(handle, format, size, w, h, name)
    }
    fun getObject(handle: Int): ByteArray = op(0x1009, handle)
    fun getThumb(handle: Int): ByteArray = op(0x100A, handle)
    /** Stream a whole object to [sink] without holding it in memory. */
    fun getObjectTo(handle: Int, sink: OutputStream) = opTo(0x1009, sink, handle)
    /** Stream [len] bytes starting at [offset] to [sink]. */
    fun getPartialObjectTo(handle: Int, offset: Long, len: Int, sink: OutputStream) =
        opTo(0x101B, sink, handle, offset.toInt(), len)
    /** Standard GetPartialObject (0x101B): [len] bytes of the object starting at [offset]. */
    fun getPartialObject(handle: Int, offset: Long, len: Int): ByteArray = op(0x101B, handle, offset.toInt(), len)
    /** Nikon vendor op GetLargeThumb (0x90C4): the full-size embedded JPEG preview, no NEF download. */
    fun getLargeThumb(handle: Int): ByteArray = op(0x90C4, handle)

    fun close() { runCatching { evt?.close() }; runCatching { cmd.close() } }
}
