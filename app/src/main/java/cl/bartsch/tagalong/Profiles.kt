package cl.bartsch.tagalong

import android.content.Context
import androidx.compose.ui.tooling.preview.Preview
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

/**
 * One camera = one profile: its own LSS client id (registered on that body), Bluetooth name and
 * (optional) public MAC. Exactly one profile is active at a time; scanner/identity follow it.
 *
 * @param btName  advertised name, e.g. "AAAA_0000000"; blank = learn it from the first camera found
 * @param mac     public BT MAC (informational + bond matching); the LE address rotates so scans
 *                never rely on it
 * @param lastAddr last LE address the camera was seen at
 */
data class Profile(
    val id: String,
    val name: String,
    val btName: String,
    val mac: String,
    val clientIdHex: String,
    val lastAddr: String = "",
)

object Profiles {
    private const val PREFS = "profiles"
    private const val KEY_LIST = "list"
    private const val KEY_ACTIVE = "active"

    private const val DEFAULT_NAME = "Camera"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun randomId() = ByteArray(8).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }


    fun list(ctx: Context): List<Profile> {
        val raw = prefs(ctx).getString(KEY_LIST, null)
        if (raw != null) {
            val a = JSONArray(raw)
            return (0 until a.length()).map { i ->
                a.getJSONObject(i).let { o ->
                    Profile(o.getString("id"), o.getString("name"), o.optString("btName"),
                        o.optString("mac"), o.getString("clientId"), o.optString("lastAddr"))
                }
            }
        }
        // First run: a blank profile; its Bluetooth name is learned from the first camera scanned.
        val seed = Profile(UUID.randomUUID().toString(), DEFAULT_NAME, "", "", randomId())
        save(ctx, listOf(seed))
        prefs(ctx).edit().putString(KEY_ACTIVE, seed.id).apply()
        return listOf(seed)
    }

    private fun save(ctx: Context, l: List<Profile>) {
        val a = JSONArray()
        l.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("btName", it.btName)
                .put("mac", it.mac).put("clientId", it.clientIdHex).put("lastAddr", it.lastAddr))
        }
        prefs(ctx).edit().putString(KEY_LIST, a.toString()).apply()
    }

    fun active(ctx: Context): Profile {
        val l = list(ctx)
        val id = prefs(ctx).getString(KEY_ACTIVE, null)
        return l.firstOrNull { it.id == id } ?: l.first()
    }

    fun setActive(ctx: Context, id: String) { prefs(ctx).edit().putString(KEY_ACTIVE, id).apply() }

    /** New profile with a fresh random client id; blank btName/mac are fine (btName is learned). */
    fun add(ctx: Context, name: String, btName: String, mac: String): Profile {
        val p = Profile(UUID.randomUUID().toString(), name.trim(), btName.trim(),
            mac.trim().uppercase(), randomId())
        save(ctx, list(ctx) + p)
        return p
    }

    fun update(ctx: Context, p: Profile) { save(ctx, list(ctx).map { if (it.id == p.id) p else it }) }

    /** Remove a profile; the last one cannot be deleted. Returns false if refused. */
    fun remove(ctx: Context, id: String): Boolean {
        val l = list(ctx)
        if (l.size <= 1) return false
        val rest = l.filter { it.id != id }
        save(ctx, rest)
        if (prefs(ctx).getString(KEY_ACTIVE, null) == id) setActive(ctx, rest.first().id)
        return true
    }

    /** Give the active profile a new client id (forces re-registration on the camera). */
    fun newClientId(ctx: Context): String {
        val p = active(ctx).copy(clientIdHex = randomId())
        update(ctx, p)
        return p.clientIdHex
    }
}
