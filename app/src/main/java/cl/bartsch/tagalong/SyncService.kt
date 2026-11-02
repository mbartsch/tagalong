package cl.bartsch.tagalong

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that holds the BLE link to the camera and refreshes time + GPS every
 * `sync_interval_s` seconds (SharedPreferences "settings"; re-read each tick). Reconnects after
 * a failure. Started from the visible activity, so no background-location permission is needed.
 */
@SuppressLint("MissingPermission")
class SyncService : Service() {
    companion object {
        val running = MutableStateFlow(false)
        val logs = MutableSharedFlow<String>(extraBufferCapacity = 128)
        private const val CHANNEL = "autosync"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "cl.bartsch.tagalong.STOP"

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, SyncService::class.java))
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, SyncService::class.java)) }
        fun intervalSec(ctx: Context) =
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("sync_interval_s", 10)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private fun log(s: String) { android.util.Log.i("Tagalong", s); logs.tryEmit(s) }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Auto-sync", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIF_ID, notif("Starting ..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (job == null) {
            running.value = true
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tagalong:sync").apply { acquire() }
            job = scope.launch { loop() }
        }
        return START_NOT_STICKY
    }

    private fun notif(text: String): Notification {
        val stop = PendingIntent.getService(this, 0,
            Intent(this, SyncService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Tagalong auto-sync")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun status(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif(text))
    }

    private suspend fun loop() {
        val tracker = FixTracker(this)
        // GPS cadence is capped at 30 s so a fix is always fresh enough for the 60 s staleness check.
        tracker.start(minOf(intervalSec(this), 30) * 1000L)
        try {
            var retrySec = 10   // backoff while the camera is unreachable: 10 -> 30 -> 60 s
            while (scope.isActive) {
                val ble = TagalongBle(this, Identity.clientId(this)) { log(it) }
                try {
                    status("Connecting ...")
                    withContext(Dispatchers.IO) { ble.connect(TagalongScanner(this@SyncService) { log(it) }.scan()); ble.authenticate() }
                    retrySec = 10
                    log("auto-sync running, every ${intervalSec(this)} s")
                    var first = true
                    while (scope.isActive) {
                        val loc = tracker.best()
                        if (loc == null) { log("waiting for GPS fix ..."); status("Waiting for GPS fix") }
                        else {
                            withTimeout(20_000) { withContext(Dispatchers.IO) {
                                if (first) ble.syncTimeAndLocation(loc.first, loc.second, loc.third)
                                else ble.writeTimeAndLocation(loc.first, loc.second, loc.third)
                            } }
                            first = false
                            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                            log("synced ${"%.5f".format(loc.first)}, ${"%.5f".format(loc.second)}")
                            status("Last sync $t")
                        }
                        delay(intervalSec(this) * 1000L)
                    }
                } catch (e: Exception) {
                    // A write/connect timeout is a CancellationException subclass; only a real
                    // cancel (service stopped) may end the loop, a timeout means the link is dead.
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    log("link lost: ${e.javaClass.simpleName}: ${e.message}; retry in $retrySec s")
                    status("Reconnecting ...")
                } finally { ble.close() }
                delay(retrySec * 1000L)
                retrySec = if (retrySec < 30) 30 else 60
            }
        } finally { tracker.stop() }
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        running.value = false
        log("auto-sync off")
        super.onDestroy()
    }
}

/**
 * Keeps the freshest phone fix. Listens to GPS plus fused/network so it still works indoors
 * (like Google Maps); a GPS fix wins while it is fresh, otherwise the newest of the others.
 */
@SuppressLint("MissingPermission")
class FixTracker(ctx: Context) : LocationListener {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val fixes = java.util.concurrent.ConcurrentHashMap<String, Location>()
    private val providers = buildList {
        add(LocationManager.GPS_PROVIDER); add(LocationManager.NETWORK_PROVIDER)
        if (android.os.Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
    }

    fun start(intervalMs: Long) {
        for (p in providers) {
            runCatching { lm.getLastKnownLocation(p)?.let { fixes[p] = it } }
            runCatching { lm.requestLocationUpdates(p, intervalMs, 0f, this, Looper.getMainLooper()) }
        }
    }
    fun stop() { runCatching { lm.removeUpdates(this) } }

    /** Best fix no older than 60 s, or null. */
    fun best(): Triple<Double, Double, Double>? {
        val now = System.currentTimeMillis()
        val fresh = fixes.values.filter { now - it.time <= 60_000 }
        val l = fresh.firstOrNull { it.provider == LocationManager.GPS_PROVIDER && now - it.time <= 30_000 }
            ?: fresh.maxByOrNull { it.time } ?: return null
        return Triple(l.latitude, l.longitude, l.altitude)
    }

    override fun onLocationChanged(l: Location) { l.provider?.let { fixes[it] = l } }
    @Deprecated("compat") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
}
