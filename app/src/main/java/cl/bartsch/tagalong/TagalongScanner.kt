package cl.bartsch.tagalong

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * BLE discovery + OS bonding for the Nikon D780, so the camera can be found and paired
 * from the app instead of through Android Bluetooth settings. The LSS client identity is
 * generated and persisted by Identity and registered during pairing-mode auth.
 */
@SuppressLint("MissingPermission")
class TagalongScanner(private val context: Context, private val log: (String) -> Unit = {}) {

    private val btm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    /** The camera this app is currently set up for (see [Profiles]). */
    private val profile get() = Profiles.active(context)

    /** True if [addr]/[name] belong to the active camera. */
    private fun mine(addr: String?, name: String?): Boolean {
        val p = profile
        return (p.mac.isNotBlank() && addr.equals(p.mac, ignoreCase = true)) || matches(name)
    }

    /** The active camera's bonded record, if any (the D780 bonds under a different address than
     *  its public MAC, so this matches by name as well). */
    fun bondedDevice(): BluetoothDevice? =
        btm.adapter.bondedDevices.firstOrNull { mine(it.address, it.name) }

    fun isBonded(): Boolean = bondedDevice() != null

    /**
     * Remove every bonded record for the camera (by MAC or Nikon name). Failed pairings leave
     * stale BR/EDR bond shells with no LE link key that block a clean re-pair; clearing them lets
     * the next connect establish a proper LE bond. removeBond() is a hidden API, called by
     * reflection (no root needed).
     */
    fun removeStaleBonds(): Int {
        var n = 0
        for (d in btm.adapter.bondedDevices) {
            if (mine(d.address, d.name)) {
                val ok = runCatching { d.javaClass.getMethod("removeBond").invoke(d) as Boolean }
                    .getOrDefault(false)
                log("removeBond ${d.address} ${d.name ?: ""} -> $ok")
                if (ok) n++
            }
        }
        return n
    }

    /** Exact advertised-name match once the profile knows its camera's name; until then (new
     *  profile) fall back to the Nikon naming pattern so the first scan can learn it. */
    private fun matches(name: String?): Boolean {
        val n = name ?: return false
        val bt = profile.btName
        return if (bt.isNotBlank()) n.equals(bt, ignoreCase = true)
               else n.startsWith("AAAA") || n.contains("801")
    }

    /** Scan until a Nikon camera is seen (by name prefix or LSS service UUID), or timeout. */
    suspend fun scan(timeoutMs: Long = 15_000): BluetoothDevice {
        val scanner = btm.adapter.bluetoothLeScanner ?: error("BLE scanner unavailable (adapter off?)")
        val found = CompletableDeferred<BluetoothDevice>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device
                val prof = profile
                val byMac = prof.mac.isNotBlank() && dev.address.equals(prof.mac, ignoreCase = true)
                // In scan results device.name is often null; the advertised name is in scanRecord.
                // Adv names can arrive NUL-padded ("AAAA_0000000\u0000\u0000...").
                val advName = result.scanRecord?.deviceName?.trimEnd('\u0000')?.trim()
                val byUuid = result.scanRecord?.serviceUuids?.any { it.uuid == TagalongBle.LSS_SERVICE } ?: false
                // The LSS UUID alone would pick any D780 in range, so only trust it while the
                // profile has no name yet.
                if ((byMac || matches(dev.name) || matches(advName) || (byUuid && prof.btName.isBlank())) && !found.isCompleted) {
                    log("found ${advName ?: dev.name ?: "?"} ${dev.address}")
                    Profiles.update(context, prof.copy(
                        btName = prof.btName.ifBlank { advName ?: dev.name ?: "" }, lastAddr = dev.address))
                    found.complete(dev)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                if (!found.isCompleted) found.completeExceptionally(IllegalStateException("scan failed: $errorCode"))
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        log("scanning for camera ...")
        scanner.startScan(null, settings, cb)
        try {
            return withTimeout(timeoutMs) { found.await() }
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
    }

    /**
     * Full Bluetooth-Classic pairing, which is what makes the D780 leave its pairing menu.
     * SnapBridge (BtcPairingUseCase) finds the camera via CLASSIC discovery and calls
     * createBond() on it — a separate bond from the LE/LSS path. The camera shows a numeric
     * code; confirm it on the camera (and on the phone if a dialog appears). Any stale bond is
     * removed first. Run this once, with the camera in pairing mode, after the LE sync.
     */
    suspend fun pairClassic(discoverMs: Long = 20_000, bondMs: Long = 40_000): BluetoothDevice {
        // Clear a stale/half bond so createBond starts fresh (matches SnapBridge).
        bondedDevice()
            ?.let { runCatching { it.javaClass.getMethod("removeBond").invoke(it) } }

        val target = CompletableDeferred<BluetoothDevice>()
        val bonded = CompletableDeferred<Boolean>()
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val nm = d?.name
                        if (d != null && mine(d.address, nm) && !target.isCompleted) {
                            log("BTC found ${nm ?: "?"} ${d.address}")
                            target.complete(d)
                        }
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        if (d != null && mine(d.address, d.name))
                            when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                                BluetoothDevice.BOND_BONDED -> bonded.complete(true)
                                BluetoothDevice.BOND_NONE -> if (!bonded.isCompleted) bonded.complete(false)
                            }
                    }
                }
            }
        }
        context.registerReceiver(rx, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        })
        try {
            log("BTC classic discovery ...")
            btm.adapter.startDiscovery()
            val dev = try { withTimeout(discoverMs) { target.await() } }
                      finally { runCatching { btm.adapter.cancelDiscovery() } }
            log("BTC createBond ${dev.address} — confirm the code on the camera")
            check(dev.createBond()) { "classic createBond() rejected" }
            check(withTimeout(bondMs) { bonded.await() }) { "classic bonding failed/cancelled" }
            log("BTC bonded — camera should leave pairing mode")
            return dev
        } finally {
            runCatching { context.unregisterReceiver(rx) }
        }
    }

    /** Create an OS bond with the device if not already bonded; suspend until done. */
    suspend fun ensureBonded(device: BluetoothDevice, timeoutMs: Long = 30_000): BluetoothDevice {
        if (device.bondState == BluetoothDevice.BOND_BONDED) { log("already bonded"); return device }

        val bonded = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                    BluetoothDevice.BOND_BONDED -> bonded.complete(true)
                    BluetoothDevice.BOND_NONE   -> if (!bonded.isCompleted) bonded.complete(false)
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        try {
            log("pairing with ${device.name ?: device.address} (put camera in pairing mode) ...")
            check(device.createBond()) { "createBond() rejected" }
            val ok = withTimeout(timeoutMs) { bonded.await() }
            check(ok) { "bonding failed/cancelled" }
            log("bonded")
            return device
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
