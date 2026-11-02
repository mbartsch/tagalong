package cl.bartsch.tagalong

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun App() {
    val ctx = LocalContextSafe()
    val scope = rememberCoroutineScope()
    val logLines = remember { mutableStateListOf("ready") }
    fun stamp(s: String) = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  " + s
    fun log(s: String) { android.util.Log.i("Tagalong", s); logLines.add(stamp(s)) }

    val perms = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
    }.toTypedArray()
    // Is the camera's MAC already BT-bonded? Drives whether "Pair camera (BT)" is promoted to the
    // main screen (not bonded yet) or tucked away in the overflow menu (already paired).
    var bonded by remember { mutableStateOf(true) }
    fun refreshBonded() { bonded = runCatching { TagalongScanner(ctx).isBonded() }.getOrDefault(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { refreshBonded() }
    LaunchedEffect(Unit) {
        if (perms.any { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED })
            permLauncher.launch(perms)
        else refreshBonded()
    }

    // Periodic time+GPS refresh over one held BLE link. Interval is persisted.
    val prefs = remember { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var intervalSec by remember { mutableStateOf(prefs.getInt("sync_interval_s", 10)) }
    val autoRunning by SyncService.running.collectAsState()
    LaunchedEffect(Unit) { SyncService.logs.collect { logLines.add(stamp(it)) } }

    var profiles by remember { mutableStateOf(Profiles.list(ctx)) }
    var activeId by remember { mutableStateOf(Profiles.active(ctx).id) }
    var identityHex by remember { mutableStateOf(Identity.clientIdHex(ctx)) }
    var session by remember { mutableStateOf<CameraSession?>(null) }   // live photo-browsing connection
    var opening by remember { mutableStateOf(false) }
    fun reloadProfiles() {
        profiles = Profiles.list(ctx); activeId = Profiles.active(ctx).id
        identityHex = Identity.clientIdHex(ctx); refreshBonded()
    }
    fun cameraInfo(): String {
        val p = Profiles.active(ctx)
        val bd = runCatching { TagalongScanner(ctx).bondedDevice() }.getOrNull()
        return listOf(
            "Profile" to p.name,
            "Bluetooth name" to p.btName.ifBlank { "(learned on first scan)" },
            "Camera MAC" to p.mac.ifBlank { "(not set)" },
            "Last BLE address" to p.lastAddr.ifBlank { "(not seen yet)" },
            "Bonded (classic BT)" to (bd?.let { "yes, ${it.address}" } ?: "no"),
            "Client ID" to p.clientIdHex,
            "Client name" to TagalongBle.CLIENT_NAME_STR,
        ).joinToString("\n") { (k, v) -> "$k: $v" }
    }
    var ssid by remember { mutableStateOf("") }
    var psk by remember { mutableStateOf("") }

    val photos = session
    if (photos != null) {
        PhotosScreen(photos, onClose = { session = null; scope.launch { photos.close() } }, log = { log(it) })
        return
    }

    ScreenContent(
        buildStamp = buildStamp(ctx),
        identityHex = identityHex,
        ssid = ssid,
        psk = psk,
        logLines = logLines,
        onSsidChange = { ssid = it },
        onPskChange = { psk = it },
        onNewIdentity = {
            identityHex = Identity.newClientId(ctx).joinToString("") { "%02x".format(it) }
            log("new identity $identityHex — put camera in pairing mode, then Connect & Sync to register")
        },
        onForgetBonds = {
            scope.launch {
                val n = withContext(Dispatchers.IO) { TagalongScanner(ctx) { log(it) }.removeStaleBonds() }
                log("removed $n stale bond(s); now put camera in pairing mode and Connect & Sync")
                refreshBonded()
            }
        },
        onPairClassic = {
            scope.launch {
                // First-time flow: BLE connect + auth (registers this identity on the camera) and
                // sync time/location, THEN the classic-BT bond.
                val ble = TagalongBle(ctx, Identity.clientId(ctx)) { log(it) }
                val scanner = TagalongScanner(ctx) { log(it) }
                try {
                    val loc = lastKnownLocation(ctx)
                    if (loc == null) log("no GPS fix yet — registering without location sync")
                    withContext(Dispatchers.IO) {
                        val dev = scanner.scan()
                        ble.connect(dev)
                        ble.authenticate()
                        if (loc != null) ble.syncTimeAndLocation(loc.first, loc.second, loc.third)
                        ble.close()
                        scanner.pairClassic()
                    }
                } catch (e: Exception) { log("ERR ${e.message}") } finally { ble.close() }
                refreshBonded()
            }
        },
        onConnectSync = {
            scope.launch {
                val ble = TagalongBle(ctx, Identity.clientId(ctx)) { log(it) }
                val scanner = TagalongScanner(ctx) { log(it) }
                try {
                    val loc = lastKnownLocation(ctx) ?: run { log("no GPS fix yet"); return@launch }
                    log("fix ${loc.first}, ${loc.second}, ${loc.third} m")
                    withContext(Dispatchers.IO) {
                        // Scan first: this both confirms the camera is actually advertising and
                        // gives us a device object that is connectable right now. If the scan
                        // times out, the camera is not discoverable (asleep / not in pairing mode
                        // / still registered to this phone on the camera side).
                        val dev = scanner.scan()
                        ble.connect(dev)
                        ble.authenticate()
                        ble.syncTimeAndLocation(loc.first, loc.second, loc.third)
                    }
                } catch (e: Exception) { log("ERR ${e.message}") } finally { ble.close() }
            }
        },
        onAutoWifi = {
            scope.launch {
                opening = true
                val s = CameraSession(ctx) { log(it) }
                try { s.open(); session = s }
                catch (e: Exception) { log("ERR ${e.javaClass.simpleName}: ${e.message}"); s.close() }
                finally { opening = false }
            }
        },
        onJoinWifi = {
            scope.launch {
                val wifi = WifiCamera(ctx) { log(it) }
                try {
                    log("joining $ssid ...")
                    val net = wifi.join(ssid, psk)
                    withContext(Dispatchers.IO) {
                        val cam = PtpIp(net)
                        cam.connect(); cam.openSession()
                        val handles = cam.getObjectHandles()
                        log("${handles.size} objects on card")
                        handles.take(10).forEach { h ->
                            val i = cam.getObjectInfo(h)
                            if (i.format != cam.FMT_ASSOCIATION) log("  ${i.name} ${i.size}B ${i.w}x${i.h}")
                        }
                        cam.closeSession(); cam.close()
                    }
                } catch (e: Exception) { log("ERR ${e.message}") } finally { wifi.release() }
            }
        },
        onClearLog = { logLines.clear() },
        pairOnMain = !bonded,
        photosBusy = opening,
        profiles = profiles,
        activeId = activeId,
        infoProvider = { cameraInfo() },
        onSelectProfile = { Profiles.setActive(ctx, it); reloadProfiles(); log("camera: ${Profiles.active(ctx).name}") },
        onAddProfile = { n, bt, mac ->
            val np = Profiles.add(ctx, n, bt, mac); Profiles.setActive(ctx, np.id); reloadProfiles()
            log("added camera ${np.name} — put it in pairing mode and tap Pair camera")
        },
        onDeleteProfile = { id ->
            if (Profiles.remove(ctx, id)) { reloadProfiles(); log("camera profile removed") }
            else log("cannot remove the only camera profile")
        },
        intervalSec = intervalSec,
        autoRunning = autoRunning,
        onSetInterval = { intervalSec = it; prefs.edit().putInt("sync_interval_s", it).apply(); log("auto-sync interval ${fmtInterval(it)}") },
        onToggleAutoSync = {
            if (autoRunning) SyncService.stop(ctx) else SyncService.start(ctx)
        },
    )
}

/** Stateless screen UI — all state and actions passed in, so it is @Preview-able. */
@Composable
fun ScreenContent(
    buildStamp: String,
    identityHex: String,
    ssid: String,
    psk: String,
    logLines: List<String>,
    onSsidChange: (String) -> Unit,
    onPskChange: (String) -> Unit,
    onNewIdentity: () -> Unit,
    onForgetBonds: () -> Unit,
    onPairClassic: () -> Unit,
    onConnectSync: () -> Unit,
    onAutoWifi: () -> Unit,
    onJoinWifi: () -> Unit,
    onClearLog: () -> Unit = {},
    pairOnMain: Boolean = false,
    photosBusy: Boolean = false,
    intervalSec: Int = 10,
    autoRunning: Boolean = false,
    onSetInterval: (Int) -> Unit = {},
    onToggleAutoSync: () -> Unit = {},
    profiles: List<Profile> = emptyList(),
    activeId: String = "",
    infoProvider: () -> String = { "" },
    onSelectProfile: (String) -> Unit = {},
    onAddProfile: (String, String, String) -> Unit = { _, _, _ -> },
    onDeleteProfile: (String) -> Unit = {},
) {
    var showInterval by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showCameras by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showCreds by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxSize(),
           verticalArrangement = Arrangement.spacedBy(8.dp)) {

        // Header: title + build/identity, with maintenance actions tucked into an overflow menu.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Tagalong" + (profiles.firstOrNull { it.id == activeId }?.let { " · ${it.name}" } ?: "  No Profile Selected"),
                    style = MaterialTheme.typography.titleLarge)
                Text("$buildStamp · id $identityHex", style = MaterialTheme.typography.bodySmall)
            }
            Box {
                TextButton(onClick = { menuOpen = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Camera info…") },
                        onClick = { menuOpen = false; showInfo = true })
                    DropdownMenuItem(text = { Text("Cameras…") },
                        onClick = { menuOpen = false; showCameras = true })
                    DropdownMenuItem(text = { Text("Manual Wi-Fi credentials…") },
                        onClick = { menuOpen = false; showCreds = true })
                    DropdownMenuItem(text = { Text("Auto-sync interval: ${fmtInterval(intervalSec)}…") },
                        onClick = { menuOpen = false; showInterval = true })
                    DropdownMenuItem(text = { Text("Clear log") },
                        onClick = { menuOpen = false; onClearLog() })
                    HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
                    DropdownMenuItem(text = { Text("New identity") },
                        onClick = { menuOpen = false; onNewIdentity() })
                    DropdownMenuItem(text = { Text("Forget pairings") },
                        onClick = { menuOpen = false; onForgetBonds() })
                }
            }
        }

        // Until the camera is BT-bonded, pairing is the only thing you can do: the two main
        // actions stay greyed out so the first-time flow is unambiguous.
        if (pairOnMain) {
            Button(onClick = onPairClassic, Modifier.fillMaxWidth()) {
                Text("Pair camera (BT) — do this first")
            }
            Text("Camera not paired yet. Put it in pairing mode (SETUP ▸ Connect to smart device) " +
                 "and tap Pair camera.", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onConnectSync, Modifier.fillMaxWidth(), enabled = !pairOnMain && !autoRunning) {
            Text("Connect & Sync Time + GPS")
        }
        Button(onClick = onToggleAutoSync, Modifier.fillMaxWidth(), enabled = !pairOnMain) {
            Text(if (autoRunning) "Stop auto-sync" else "Auto-sync every ${fmtInterval(intervalSec)}")
        }
        Button(onClick = onAutoWifi, Modifier.fillMaxWidth(), enabled = !pairOnMain && !autoRunning && !photosBusy) {
            Text(if (photosBusy) "Connecting to camera ..." else "Photos (Wi-Fi)")
        }

        HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            logLines.reversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    if (showInfo) CameraInfoDialog(infoProvider(), onClose = { showInfo = false })
    if (showCameras) CamerasDialog(profiles, activeId, locked = autoRunning,
        onSelect = { onSelectProfile(it); showCameras = false }, onDelete = onDeleteProfile,
        onAdd = { showCameras = false; showAdd = true }, onClose = { showCameras = false })
    if (showAdd) AddCameraDialog(onAdd = { n, bt, mac -> showAdd = false; onAddProfile(n, bt, mac) },
        onCancel = { showAdd = false })
    if (showInterval) IntervalDialog(intervalSec, onSet = { onSetInterval(it); showInterval = false },
        onClose = { showInterval = false })
    if (showCreds) ManualWifiDialog(ssid, psk, onSsidChange, onPskChange,
        onJoin = { showCreds = false; onJoinWifi() }, onCancel = { showCreds = false })
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun ScreenContentPreview() {
    MaterialTheme {
        ScreenContent(
            buildStamp = "2026-11-02 14:30",
            identityHex = "a1b2c3d4e5f60718",
            ssid = "D780_0000000",
            psk = "",
            logLines = listOf("ready", "scanning for camera ...", "found AAAA_0000000", "authenticated (idx=3)"),
            onSsidChange = {}, onPskChange = {}, onNewIdentity = {}, onForgetBonds = {},
            onPairClassic = {}, onConnectSync = {}, onAutoWifi = {}, onJoinWifi = {}, onClearLog = {},
        )
    }
}

internal val SYNC_INTERVALS = listOf(1, 5, 10, 30, 60, 120, 300)

internal fun fmtInterval(s: Int) = if (s >= 60) "${s / 60} min" else "$s s"

private val previewProfiles = listOf(
    Profile("1", "D780", "AAAA_0000000", "AA:BB:CC:DD:EE:FF", "a1b2c3d4e5f60718"),
    Profile("2", "Z6 II", "", "", "0011223344556677"),
)

/** First run: camera not bonded -> Pair is promoted, the other actions are greyed out. */
@Preview(showBackground = true, showSystemUi = true, name = "Not paired")
@Composable
private fun ScreenContentNotPairedPreview() {
    MaterialTheme {
        ScreenContent(
            buildStamp = "2026-11-02 14:30", identityHex = "a1b2c3d4e5f60718", ssid = "", psk = "",
            logLines = listOf("14:30:01  ready"),
            onSsidChange = {}, onPskChange = {}, onNewIdentity = {}, onForgetBonds = {},
            onPairClassic = {}, onConnectSync = {}, onAutoWifi = {}, onJoinWifi = {},
            pairOnMain = true, profiles = previewProfiles, activeId = "1",
        )
    }
}

/** Background auto-sync running: sync/Wi-Fi buttons disabled, toggle reads "Stop". */
@Preview(showBackground = true, showSystemUi = true, name = "Auto-sync running")
@Composable
private fun ScreenContentAutoSyncPreview() {
    MaterialTheme {
        ScreenContent(
            buildStamp = "2026-11-02 14:30", identityHex = "a1b2c3d4e5f60718", ssid = "", psk = "",
            logLines = listOf("14:41:40  synced 41.71646, 2.74215", "14:41:30  auto-sync running, every 10 s"),
            onSsidChange = {}, onPskChange = {}, onNewIdentity = {}, onForgetBonds = {},
            onPairClassic = {}, onConnectSync = {}, onAutoWifi = {}, onJoinWifi = {},
            autoRunning = true, intervalSec = 10, profiles = previewProfiles, activeId = "1",
        )
    }
}

/** APK install time, so the main screen shows which build is actually running. */
private fun buildStamp(ctx: Context): String {
    val t = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        .format(java.util.Date(t))
}

@SuppressLint("MissingPermission")
private fun lastKnownLocation(ctx: Context): Triple<Double, Double, Double>? {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
        runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let {
            return Triple(it.latitude, it.longitude, it.altitude)
        }
    }
    return null
}

@Composable
private fun LocalContextSafe(): Context = androidx.compose.ui.platform.LocalContext.current
