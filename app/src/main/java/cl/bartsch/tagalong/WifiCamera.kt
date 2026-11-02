package cl.bartsch.tagalong

import android.content.Context
import android.net.*
import android.net.wifi.WifiNetworkSpecifier
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Joins the camera's Wi-Fi AP as a local-only (no-internet) network and hands back the
 * [Network] whose socketFactory routes to the camera. On Android this survives the phone
 * keeping internet on cellular/other Wi-Fi — no roaming-away problem like on Windows.
 */
class WifiCamera(private val context: Context, private val log: (String) -> Unit = {}) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    suspend fun join(ssid: String, passphrase: String, timeoutMs: Int = 30_000): Network =
        suspendCancellableCoroutine { cont ->
            val specBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (passphrase.isNotEmpty()) specBuilder.setWpa2Passphrase(passphrase)
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specBuilder.build())
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    log("wifi onAvailable"); if (cont.isActive) cont.resume(network)
                }
                override fun onUnavailable() {
                    log("wifi onUnavailable (not found / declined / timed out)")
                    if (cont.isActive) cont.resumeWithException(
                        IllegalStateException("camera Wi-Fi join failed/declined"))
                }
            }
            callback = cb
            log("requesting wifi '$ssid' (approve the phone dialog) ...")
            cm.requestNetwork(req, cb, timeoutMs)
            cont.invokeOnCancellation { release() }
        }

    /** The camera's IP on the joined AP: DHCP server / default-route gateway, else .1 of our subnet. */
    fun cameraHost(network: Network): String {
        val lp = cm.getLinkProperties(network)
        lp?.let { log("link addrs=${it.linkAddresses} routes=${it.routes}") }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            lp?.dhcpServerAddress?.hostAddress?.let { log("dhcp server=$it"); return it }
        }
        lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway?.hostAddress?.contains('.') == true }
            ?.gateway?.hostAddress?.let { log("gateway=$it"); return it }
        // fallback: replace last octet of our IPv4 with .1
        lp?.linkAddresses?.firstOrNull { it.address.hostAddress?.contains('.') == true }
            ?.address?.hostAddress?.let { ip ->
                val host = ip.substringBeforeLast('.') + ".1"; log("derived host=$host"); return host
            }
        return "192.168.1.1"
    }

    fun release() { callback?.let { cm.unregisterNetworkCallback(it) }; callback = null }
}
