package cl.bartsch.tagalong

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * BLE client for the Nikon D780 LSS service: connect, enable CCCs, authenticate,
 * and write time + location. Operations are serialized (one GATT op in flight).
 */
@SuppressLint("MissingPermission")
class TagalongBle(
    private val context: Context,
    private val clientId: ByteArray,
    private val log: (String) -> Unit = {},
) {
    init { require(clientId.size == 8) { "clientId must be 8 bytes" } }

    companion object {
        private fun lss(x: String) = UUID.fromString("0000$x-3DD4-4255-8D62-6DC7B9BD5561")
        val LSS_SERVICE = lss("DE00")
        val AUTH = lss("2000"); val CLIENT_NAME = lss("2002"); val TIME = lss("2006")
        val LOCATION = lss("2007"); val CTRL_PT = lss("2008"); val CABLE = lss("200a")
        val STATUS_CTL = lss("2020"); val CTRL_FORCTL = lss("2021")
        val CONN_CONFIG = lss("2004")   // 0x4b: encrypted Wi-Fi SSID/PSK
        val CONN_ESTAB = lss("2005")    // 0x4d: write 0x02 to start the camera Wi-Fi AP
        val CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val CLIENT_NAME_STR = "Android_SM-S721B_3378"
    }

    private var gatt: BluetoothGatt? = null
    @Volatile private var connected = false
    private var opAck: CompletableDeferred<Boolean>? = null
    private val authInd = Channel<ByteArray>(Channel.UNLIMITED)
    private var readAck: CompletableDeferred<ByteArray?>? = null

    // Last auth session params, for offline validation of the session cipher (see dumpWifiConfig).
    private var lastNc: ByteArray = ByteArray(0)
    private var lastNs: ByteArray = ByteArray(0)
    private var lastS4: ByteArray = ByteArray(0)
    private var lastIdx: Int = -1

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            connected = newState == BluetoothProfile.STATE_CONNECTED
            if (connected) g.discoverServices()
            else opAck?.complete(false)
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            opAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            opAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            opAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
        @Deprecated("compat")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid == AUTH) authInd.trySend(c.value.clone())
        }
        @Deprecated("compat")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            readAck?.complete(if (status == BluetoothGatt.GATT_SUCCESS) c.value.clone() else null)
        }
    }

    suspend fun connect(device: BluetoothDevice, autoConnect: Boolean = false, connectMs: Long = 20_000) {
        opAck = CompletableDeferred()
        // autoConnect=true queues the link and waits for the camera to advertise (handles
        // intermittent/directed advertising); autoConnect=false is a one-shot direct connect.
        gatt = device.connectGatt(context, autoConnect, cb, BluetoothDevice.TRANSPORT_LE)
        check(await(connectMs)) { "connect/discover failed (camera not advertising/connectable)" }
        log("services discovered")
        for (u in listOf(CTRL_PT, CABLE, STATUS_CTL, CTRL_FORCTL)) enableCcc(u, indicate = u != CTRL_PT && u != CABLE)
        enableCcc(AUTH, indicate = true)
    }

    private fun charOf(u: UUID): BluetoothGattCharacteristic =
        gatt!!.services.firstNotNullOf { s -> s.getCharacteristic(u) }

    private suspend fun enableCcc(u: UUID, indicate: Boolean) {
        val c = charOf(u)
        gatt!!.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCC)
        @Suppress("DEPRECATION")
        d.value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                  else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        opAck = CompletableDeferred()
        @Suppress("DEPRECATION") gatt!!.writeDescriptor(d)
        // Generous cap: the first encrypted op triggers OS bonding and blocks on the pair dialog.
        check(await(45_000)) { "CCC write failed for $u" }
    }

    private suspend fun write(u: UUID, data: ByteArray, withResponse: Boolean = true) {
        check(connected) { "BLE link is down" }
        val c = charOf(u)
        @Suppress("DEPRECATION")
        c.writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                      else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        @Suppress("DEPRECATION") c.value = data
        opAck = CompletableDeferred()
        @Suppress("DEPRECATION") check(gatt!!.writeCharacteristic(c)) { "write not accepted for $u" }
        check(await(45_000)) { "write failed for $u" }
    }

    private suspend fun await(ms: Long): Boolean = withTimeout(ms) { opAck!!.await() }

    suspend fun authenticate(): Int {
        val nc = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }
        write(AUTH, byteArrayOf(0x01) + nc + clientId)
        val s2 = withTimeout(10_000) { authInd.receive() }
        require(s2[0].toInt() == 0x02) { "stage2 unexpected: ${s2[0]}" }
        val (proof, idx) = LssCrypto.respond(nc, s2.copyOfRange(1, 9), s2.copyOfRange(9, 17))
        write(AUTH, byteArrayOf(0x03) + nc + proof)
        val s4 = withTimeout(10_000) { authInd.receive() }
        check(s4[0].toInt() == 0x04) { "auth failed: ${s4[0]}" }
        lastNc = nc; lastNs = s2.copyOfRange(1, 9); lastS4 = s4.clone(); lastIdx = idx
        log("authenticated (idx=$idx)")
        return idx
    }

    private suspend fun read(u: UUID): ByteArray? {
        val c = charOf(u)
        readAck = CompletableDeferred()
        gatt!!.readCharacteristic(c)
        return withTimeout(10_000) { readAck!!.await() }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    data class WifiCreds(val ssid: String, val psk: String, val security: Int)

    /** Read char 0x4b and decrypt the camera's Wi-Fi AP credentials with the session cipher.
     *  Layout (AbstractC1669k): [0]=flags [1:33]=SSID(enc) [33:97]=PSK(enc) [97]=security. */
    suspend fun readWifiConfig(): WifiCreds {
        val blob = read(CONN_CONFIG) ?: error("0x4b read failed")
        require(blob.size >= 98) { "0x4b too short (${blob.size})" }
        // stage4 field for key derivation is the second 8 bytes of the stage-4 frame.
        val stage4 = lastS4.copyOfRange(9, 17)
        val key = LssCrypto.generateKey(stage4, clientId, lastNc, lastNs, lastIdx)
        fun str(b: ByteArray): String {
            val plain = LssCrypto.decodeConfig(key, b)
            val end = plain.indexOf(0).let { if (it < 0) plain.size else it }
            return String(plain, 0, end, Charsets.UTF_8)
        }
        val creds = WifiCreds(str(blob.copyOfRange(1, 33)), str(blob.copyOfRange(33, 97)), blob[97].toInt())
        val sane = creds.ssid.isNotEmpty() && creds.ssid.all { it.code in 32..126 }
        if (!sane) {
            // dump everything for offline analysis of the decode failure
            log("0x4b DECODE SUSPECT len=${blob.size} hex=${blob.hex()}")
            log("session nc=${lastNc.hex()} ns=${lastNs.hex()} idx=$lastIdx id=${clientId.hex()} s4=${lastS4.hex()}")
            error("Wi-Fi SSID decode failed (idx=$lastIdx) — power-cycle the camera and retry")
        }
        log("wifi ssid=${creds.ssid} psk=${creds.psk} security=${creds.security}")
        return creds
    }

    /** Tell the camera to start its Wi-Fi AP. Establishment byte bit0=Wi-Fi (0x01), bit1=BT. */
    suspend fun startWifiAp() { write(CONN_ESTAB, byteArrayOf(0x01)); log("camera Wi-Fi AP starting") }

    suspend fun syncTimeAndLocation(lat: Double, lon: Double, altM: Double) {
        write(CLIENT_NAME, CLIENT_NAME_STR.toByteArray().copyOf(32))
        write(TIME, LocationEncoder.buildTime())
        write(LOCATION, LocationEncoder.buildLocation(lat, lon, altM))
        log("time + location written")
    }

    /** Refresh write on an already-authenticated link (client name was sent by the first sync). */
    suspend fun writeTimeAndLocation(lat: Double, lon: Double, altM: Double) {
        write(TIME, LocationEncoder.buildTime())
        write(LOCATION, LocationEncoder.buildLocation(lat, lon, altM))
    }

    fun close() { @Suppress("DEPRECATION") gatt?.close(); gatt = null }
}
