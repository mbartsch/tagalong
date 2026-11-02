package cl.bartsch.tagalong

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** Stateful host: wires a live [CameraSession] to the stateless [PhotosContent] / [PhotoViewer]. */
@Composable
fun PhotosScreen(session: CameraSession, onClose: () -> Unit, log: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var viewing by remember { mutableStateOf<Int?>(null) }   // index into session.items
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(session) {
        try { session.loadItems() } catch (e: Exception) { log("ERR list: ${e.javaClass.simpleName}: ${e.message}") }
    }

    fun download(list: List<PtpIp.ObjectInfo>, original: Boolean) {
        if (busy || list.isEmpty()) return
        scope.launch {
            busy = true
            var ok = 0
            list.forEachIndexed { n, i ->
                status = "Downloading ${n + 1}/${list.size}: ${i.name}"
                try {
                    var lastMb = -1L
                    session.saveToGallery(i, original) { done, total ->
                        val mb = done shr 20
                        if (mb != lastMb) { lastMb = mb; status = "Downloading ${n + 1}/${list.size}: ${i.name}  $mb/${total shr 20} MB" }
                    }
                    ok++
                } catch (e: Exception) { log("ERR ${i.name}: ${e.javaClass.simpleName}: ${e.message}") }
            }
            status = "Saved $ok/${list.size} to ${if (original) "Download" else "Pictures"}/${session.folder}"
            log(status); selected = emptySet(); busy = false
        }
    }

    BackHandler {
        when {
            viewing != null -> viewing = null
            selected.isNotEmpty() -> selected = emptySet()
            else -> onClose()
        }
    }

    PhotosContent(
        items = session.items, thumbs = session.thumbs, selected = selected,
        loading = session.loading, status = status, busy = busy,
        onNeedThumb = { h -> scope.launch { runCatching { session.ensureThumb(h) } } },
        onOpen = { idx ->
            val h = session.items[idx].handle
            if (selected.isNotEmpty()) selected = if (h in selected) selected - h else selected + h
            else viewing = idx
        },
        onSelect = { idx -> session.items[idx].handle.let { selected = if (it in selected) selected - it else selected + it } },
        onDownloadJpeg = { download(session.items.filter { it.handle in selected }, original = false) },
        onDownloadOriginal = { download(session.items.filter { it.handle in selected }, original = true) },
        onClearSelection = { selected = emptySet() },
        onBack = onClose,
    )

    viewing?.let { idx ->
        val item = session.items.getOrNull(idx) ?: return@let
        var bmp by remember(item.handle) { mutableStateOf(session.thumbs[item.handle]) }
        var stage by remember(item.handle) { mutableStateOf<String?>("Loading ...") }
        LaunchedEffect(item.handle) {
            try {
                session.largeThumb(item.handle)?.let { bmp = it }
                stage = "Loading full preview ..."
                session.preview(item)?.let { bmp = it }
                stage = null
            } catch (e: Exception) { stage = "Preview failed: ${e.message}" }
        }
        PhotoViewer(
            name = item.name, sizeBytes = item.size, bitmap = bmp, stage = stage, busy = busy, status = status,
            hasPrev = idx > 0, hasNext = idx < session.items.lastIndex,
            onPrev = { viewing = idx - 1 }, onNext = { viewing = idx + 1 },
            onDownloadJpeg = { download(listOf(item), original = false) },
            onDownloadOriginal = { download(listOf(item), original = true) },
            onClose = { viewing = null },
        )
    }
}

