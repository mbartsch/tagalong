package cl.bartsch.tagalong

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Stateless dialogs used by the main screen's overflow menu; each has a @Preview below. */

@Composable
fun CameraInfoDialog(text: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Camera info") },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
fun CamerasDialog(
    profiles: List<Profile>,
    activeId: String,
    locked: Boolean,                 // auto-sync running: no switching/adding/deleting
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Cameras") },
        text = {
            Column {
                if (locked) Text("Stop auto-sync to switch cameras.", style = MaterialTheme.typography.bodySmall)
                profiles.forEach { pr ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = pr.id == activeId, enabled = !locked, onClick = { onSelect(pr.id) })
                        Column(Modifier.weight(1f)) {
                            Text(pr.name)
                            Text(pr.btName.ifBlank { "name not learned yet" }, style = MaterialTheme.typography.bodySmall)
                        }
                        if (profiles.size > 1) TextButton(enabled = !locked, onClick = { onDelete(pr.id) }) { Text("Delete") }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !locked, onClick = onAdd) { Text("Add camera") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
fun AddCameraDialog(onAdd: (name: String, btName: String, mac: String) -> Unit, onCancel: () -> Unit) {
    var n by remember { mutableStateOf("") }
    var bt by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Add camera") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(n, { n = it }, singleLine = true,
                    label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(bt, { bt = it }, singleLine = true,
                    label = { Text("Bluetooth name (optional)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(mac, { mac = it }, singleLine = true,
                    label = { Text("MAC (optional)") }, modifier = Modifier.fillMaxWidth())
                Text("Leave the Bluetooth name empty to learn it on the first scan. Put the new camera in pairing mode, then tap Pair camera.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = n.isNotBlank(), onClick = { onAdd(n, bt, mac) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
fun IntervalDialog(current: Int, onSet: (Int) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Auto-sync interval") },
        text = {
            Column {
                SYNC_INTERVALS.forEach { s ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = s == current, onClick = { onSet(s) })
                        Text(fmtInterval(s))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
fun ManualWifiDialog(
    ssid: String, psk: String,
    onSsidChange: (String) -> Unit, onPskChange: (String) -> Unit,
    onJoin: () -> Unit, onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Manual Wi-Fi credentials") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(ssid, onSsidChange, singleLine = true,
                    label = { Text("Camera SSID") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(psk, onPskChange, singleLine = true,
                    label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = ssid.isNotBlank(), onClick = onJoin) { Text("Join & list") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

// ---- previews ----

private val previewCameras = listOf(
    Profile("1", "D780", "AAAA_0000000", "AA:BB:CC:DD:EE:FF", "a1b2c3d4e5f60718", "11:22:33:44:55:66"),
    Profile("2", "Z6 II", "", "", "0011223344556677"),
)

@Preview(showBackground = true, name = "Camera info")
@Composable
private fun CameraInfoDialogPreview() = MaterialTheme {
    CameraInfoDialog(
        "Profile: D780\nBluetooth name: AAAA_0000000\nCamera MAC: AA:BB:CC:DD:EE:FF\n" +
            "Last BLE address: 11:22:33:44:55:66\nBonded (classic BT): yes, XX:XX:XX:XX:EE:FF\n" +
            "Client ID: a1b2c3d4e5f60718\nClient name: Android_SM-S721B_3378", onClose = {})
}

@Preview(showBackground = true, name = "Cameras")
@Composable
private fun CamerasDialogPreview() = MaterialTheme {
    CamerasDialog(previewCameras, activeId = "1", locked = false, onSelect = {}, onDelete = {}, onAdd = {}, onClose = {})
}

@Preview(showBackground = true, name = "Cameras (auto-sync running)")
@Composable
private fun CamerasDialogLockedPreview() = MaterialTheme {
    CamerasDialog(previewCameras, activeId = "1", locked = true, onSelect = {}, onDelete = {}, onAdd = {}, onClose = {})
}

@Preview(showBackground = true, name = "Add camera")
@Composable
private fun AddCameraDialogPreview() = MaterialTheme { AddCameraDialog(onAdd = { _, _, _ -> }, onCancel = {}) }

@Preview(showBackground = true, name = "Auto-sync interval")
@Composable
private fun IntervalDialogPreview() = MaterialTheme { IntervalDialog(current = 10, onSet = {}, onClose = {}) }

@Preview(showBackground = true, name = "Manual Wi-Fi")
@Composable
private fun ManualWifiDialogPreview() = MaterialTheme {
    ManualWifiDialog("AAAA_0000000_SnapBridge", "12345678", {}, {}, onJoin = {}, onCancel = {})
}
