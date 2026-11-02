package cl.bartsch.tagalong

import android.content.Context

/**
 * The 8-byte LSS client ("device") ID presented in auth stage 1. birdcam's a900.go uses a
 * random uint64 here; it is an opaque identity token the camera registers while in pairing
 * mode and validates on later connects — it is NOT used in the crypto proof. It is stored per
 * camera in the active [Profile], so each body registers its own id.
 */
object Identity {
    fun clientId(ctx: Context): ByteArray = clientIdHex(ctx).let { hex ->
        ByteArray(8) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Mint a fresh random identity for the active camera (forces re-registration). */
    fun newClientId(ctx: Context): ByteArray {
        Profiles.newClientId(ctx)
        return clientId(ctx)
    }

    fun clientIdHex(ctx: Context): String = Profiles.active(ctx).clientIdHex
}