/** Thumbnail grid with a top bar; stateless so it can be previewed. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotosContent(
    items: List<PtpIp.ObjectInfo>,
    thumbs: Map<Int, Bitmap>,
    selected: Set<Int>,
    loading: Boolean,
    status: String,
    busy: Boolean,
    onNeedThumb: (Int) -> Unit,
    onOpen: (Int) -> Unit,
    onSelect: (Int) -> Unit,
    onDownloadJpeg: () -> Unit,
    onDownloadOriginal: () -> Unit,
    onClearSelection: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text(
                if (selected.isEmpty()) "Photos (${items.size}${if (loading) "+" else ""})" else "${selected.size} selected",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (selected.isNotEmpty()) {
                TextButton(enabled = !busy, onClick = onDownloadJpeg) { Text("JPEG") }
                TextButton(enabled = !busy, onClick = onDownloadOriginal) { Text("NEF") }
                TextButton(onClick = onClearSelection) { Text("Clear") }
            }
        }
        if (loading || status.isNotBlank() || busy) {
            if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if (status.isNotBlank()) status else "Reading card ...", style = MaterialTheme.typography.bodySmall)
        } else if (items.isNotEmpty() && selected.isEmpty()) {
            Text("Tap to view · long-press to select", style = MaterialTheme.typography.bodySmall)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize().padding(top = 6.dp),
        ) {
            items(items.size, key = { items[it].handle }) { idx ->
                val item = items[idx]
                val isSel = item.handle in selected
                LaunchedEffect(item.handle) { if (thumbs[item.handle] == null) onNeedThumb(item.handle) }
                Box(
                    Modifier.aspectRatio(1f).clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .then(if (isSel) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier)
                        .combinedClickable(onClick = { onOpen(idx) }, onLongClick = { onSelect(idx) }),
                ) {
                    thumbs[item.handle]?.let {
                        Image(it.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Text(
                        item.name.substringBeforeLast('.') + if (item.name.endsWith(".NEF", true)) " · NEF" else "",
                        fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .background(Color(0x99000000)).padding(horizontal = 4.dp, vertical = 2.dp))
                    if (isSel) Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center) { Text("✓", color = Color.White, fontSize = 14.sp) }
                }
            }
        }
    }
}

/** Full-screen viewer: swipe or use ‹ › to move, download the JPEG or the original. */
@Composable
fun PhotoViewer(
    name: String,
    sizeBytes: Long,
    bitmap: Bitmap?,
    stage: String?,
    busy: Boolean,
    status: String,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onDownloadJpeg: () -> Unit,
    onDownloadOriginal: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$name · ${sizeBytes / 1_000_000} MB", color = Color.White, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().pointerInput(name) {
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onDragEnd = { if (dx > 150f && hasPrev) onPrev() else if (dx < -150f && hasNext) onNext() },
                        onHorizontalDrag = { _, d -> dx += d })
                },
                contentAlignment = Alignment.Center,
            ) {
                bitmap?.let { Image(it.asImageBitmap(), name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                stage?.let {
                    Text(it, color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).background(Color(0x99000000)).padding(6.dp))
                }
            }
            if (status.isNotBlank()) Text(status, color = Color.White, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = hasPrev, onClick = onPrev) { Text("‹") }
                Button(enabled = !busy, onClick = onDownloadJpeg, modifier = Modifier.weight(1f)) { Text("Save JPEG") }
                Button(enabled = !busy, onClick = onDownloadOriginal, modifier = Modifier.weight(1f)) { Text("Save NEF") }
                OutlinedButton(enabled = hasNext, onClick = onNext) { Text("›") }
            }
        }
    }
}

// ---- previews ----

private fun sampleItems() = listOf(
    PtpIp.ObjectInfo(1, 0x3000, 27_595_078, 6048, 4024, "_MBR0337.NEF"),
    PtpIp.ObjectInfo(2, 0x3000, 30_080_871, 6048, 4024, "_MBR0338.NEF"),
    PtpIp.ObjectInfo(3, 0x3801, 9_400_000, 6048, 4024, "_MBR0339.JPG"),
    PtpIp.ObjectInfo(4, 0x3000, 28_990_938, 6048, 4024, "_MBR0340.NEF"),
)

private fun sampleBitmap(color: Int) = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

@Preview(showBackground = true, name = "Photos grid")
@Composable
private fun PhotosContentPreview() {
    MaterialTheme {
        PhotosContent(
            items = sampleItems(),
            thumbs = mapOf(1 to sampleBitmap(0xFF6D8FB3.toInt()), 2 to sampleBitmap(0xFFB38F6D.toInt()), 3 to sampleBitmap(0xFF8FB36D.toInt())),
            selected = setOf(2), loading = true, status = "", busy = false,
            onNeedThumb = {}, onOpen = {}, onSelect = {}, onDownloadJpeg = {}, onDownloadOriginal = {},
            onClearSelection = {}, onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Photos empty / loading")
@Composable
private fun PhotosContentLoadingPreview() {
    MaterialTheme {
        PhotosContent(
            items = emptyList(), thumbs = emptyMap(), selected = emptySet(), loading = true, status = "", busy = false,
            onNeedThumb = {}, onOpen = {}, onSelect = {}, onDownloadJpeg = {}, onDownloadOriginal = {},
            onClearSelection = {}, onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Downloading")
@Composable
private fun PhotosContentDownloadingPreview() {
    MaterialTheme {
        PhotosContent(
            items = sampleItems(), thumbs = emptyMap(), selected = setOf(1, 2), loading = false,
            status = "Downloading 1/2: _MBR0337.NEF", busy = true,
            onNeedThumb = {}, onOpen = {}, onSelect = {}, onDownloadJpeg = {}, onDownloadOriginal = {},
            onClearSelection = {}, onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Viewer")
@Composable
private fun PhotoViewerPreview() {
    MaterialTheme {
        PhotoViewer(
            name = "_MBR0337.NEF", sizeBytes = 27_595_078, bitmap = sampleBitmap(0xFF6D8FB3.toInt()),
            stage = "Loading full preview ...", busy = false, status = "",
            hasPrev = false, hasNext = true, onPrev = {}, onNext = {},
            onDownloadJpeg = {}, onDownloadOriginal = {}, onClose = {},
        )
    }
}

@Preview(showBackground = true, name = "Viewer (loading)")
@Composable
private fun PhotoViewerLoadingPreview() {
    MaterialTheme {
        PhotoViewer(
            name = "_MBR0338.NEF", sizeBytes = 30_080_871, bitmap = null,
            stage = "Loading ...", busy = true, status = "Downloading 1/1: _MBR0338.NEF  3/28 MB",
            hasPrev = true, hasNext = true, onPrev = {}, onNext = {},
            onDownloadJpeg = {}, onDownloadOriginal = {}, onClose = {},
        )
    }
}
